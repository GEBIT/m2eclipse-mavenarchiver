/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.m2e.mavenarchiver.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReflectionUtils;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.m2e.core.internal.M2EUtils;
import org.eclipse.m2e.core.lifecyclemapping.model.IPluginExecutionMetadata;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.MojoExecutionKey;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;

/**
 * This configurator is used to generate files as per the org.apache.maven
 * maven-archiver configuration.<br/>
 * During an eclipse build, it :<br/>
 * <ul>
 * <li>Generates pom.properties and pom.xml files under the
 * ${outputDir}/META-INF/maven/${project.groupId}/${project.artifactId} folder
 * <br/>
 * In maven, this behaviour is implemented by
 * org.apache.maven.archiver.PomPropertiesUtil from org.apache.maven
 * maven-archiver.</li>
 * <li>Generates the MANIFEST.MF under the ${outputDir}/META-INF/ folder</li>
 * </ul>
 * All mojos that use MavenArchiver (jar mojo, ejb mojo and so on) produce these
 * files during cli build. In order to reproduce this behaviour in eclipse, a
 * dedicated configurator must be created for each of these Mojos (JarMojo,
 * EjbMojo ...).
 *
 * @see https://svn.apache.org/repos/asf/maven/shared/trunk/maven-archiver/src/main/java/org/apache/maven/archiver/
 *      PomPropertiesUtil.java
 * @see http://maven.apache.org/shared/maven-archiver/index.html
 * @author igor
 * @author Fred Bricon
 */
public abstract class AbstractMavenArchiverConfigurator extends AbstractProjectConfigurator {

	private static final String GET_MANIFEST = "getManifest";

	private static final String MANIFEST_ENTRIES_NODE = "manifestEntries";

	private static final String ARCHIVE_NODE = "archive";

	private static final String CREATED_BY_ENTRY = "Created-By";

	private static final String MAVEN_ARCHIVER_CLASS = "org.apache.maven.archiver.MavenArchiver";

	private static final String M2E = "Maven Integration for Eclipse";

	private static final String GENERATED_BY_M2E = "Generated by " + M2E;
	
	private static final boolean JDT_SUPPORTS_MODULES;
	
	static {
	    boolean isModuleSupportAvailable = false;
	    try {
	      Class.forName("org.eclipse.jdt.core.IModuleDescription");
	      isModuleSupportAvailable = true;
	    } catch(Throwable ignored) {
	    }
	    JDT_SUPPORTS_MODULES = isModuleSupportAvailable;
	  }

	public void configure(ProjectConfigurationRequest request, IProgressMonitor monitor) throws CoreException {
		// Nothing to configure
	}

	/**
	 * Gets the MojoExecutionKey from which to retrieve the maven archiver instance.
	 * 
	 * @return the MojoExecutionKey from which to retrieve the maven archiver
	 *         instance.
	 */
	protected abstract MojoExecutionKey getExecutionKey();

	public AbstractBuildParticipant getBuildParticipant(final IMavenProjectFacade projectFacade,
			MojoExecution execution, IPluginExecutionMetadata executionMetadata) {

		MojoExecutionKey key = getExecutionKey();
		if (execution.getArtifactId().equals(key.getArtifactId()) && execution.getGoal().equals(key.getGoal())) {

			return new AbstractBuildParticipant() {
				public Set<IProject> build(int kind, IProgressMonitor monitor) throws Exception {
					IResourceDelta delta = getDelta(projectFacade.getProject());

					boolean forceManifest = false;
					if (delta != null) {
						ManifestDeltaVisitor visitor = new ManifestDeltaVisitor();
						delta.accept(visitor);
						forceManifest = visitor.foundManifest;
					}

					// this will be true for full builds too
					boolean forcePom = getBuildContext().hasDelta(IMavenConstants.POM_FILE_NAME);

					// The manifest will be (re)generated if it doesn't exist or an existing
					// manifest is modified
					mavenProjectChanged(projectFacade, null, forceManifest || forcePom, monitor);

					if (!forcePom) {
						IProject project = projectFacade.getProject();
						IWorkspaceRoot root = project.getWorkspace().getRoot();
						ArtifactKey mavenProject = projectFacade.getArtifactKey();
						IPath outputPath = getOutputDir(projectFacade).append("META-INF/maven")
								.append(mavenProject.getGroupId()).append(mavenProject.getArtifactId());

						IFile pom = root.getFolder(outputPath).getFile(IMavenConstants.POM_FILE_NAME);
						forcePom = !pom.exists();
					}
					if (forcePom) {
						writePom(projectFacade, monitor);
					}

					return null;
				}
			};
		}
		return null;
	}

