package org.metaborg.spoofax.maven.plugin;

import java.io.File;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.metaborg.core.MetaborgException;
import org.metaborg.core.language.ILanguageComponent;
import org.metaborg.core.language.ILanguageDiscoveryRequest;
import org.metaborg.core.language.LanguageIdentifier;
import org.metaborg.core.language.LanguageVersion;
import org.metaborg.core.project.IProject;
import org.metaborg.core.resource.ResourceChange;
import org.metaborg.core.resource.ResourceChangeKind;
import org.metaborg.core.resource.ResourceUtils;
import org.metaborg.spoofax.core.resource.SpoofaxIgnoresSelector;
import org.metaborg.util.iterators.Iterables2;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public abstract class AbstractSpoofaxMojo extends AbstractMojo {
    private static final String PROJECT_ID = "spoofax-maven-plugin.project";
    private static final String DISCOVERED_ID = "spoofax-maven-plugin.discovered";

    @Component(hint = "default") private DependencyTreeBuilder dependencyTreeBuilder;
    @Component private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${basedir}", readonly = true, required = true) private File basedir;
    @Parameter(defaultValue = "${project}", readonly = true) private MavenProject project;
    @Parameter(defaultValue = "${localRepository}", readonly = true) private ArtifactRepository localRepository;

    @Parameter(property = "spoofax.skip", defaultValue = "false") protected boolean skipAll;

    private FileObject basedirLocation;
    private @Nullable IProject metaborgProject;


    private static boolean getContextBool(MavenProject project, String id) throws MojoExecutionException {
        if(project == null) {
            throw new MojoExecutionException("Cannot get context value without a project");
        }

        final Boolean bool = (Boolean) project.getContextValue(id);
        if(bool != null && bool) {
            return true;
        }
        return false;
    }

    private static void setContextBool(MavenProject project, String id, boolean value) throws MojoExecutionException {
        if(project == null) {
            throw new MojoExecutionException("Cannot set context value without a project");
        }

        project.setContextValue(id, value);
    }

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        if(SpoofaxInit.shouldInit()) {
            getLog().info("Initialising Spoofax core");
            try {
                SpoofaxInit.init();
            } catch(MetaborgException e) {
                throw new MojoExecutionException("Cannot instantiate Spoofax", e);
            }
        }

        basedirLocation = SpoofaxInit.spoofax().resourceService.resolve(basedir);
        if(!getContextBool(project, PROJECT_ID)) {
            try {
                metaborgProject = SpoofaxInit.projectService().create(basedirLocation);
            } catch(MetaborgException e) {
                throw new MojoExecutionException("Cannot create Metaborg project", e);
            }

            setContextBool(project, PROJECT_ID, true);
        } else {
            metaborgProject = SpoofaxInit.projectService().get(basedirLocation);
        }
    }


    public File basedir() {
        return basedir;
    }

    public FileObject basedirLocation() {
        return basedirLocation;
    }


    public @Nullable File absoluteFile(@Nullable File file) {
        if(file == null) {
            return basedir;
        }
        return file.isAbsolute() ? file : new File(basedir, file.getPath());
    }

    public @Nullable File absoluteFile(@Nullable String path) {
        if(path == null) {
            return basedir;
        }
        return absoluteFile(new File(path));
    }


    public @Nullable MavenProject mavenProject() {
        return project;
    }

    public @Nullable IProject project() {
        return metaborgProject;
    }


    public void discoverLanguages() throws MojoExecutionException {
        discoverLanguages(Sets.<String>newHashSet());
    }

    public void discoverLanguages(Set<String> scopes) throws MojoExecutionException {
        if(project == null) {
            throw new MojoExecutionException("Cannot discover languages without a project");
        }

        if(getContextBool(project, DISCOVERED_ID)) {
            return;
        }

        getLog().info("Collecting language dependencies");

        final Iterable<Artifact> dependencies;
        try {
            final Iterable<Artifact> allDependencies = allDependencies(scopes);
            dependencies = resolveArtifacts(allDependencies);
        } catch(DependencyTreeBuilderException e) {
            throw new MojoExecutionException("Resolving dependencies failed", e);
        }

        getLog().info("Loading language components from dependencies");

        boolean error = false;
        for(Artifact dependency : dependencies) {
            if(loadComponents(dependency) == null) {
                error = true;
            }
        }

        if(error) {
            throw new MojoExecutionException("Error(s) occurred while discovering languages");
        }

        getLog().info("Loading dialects");

        try {
            final Iterable<FileObject> resources = ResourceUtils.find(basedirLocation, new SpoofaxIgnoresSelector());
            final Iterable<ResourceChange> creations = ResourceUtils.toChanges(resources, ResourceChangeKind.Create);
            SpoofaxInit.spoofax().processorRunner.updateDialects(basedirLocation, creations).schedule().block();
        } catch(FileSystemException | InterruptedException e) {
            throw new MojoExecutionException("Error(s) occurred while loading dialects");
        }

        setContextBool(project, DISCOVERED_ID, true);
    }

    public void discoverSelf() {
        if(!project.getPackaging().equals(Constants.languageSpecType)) {
            return;
        }
        loadComponents(project.getBasedir());
    }
    
    /**
     * Get the dependency tree so that we also see dependencies that have been omitted by Maven. Maven does conflict
     * resolution so that it only has to load a single version of the artifact in the JVM, which makes sense for Java,
     * but not for Spoofax. We actually want to load multiple versions of the same language for bootstrapping purposes.
     */
    private Iterable<Artifact> allDependencies(final Set<String> scopes) throws DependencyTreeBuilderException {
        final Set<Artifact> dependencies = Sets.newHashSet();
        final DependencyNode node =
            dependencyTreeBuilder.buildDependencyTree(project, localRepository, new ArtifactFilter() {
                @Override public boolean include(Artifact artifact) {
                    if(!scopes.isEmpty()) {
                        return scopes.contains(artifact.getScope());
                    }
                    return true;
                }
            });
        node.accept(new DependencyNodeVisitor() {
            @Override public boolean visit(DependencyNode node) {
                final Artifact artifact = node.getArtifact();
                if(artifact.getType().equalsIgnoreCase(Constants.languageSpecType)) {
                    dependencies.add(artifact);
                }
                return true;
            }

            @Override public boolean endVisit(DependencyNode node) {
                return true;
            }
        });
        dependencies.remove(project.getArtifact());
        return dependencies;
    }

    /**
     * Omitted dependencies in the dependency tree are not resolved. Resolve them manually and return the resolved
     * artifacts.
     */
    private Iterable<Artifact> resolveArtifacts(Iterable<Artifact> dependencies) {
        final Set<Artifact> artifacts = Sets.newHashSet();
        for(Artifact dependency : dependencies) {
            if(dependency.isResolved()) {
                artifacts.add(dependency);
            } else {
                final ArtifactResolutionRequest request = new ArtifactResolutionRequest();
                request.setArtifact(dependency);
                // HACK: setting remote repositories causes ClassCastException in Maven, disable for now..
                // request.setRemoteRepositories(projectRepos);
                request.setLocalRepository(localRepository);
                final ArtifactResolutionResult result = repoSystem.resolve(request);
                artifacts.addAll(result.getArtifacts());
            }
        }
        return artifacts;
    }

    /**
     * Loads language components given an artifact.
     * 
     * @param artifact
     *            Artifact to load language components from.
     * @return Loaded components, or null if an error occurred.
     */
    private Iterable<ILanguageComponent> loadComponents(Artifact artifact) {
        final LanguageVersion version = LanguageVersion.parse(artifact.getBaseVersion());
        final LanguageIdentifier identifier =
            new LanguageIdentifier(artifact.getGroupId(), artifact.getArtifactId(), version);
        final ILanguageComponent existingComponent = SpoofaxInit.spoofax().languageService.getComponent(identifier);
        if(existingComponent != null) {
            return Iterables2.empty();
        }

        final File file = artifact.getFile();
        if(file != null && file.exists()) {
            final String url = (file.isDirectory() ? "file:" : "zip:") + file.getPath();
            final FileObject artifactLocation = SpoofaxInit.spoofax().resourceService.resolve(url);

            try {
                if(!artifactLocation.exists()) {
                    getLog().error("Artifact location" + artifactLocation + " does not exist, cannot load languages");
                    return null;
                }

                // When running in Eclipse using M2E, artifact location will point to the target/classes/
                // directory which is empty. Try again with the packaged artifact.
                final FileObject targetLocation = artifactLocation.getParent();
                final String filename =
                    artifact.getArtifactId() + "-" + artifact.getBaseVersion() + "." + artifact.getType();
                final FileObject packageLocation = targetLocation.resolveFile(filename);
                // NOTE: FileObject.getName().getPath() will not include the drive letter on Windows.
                // To fix this, use FileObject.getURL().getPath() instead.
                final FileObject packageFile =
                    SpoofaxInit.spoofax().resourceService.resolve("zip:" + packageLocation.getURL().getPath());

                final Iterable<ILanguageDiscoveryRequest> requests;
                if(packageFile.exists()) {
                    requests = SpoofaxInit.spoofax().languageDiscoveryService.request(packageFile);
                } else {
                    requests = SpoofaxInit.spoofax().languageDiscoveryService.request(artifactLocation);
                }
                final Iterable<ILanguageComponent> components =
                    SpoofaxInit.spoofax().languageDiscoveryService.discover(requests);

                if(Iterables.isEmpty(components)) {
                    getLog().error("No languages were discovered in " + artifact);
                    return null;
                }

                for(ILanguageComponent component : components) {
                    getLog().info("Loaded " + component);
                }

                return components;
            } catch(FileSystemException | MetaborgException e) {
                getLog().error("Unexpected error while discovering languages in " + artifact, e);
                return null;
            }
        }

        getLog().error("Artifact " + artifact + " has no files, cannot load languages");
        return null;
    }

    private Iterable<ILanguageComponent> loadComponents(File file) {
        if(file != null && file.exists()) {
            final String url = (file.isDirectory() ? "file:" : "zip:") + file.getPath();
            final FileObject location = SpoofaxInit.spoofax().resourceService.resolve(url);

            try {
                if(!location.exists()) {
                    getLog().error("Artifact location" + location + " does not exist, cannot load languages");
                    return null;
                }

                final Iterable<ILanguageDiscoveryRequest> requests =
                    SpoofaxInit.spoofax().languageDiscoveryService.request(location);
                final Iterable<ILanguageComponent> components =
                    SpoofaxInit.spoofax().languageDiscoveryService.discover(requests);

                if(Iterables.isEmpty(components)) {
                    getLog().error("No languages were discovered at " + location);
                    return null;
                }

                for(ILanguageComponent component : components) {
                    getLog().info("Loaded " + component);
                }

                return components;
            } catch(FileSystemException | MetaborgException e) {
                getLog().error("Unexpected error while discovering languages at " + location, e);
                return null;
            }
        }
        return null;
    }
}