	private class ManifestDeltaVisitor implements IResourceDeltaVisitor {

		private final String MANIFEST = "MANIFEST.MF";

		boolean foundManifest;

		public boolean visit(IResourceDelta delta) throws CoreException {
			if (delta.getResource() instanceof IFile && MANIFEST.equals(delta.getResource().getName())) {
				foundManifest = true;
			}
			return !foundManifest;
		}
	}

	/**
	 * Generates the project manifest if necessary, that is if the project manifest
	 * configuration has changed or if the dependencies have changed.
	 */
	public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {

		IMavenProjectFacade oldFacade = event.getOldMavenProject();
		IMavenProjectFacade newFacade = event.getMavenProject();
		if (oldFacade == null && newFacade == null) {
			return;
		}
		mavenProjectChanged(newFacade, oldFacade, false, monitor);
	}

	public void mavenProjectChanged(IMavenProjectFacade newFacade, IMavenProjectFacade oldFacade,
			boolean forceGeneration, IProgressMonitor monitor) throws CoreException {

		final IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IFolder outputdir = root.getFolder(getOutputDir(newFacade));

		IFile manifest = outputdir.getFolder("META-INF").getFile("MANIFEST.MF");

		if (forceGeneration || needsNewManifest(manifest, oldFacade, newFacade, monitor)) {
			if (generateManifest(newFacade, manifest, monitor)) {
				refresh(newFacade, manifest, monitor);
			}
		}

	}

	/**
	 * Gets the output directory in which the files will be generated
	 * 
	 * @param facade the maven project facade to get the output directory from.
	 * @return the full workspace path to the output directory
	 */
	protected abstract IPath getOutputDir(IMavenProjectFacade facade);

	/**
	 * Refreshes the output resource of the maven project after file generation.<br/>
	 * Implementations can override this method to add some post processing.
	 * 
	 * @param mavenFacade    the maven facade
	 * @param outputResource the output resource to refresh
	 * @param monitor        the progress monitor
	 * @throws CoreException
	 */
	protected void refresh(IMavenProjectFacade mavenFacade, IResource outputResource, IProgressMonitor monitor)
			throws CoreException {
		// refresh the target folder
		if (outputResource.exists() && !outputResource.isDerived(IResource.CHECK_ANCESTORS)) {
			try {
				outputResource.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			} catch (Exception e) {
				e.printStackTrace();
				// random java.lang.IllegalArgumentException: Element not found:
				// /parent/project/target/classes/META-INF.
				// occur when refreshing the folder on project import / creation
				// See https://bugs.eclipse.org/bugs/show_bug.cgi?id=244315
			}
		}
	}

	/**
	 * Checks if the MANIFEST.MF needs to be regenerated. That is if :
	 * <ul>
	 * <li>it doesn't already exist</li>
	 * <li>the maven project configuration changed</li>
	 * <li>the maven project dependencies changed</li>
	 * </ul>
	 * Implementations can override this method to add pre-conditions.
	 * 
	 * @param manifest  the MANIFEST.MF file to control
	 * @param oldFacade the old maven facade project configuration
	 * @param newFacade the new maven facade project configuration
	 * @param monitor   the progress monitor
	 * @return true if the MANIFEST.MF needs to be regenerated
	 */
	protected boolean needsNewManifest(IFile manifest, IMavenProjectFacade oldFacade, IMavenProjectFacade newFacade,
			IProgressMonitor monitor) {

		if (!manifest.getLocation().toFile().exists()) {
			return true;
		}
		// Can't compare to a previous state, so assuming it's unchanged
		// This situation actually occurs during incremental builds,
		// when called from the buildParticipant
		if (oldFacade == null || oldFacade.getMavenProject() == null) {
			return false;
		}

		MavenProject newProject = newFacade.getMavenProject();
		MavenProject oldProject = oldFacade.getMavenProject();

		// Assume Sets of artifacts are actually ordered
		if (dependenciesChanged(
				oldProject.getArtifacts() == null ? null : new ArrayList<Artifact>(oldProject.getArtifacts()),
				newProject.getArtifacts() == null ? null : new ArrayList<Artifact>(newProject.getArtifacts()))) {
			return true;
		}

		Xpp3Dom oldArchiveConfig = getArchiveConfiguration(oldProject);
		Xpp3Dom newArchiveConfig = getArchiveConfiguration(newProject);

		if (newArchiveConfig != null && !newArchiveConfig.equals(oldArchiveConfig) || oldArchiveConfig != null) {
			return true;
		}

		// Name always not null
		if (!newProject.getName().equals(oldProject.getName())) {
			return true;
		}

		String oldOrganizationName = oldProject.getOrganization() == null ? null
				: oldProject.getOrganization().getName();
		String newOrganizationName = newProject.getOrganization() == null ? null
				: newProject.getOrganization().getName();

		if (newOrganizationName != null && !newOrganizationName.equals(oldOrganizationName)
				|| oldOrganizationName != null && newOrganizationName == null) {
			return true;
		}
		return false;
	}

	/**
	 * Compare 2 lists of Artifacts for change
	 * 
	 * @param artifacts the reference artifact list
	 * @param others    the artifacts to compare to
	 * @return true if the 2 artifact lists are different
	 */
	private boolean dependenciesChanged(List<Artifact> artifacts, List<Artifact> others) {
		if (artifacts == others) {
			return false;
		}
		if (artifacts.size() != others.size()) {
			return true;
		}
		for (int i = 0; i < artifacts.size(); i++) {
			Artifact dep = artifacts.get(i);
			Artifact dep2 = others.get(i);
			if (!areEqual(dep, dep2)) {
				return true;
			}

		}
		return false;
	}

	@SuppressWarnings("null")
	private boolean areEqual(Artifact dep, Artifact other) {
		if (dep == other) {
			return true;
		}
		if (dep == null && other != null || dep != null && other == null) {
			return false;
		}
		// So both artifacts are not null here.
		// Fast (to type) and easy way to compare artifacts.
		// Proper solution would not rely on internal implementation of toString
		if (dep.toString().equals(other.toString()) && dep.isOptional() == other.isOptional()) {
			return true;
		}
		return false;
	}

	protected Xpp3Dom getArchiveConfiguration(MavenProject mavenProject) {
		Plugin plugin = mavenProject.getPlugin(getPluginKey());
		if (plugin == null)
			return null;

		Xpp3Dom pluginConfig = (Xpp3Dom) plugin.getConfiguration();
		if (pluginConfig == null) {
			return null;
		}
		return pluginConfig.getChild(ARCHIVE_NODE);
	}

	public boolean generateManifest(IMavenProjectFacade mavenFacade, IFile manifest, IProgressMonitor monitor)
			throws CoreException {

		MavenProject mavenProject = mavenFacade.getMavenProject();
		Set<Artifact> originalArtifacts = mavenProject.getArtifacts();
		boolean parentHierarchyLoaded = false;
		try {
			markerManager.deleteMarkers(mavenFacade.getPom(), MavenArchiverConstants.MAVENARCHIVER_MARKER_ERROR);

			// Find the mojoExecution
			MavenSession session = getMavenSession(mavenFacade, monitor);

			parentHierarchyLoaded = loadParentHierarchy(mavenFacade, monitor);

			ClassLoader originalTCL = Thread.currentThread().getContextClassLoader();
			try {
				ClassRealm projectRealm = mavenProject.getClassRealm();
				if (projectRealm != null && projectRealm != originalTCL) {
					Thread.currentThread().setContextClassLoader(projectRealm);
				}
				MavenExecutionPlan executionPlan = maven.calculateExecutionPlan(session, mavenProject,
						Collections.singletonList("package"), true, monitor);
				MojoExecution mojoExecution = getExecution(executionPlan, getExecutionKey());
				if (mojoExecution == null) {
					return false;
				}

				// Get the target manifest file
				IFolder destinationFolder = (IFolder) manifest.getParent();
				M2EUtils.createFolder(destinationFolder, true, monitor);

				// Workspace project artifacts don't have a valid getFile(), so won't appear in
				// the manifest
				// We need to workaround the issue by creating fake files for such artifacts.
				// We could also use a custom File implementation having "public boolean
				// exists(){return true;}"
				mavenProject.setArtifacts(fixArtifactFileNames(mavenFacade));

				// Invoke the manifest generation API via reflection
				return reflectManifestGeneration(mavenFacade, mojoExecution, session,
						new File(manifest.getLocation().toOSString()));
			} finally {
				Thread.currentThread().setContextClassLoader(originalTCL);
			}
		} catch (Exception ex) {
			markerManager.addErrorMarkers(mavenFacade.getPom(), MavenArchiverConstants.MAVENARCHIVER_MARKER_ERROR, ex);
			return false;
		} finally {
			// Restore the project state
			mavenProject.setArtifacts(originalArtifacts);
			if (parentHierarchyLoaded) {
				mavenProject.setParent(null);
			}
		}

	}

	private MavenSession getMavenSession(IMavenProjectFacade mavenFacade, IProgressMonitor monitor)
			throws CoreException {
		IMavenProjectRegistry projectManager = MavenPlugin.getMavenProjectRegistry();
		IMaven maven = MavenPlugin.getMaven();
		// Create a maven request + session
		IFile pomResource = mavenFacade.getPom();
		MavenExecutionRequest request = projectManager.createExecutionRequest(pomResource,
				mavenFacade.getResolverConfiguration(), monitor);
		request.setOffline(MavenPlugin.getMavenConfiguration().isOffline());
		return maven.createSession(request, mavenFacade.getMavenProject());
	}

	private boolean reflectManifestGeneration(IMavenProjectFacade facade, MojoExecution mojoExecution,
			MavenSession session, File manifestFile) throws Exception {

		ClassLoader loader = null;
		Class<? extends Mojo> mojoClass;
		Mojo mojo = null;

		Xpp3Dom originalConfig = mojoExecution.getConfiguration();
		Xpp3Dom customConfig = Xpp3DomUtils.mergeXpp3Dom(new Xpp3Dom("configuration"), originalConfig);

		Xpp3Dom useDefaultManifestFile = customConfig.getChild("useDefaultManifestFile");
		if (useDefaultManifestFile != null && Boolean.parseBoolean(useDefaultManifestFile.getValue())) {
			//<useDefaultManifestFile>true</useDefaultManifestFile> -> assume manifest is provided or generated by other mojo
			return false;
		}
		Xpp3Dom archiveConfig = customConfig.getChild("archive");
		if (archiveConfig != null && archiveConfig.getChild("manifestFile") != null) {
			//<archive><manifestFile>..</manifestFile></archive> -> assume manifest is provided or generated by other mojo
			return false;
		}

		MavenProject mavenProject = facade.getMavenProject();
		IProject project = facade.getProject();
		// Add custom manifest entries
		customizeManifest(customConfig, mavenProject);

		mojoExecution.setConfiguration(customConfig);

		mojo = maven.getConfiguredMojo(session, mojoExecution, Mojo.class);
		mojoClass = mojo.getClass();
		loader = mojoClass.getClassLoader();
		try {
			Object archiver = getArchiverInstance(mojoClass, mojo, project);
			if (archiver != null) {
				Field archiveConfigurationField = findField(getArchiveConfigurationFieldName(), mojoClass);
				archiveConfigurationField.setAccessible(true);
				Object archiveConfiguration = archiveConfigurationField.get(mojo);
				Object mavenArchiver = getMavenArchiver(archiver, manifestFile, loader);
				
				Object manifest = getManifest(session, mavenProject, archiveConfiguration, mavenArchiver);
				
				// Get the user provided manifest, if it exists
				Object userManifest = getProvidedManifest(manifest.getClass(), archiveConfiguration);
				
				// Merge both manifests, the user provided manifest data takes precedence
				mergeManifests(manifest, userManifest);
				
				// Serialize the Manifest instance to an actual file
				writeManifest(manifestFile, manifest);
				return true;
			}
		} finally {
			mojoExecution.setConfiguration(originalConfig);

			maven.releaseMojo(mojo, mojoExecution);
		}
		return false;
	}

	private void writeManifest(File manifestFile, Object manifest) throws UnsupportedEncodingException, FileNotFoundException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		PrintWriter printWriter = null;
		try {
			Method write = getWriteMethod(manifest);
			if (write != null) {
				printWriter = new PrintWriter(WriterFactory.newWriter(manifestFile, WriterFactory.UTF_8));
				write.invoke(manifest, printWriter);
			}		
		} finally {
			if (printWriter != null) {
				printWriter.close();
			}
		}
			
	}

	private Object getManifest(MavenSession session, MavenProject mavenProject, Object archiveConfiguration,
			Object mavenArchiver) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		Object manifest = null;
		Class<?> archiveConfigClass = archiveConfiguration.getClass();
		try {
			Method getManifest = mavenArchiver.getClass().getMethod(GET_MANIFEST, MavenSession.class,
					MavenProject.class, archiveConfigClass);

			// Create the Manifest instance
			manifest = getManifest.invoke(mavenArchiver, session, mavenProject, archiveConfiguration);

		} catch (NoSuchMethodException nsme) {
			// Fall back to legacy invocation
			Method getManifest = mavenArchiver.getClass().getMethod(GET_MANIFEST, MavenProject.class,
					archiveConfigClass);

			// Create the Manifest instance
			manifest = getManifest.invoke(mavenArchiver, mavenProject, archiveConfiguration);
		}
		return manifest;
	}

	private Object getArchiverInstance(Class<? extends Mojo> mojoClass, Mojo mojo, IProject project)
			throws IllegalAccessException {
		Object archiver = null;
		Field archiverField = findField(getArchiverFieldName(), mojoClass);
		if (archiverField == null) {
			// Since maven-jar-plugin 3.1.2, the field doesn't exist anymore, search for an
			// archiver map instead.
			// GBLD-783: archivers map is empty if accessed from thread with context class loader different from 
			// maven-jar-plugin ClassRealm
			ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(mojoClass.getClassLoader());
			try {
				Field archiversField = findField("archivers", mojoClass);
				if (archiversField != null) {
					archiversField.setAccessible(true);
					Map archivers = (Map) archiversField.get(mojo);
					String key = isModular(project)?"mjar":"jar";
					archiver = archivers.get(key);
				}
			} finally {
				Thread.currentThread().setContextClassLoader(oldClassLoader);
			}
		} else {
			archiverField.setAccessible(true);
			archiver = archiverField.get(mojo);
		}
		return archiver;
	}

	private boolean isModular(IProject project) {
		try {
			if (JDT_SUPPORTS_MODULES && project.hasNature(JavaCore.NATURE_ID)) {
				IJavaProject jp = JavaCore.create(project);
				return jp.getModuleDescription() != null;
			}
		} catch (Exception ignoreMe) {
		}
		return false;
	}

	private Method getWriteMethod(Object manifest) {
		for (Method m : manifest.getClass().getMethods()) {
			if ("write".equals(m.getName())) {
				Class<?>[] params = m.getParameterTypes();
				if (params.length == 1 && Writer.class.isAssignableFrom(params[0])) {
					return m;
				}
			}
		}
		return null;
	}

	/**
	 * Workaround for https://bugs.eclipse.org/bugs/show_bug.cgi?id=356725. Loads
	 * the parent project hierarchy if needed.
	 * 
	 * @param facade
	 * @param monitor
	 * @return true if parent projects had to be loaded.
	 * @throws CoreException
	 */
	private boolean loadParentHierarchy(IMavenProjectFacade facade, IProgressMonitor monitor) throws CoreException {
		boolean loadedParent = false;
		MavenProject mavenProject = facade.getMavenProject();
		try {
			if (mavenProject.getModel().getParent() == null || mavenProject.getParent() != null) {
				// If the getParent() method is called without error,
				// we can assume the project has been fully loaded, no need to continue.
				return false;
			}
		} catch (IllegalStateException e) {
			// The parent can not be loaded properly
		}
		MavenExecutionRequest request = null;
		while (mavenProject != null && mavenProject.getModel().getParent() != null) {
			if (monitor.isCanceled()) {
				break;
			}
			if (request == null) {
				request = projectManager.createExecutionRequest(facade, monitor);
			}
			MavenProject parentProject = maven.resolveParentProject(request, mavenProject, monitor);
			if (parentProject != null) {
				mavenProject.setParent(parentProject);
				loadedParent = true;
			}
			mavenProject = parentProject;
		}
		return loadedParent;
	}

	private Object getProvidedManifest(Class manifestClass, Object archiveConfiguration) throws SecurityException,
			IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {

		Object newManifest = null;
		Reader reader = null;
		try {
			Method getManifestFile = archiveConfiguration.getClass().getMethod("getManifestFile");
			File manifestFile = (File) getManifestFile.invoke(archiveConfiguration);

			if (manifestFile == null || !manifestFile.exists() || !manifestFile.canRead()) {
				return null;
			}

			reader = new FileReader(manifestFile);
			Constructor<?> constructor = manifestClass.getConstructor(Reader.class);
			newManifest = constructor.newInstance(reader);

		} catch (FileNotFoundException ex) {
			// ignore
		} catch (NoSuchMethodException ex) {
			// ignore, this is not supported by this archiver version
		} finally {
			IOUtil.close(reader);
		}
		return newManifest;
	}

	private void mergeManifests(Object manifest, Object sourceManifest) throws SecurityException, NoSuchMethodException,
			IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		if (sourceManifest == null)
			return;

		if (manifest instanceof Manifest && sourceManifest instanceof Manifest) {
			merge((Manifest) manifest, (Manifest) sourceManifest, false);
		} else {
			// keep backward compatibility with old plexus-archiver versions prior to 2.1
			Method merge = manifest.getClass().getMethod("merge", sourceManifest.getClass());
			merge.invoke(manifest, sourceManifest);
		}
	}

	/**
	 * @see org.codehaus.plexus.archiver.jar.JdkManifestFactory#merge()
	 */
	private void merge(Manifest target, Manifest other, boolean overwriteMain) {
		if (other != null) {
			final Attributes mainAttributes = target.getMainAttributes();
			if (overwriteMain) {
				mainAttributes.clear();
				mainAttributes.putAll(other.getMainAttributes());
			} else {
				mergeAttributes(mainAttributes, other.getMainAttributes());
			}

			for (Map.Entry<String, Attributes> o : other.getEntries().entrySet()) {
				Attributes ourSection = target.getAttributes(o.getKey());
				Attributes otherSection = o.getValue();
				if (ourSection == null) {
					if (otherSection != null) {
						target.getEntries().put(o.getKey(), (Attributes) otherSection.clone());
					}
				} else {
					mergeAttributes(ourSection, otherSection);
				}
			}
		}
	}

	/**
	 * @see org.codehaus.plexus.archiver.jar.JdkManifestFactory#mergeAttributes()
	 */
	private void mergeAttributes(java.util.jar.Attributes target, java.util.jar.Attributes section) {
		for (Object o : section.keySet()) {
			java.util.jar.Attributes.Name key = (Attributes.Name) o;
			final Object value = section.get(o);
			// the merge file always wins
			target.put(key, value);
		}
	}

	/**
	 * Get the Mojo's maven archiver field name.
	 * 
	 * @return the Mojo's maven archiver field name.
	 */
	protected abstract String getArchiverFieldName();

	/**
	 * Get the Mojo's archive configuration field name.
	 * 
	 * @return the Mojo's archive configuration field name.
	 */
	protected String getArchiveConfigurationFieldName() {
		return "archive";
	}

	private Set<Artifact> fixArtifactFileNames(IMavenProjectFacade facade) throws IOException, CoreException {
		Set<Artifact> artifacts = facade.getMavenProject().getArtifacts();
		if (artifacts == null)
			return null;
		Set<Artifact> newArtifacts = new LinkedHashSet<Artifact>(artifacts.size());

		ArtifactRepository localRepo = MavenPlugin.getMaven().getLocalRepository();

		for (Artifact a : artifacts) {
			Artifact artifact;
			if (a.getFile().isDirectory() || "pom.xml".equals(a.getFile().getName())) {
				// Workaround Driven Development : Create a dummy file associated with an
				// Artifact,
				// so this artifact won't be ignored during the resolution of the Class-Path
				// entry in the Manifest
				artifact = new DefaultArtifact(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getScope(),
						a.getType(), a.getClassifier(), a.getArtifactHandler());
				artifact.setFile(fakeFile(localRepo, a));
			} else {
				artifact = a;
			}

			newArtifacts.add(artifact);
		}
		return newArtifacts;
	}

	private void customizeManifest(Xpp3Dom customConfig, MavenProject mavenProject) throws CoreException {
		if (customConfig == null)
			return;
		Xpp3Dom archiveNode = customConfig.getChild(ARCHIVE_NODE);
		if (archiveNode == null) {
			archiveNode = new Xpp3Dom(ARCHIVE_NODE);
			customConfig.addChild(archiveNode);
		}

		Xpp3Dom manifestEntriesNode = archiveNode.getChild(MANIFEST_ENTRIES_NODE);
		if (manifestEntriesNode == null) {
			manifestEntriesNode = new Xpp3Dom(MANIFEST_ENTRIES_NODE);
			archiveNode.addChild(manifestEntriesNode);
		}

		Xpp3Dom createdByNode = manifestEntriesNode.getChild(CREATED_BY_ENTRY);
		// Add a default "Created-By: Maven Integration for Eclipse", because it's cool
		if (createdByNode == null) {
			createdByNode = new Xpp3Dom(CREATED_BY_ENTRY);
			createdByNode.setValue(M2E);
			manifestEntriesNode.addChild(createdByNode);
		}
	}

	private Field findField(String name, Class<?> clazz) {
		return ReflectionUtils.getFieldByNameIncludingSuperclasses(name, clazz);
	}

	private Object getMavenArchiver(Object archiver, File manifestFile, ClassLoader loader)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException, SecurityException,
			NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
		Class<Object> mavenArchiverClass = (Class<Object>) Class.forName(MAVEN_ARCHIVER_CLASS, false, loader);
		Object mavenArchiver = mavenArchiverClass.newInstance();

		Method setArchiver = null;
		// TODO do a proper lookup
		for (Method m : mavenArchiver.getClass().getMethods()) {
			if ("setArchiver".equals(m.getName())) {
				setArchiver = m;
				break;
			}
		}

		setArchiver.invoke(mavenArchiver, archiver);
		Method setOutputFile = mavenArchiverClass.getMethod("setOutputFile", File.class);
		setOutputFile.invoke(mavenArchiver, manifestFile);
		return mavenArchiver;
	}

	private String getPluginKey() {
		MojoExecutionKey execution = getExecutionKey();
		return execution.getGroupId() + ":" + execution.getArtifactId();
	}

	private MojoExecution getExecution(MavenExecutionPlan executionPlan, MojoExecutionKey key) {
		for (MojoExecution execution : executionPlan.getMojoExecutions()) {
			if (key.getArtifactId().equals(execution.getArtifactId()) && key.getGroupId().equals(execution.getGroupId())
					&& key.getGoal().equals(execution.getGoal())) {
				return execution;
			}
		}
		return null;
	}

	/**
	 * Generates a temporary file in the system temporary folder for a given
	 * artifact
	 * 
	 * @param localRepo the local repository used to compute the file path
	 * @param artifact  the artifact to generate a temporary file for
	 * @return a temporary file sitting under
	 *         ${"java.io.tmpdir"}/fakerepo/${groupid}/{artifactid}/${version}/
	 * @throws IOException if the file could not be created
	 */
	private File fakeFile(ArtifactRepository localRepo, Artifact artifact) throws IOException {
		File fakeRepo = new File(System.getProperty("java.io.tmpdir"), "fakerepo");

		File fakeFile = new File(fakeRepo, localRepo.pathOf(artifact));
		File parent = fakeFile.getParentFile();
		if (!parent.exists()) {
			parent.mkdirs();
		}

		if (!fakeFile.exists()) {
			fakeFile.createNewFile();
		}
		return fakeFile;
	}

	protected void writePom(IMavenProjectFacade facade, IProgressMonitor monitor) throws CoreException {
		IProject project = facade.getProject();
		ArtifactKey mavenProject = facade.getArtifactKey();
		IWorkspaceRoot root = project.getWorkspace().getRoot();

		IPath outputPath = getOutputDir(facade).append("META-INF/maven").append(mavenProject.getGroupId())
				.append(mavenProject.getArtifactId());

		IFolder output = root.getFolder(outputPath);
		M2EUtils.createFolder(output, true, monitor);

		Properties properties = new Properties();
		properties.put("groupId", mavenProject.getGroupId());
		properties.put("artifactId", mavenProject.getArtifactId());
		properties.put("version", mavenProject.getVersion());
		properties.put("m2e.projectName", project.getName());
		properties.put("m2e.projectLocation", project.getLocation().toOSString());

		IFile pomProperties = output.getFile("pom.properties");
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try {
			properties.store(buf, GENERATED_BY_M2E);
		} catch (IOException ex) {
		}

		if (pomProperties.exists()) {
			pomProperties.setContents(new ByteArrayInputStream(buf.toByteArray()), IResource.FORCE, monitor);
		} else {
			pomProperties.create(new ByteArrayInputStream(buf.toByteArray()), IResource.FORCE, monitor);
		}

		IFile pom = output.getFile("pom.xml");
		InputStream is = facade.getPom().getContents();
		try {
			if (pom.exists()) {
				pom.setContents(is, IResource.FORCE, monitor);
			} else {
				pom.create(is, IResource.FORCE, monitor);
			}
		} finally {
			IOUtil.close(is);
		}
	}
}
