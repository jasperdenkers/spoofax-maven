package org.metaborg.spoofax.maven.plugin;

import java.io.File;
import java.util.List;
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
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor;
import org.metaborg.core.MetaborgException;
import org.metaborg.core.build.dependency.IDependencyService;
import org.metaborg.core.build.paths.ILanguagePathService;
import org.metaborg.core.language.ILanguageComponent;
import org.metaborg.core.language.ILanguageDiscoveryService;
import org.metaborg.core.language.ILanguageService;
import org.metaborg.core.language.LanguageIdentifier;
import org.metaborg.core.language.LanguageVersion;
import org.metaborg.core.language.dialect.IDialectProcessor;
import org.metaborg.core.processing.IProcessorRunner;
import org.metaborg.core.project.IProject;
import org.metaborg.core.project.ISimpleProjectService;
import org.metaborg.core.resource.IResourceService;
import org.metaborg.core.resource.ResourceChange;
import org.metaborg.core.resource.ResourceChangeKind;
import org.metaborg.core.resource.ResourceUtils;
import org.metaborg.core.source.ISourceTextService;
import org.metaborg.spoofax.core.Spoofax;
import org.metaborg.spoofax.core.project.ISimpleMavenProjectService;
import org.metaborg.spoofax.core.project.SpoofaxMavenConstants;
import org.metaborg.spoofax.core.project.settings.ISpoofaxProjectSettingsService;
import org.metaborg.spoofax.core.resource.SpoofaxIgnoresSelector;
import org.metaborg.spoofax.core.stratego.IStrategoRuntimeService;
import org.metaborg.spoofax.maven.plugin.impl.MavenSpoofaxModule;
import org.metaborg.spoofax.meta.core.SpoofaxMeta;
import org.metaborg.spoofax.meta.core.SpoofaxMetaBuilder;
import org.metaborg.spoofax.meta.core.SpoofaxMetaModule;
import org.metaborg.util.iterators.Iterables2;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.inject.Injector;

public abstract class AbstractSpoofaxMojo extends AbstractMojo {
    private static final String PROJECT_ID = "spoofax-maven-plugin.project";
    private static final String DISCOVERED_ID = "spoofax-maven-plugin.discovered";

    protected static Injector spoofaxInjector;

    protected static IResourceService resourceService;
    protected static ILanguageService languageService;
    protected static ILanguageDiscoveryService languageDiscoveryService;
    protected static ILanguagePathService languagePathService;
    protected static IDialectProcessor dialectProcessor;
    protected static IDependencyService dependencyService;
    protected static ISimpleProjectService projectService;
    protected static ISimpleMavenProjectService mavenProjectService;
    protected static ISpoofaxProjectSettingsService projectSettingsService;
    protected static ISourceTextService sourceTextService;
    protected static IStrategoRuntimeService strategoRuntimeService;
    protected static SpoofaxMetaBuilder metaBuilder;
    protected static IProcessorRunner<?, ?, ?> processorRunner;

    @Component(hint = "default") private DependencyTreeBuilder dependencyTreeBuilder;
    @Component private RepositorySystem repoSystem;
    @Component private ProjectDependenciesResolver projectDependenciesResolver;

    @Parameter(defaultValue = "${basedir}", readonly = true, required = true) private File basedir;
    @Parameter(defaultValue = "${project}", readonly = true) private MavenProject project;
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true) private PluginDescriptor plugin;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true) private File buildDirectory;
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true) private File javaOutputDirectory;

    @Parameter(defaultValue = "${localRepository}", readonly = true) private ArtifactRepository localRepository;
    @Parameter(defaultValue = "${project.remoteProjectRepositories}") private List<ArtifactRepository> projectRepos;
    @Parameter(defaultValue = "${project.remotePluginRepositories}") private List<ArtifactRepository> pluginRepos;

    @Parameter(property = "spoofax.skip", defaultValue = "false") protected boolean skipAll;

    private FileObject basedirLocation;
    private IProject metaborgProject;


    private static boolean shouldInit() {
        return spoofaxInjector == null;
    }

    private static void init() throws MetaborgException {
        if(spoofaxInjector == null) {
            final Spoofax spoofax = new Spoofax(new MavenSpoofaxModule());
            final SpoofaxMeta spoofaxMeta = new SpoofaxMeta(spoofax, new SpoofaxMetaModule());
            spoofaxInjector = spoofaxMeta.injector();

            resourceService = spoofaxInjector.getInstance(IResourceService.class);
            languageService = spoofaxInjector.getInstance(ILanguageService.class);
            languageDiscoveryService = spoofaxInjector.getInstance(ILanguageDiscoveryService.class);
            languagePathService = spoofaxInjector.getInstance(ILanguagePathService.class);
            dependencyService = spoofaxInjector.getInstance(IDependencyService.class);
            projectService = spoofaxInjector.getInstance(ISimpleProjectService.class);
            mavenProjectService = spoofaxInjector.getInstance(ISimpleMavenProjectService.class);
            projectSettingsService = spoofaxInjector.getInstance(ISpoofaxProjectSettingsService.class);
            sourceTextService = spoofaxInjector.getInstance(ISourceTextService.class);
            strategoRuntimeService = spoofaxInjector.getInstance(IStrategoRuntimeService.class);
            metaBuilder = spoofaxInjector.getInstance(SpoofaxMetaBuilder.class);
            processorRunner = spoofaxInjector.getInstance(IProcessorRunner.class);
            
        }
    }

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
        if(shouldInit()) {
            getLog().info("Initialising Spoofax core");
            try {
                init();
            } catch(MetaborgException e) {
                throw new MojoExecutionException("Cannot instantiate Spoofax", e);
            }
        }

        basedirLocation = resourceService.resolve(basedir);
        if(!getContextBool(project, PROJECT_ID)) {
            try {
                metaborgProject = projectService.create(basedirLocation);
            } catch(MetaborgException e) {
                throw new MojoExecutionException("Cannot create Metaborg project", e);
            }

            try {
                mavenProjectService.add(metaborgProject, project);
            } catch(MetaborgException e) {
                throw new MojoExecutionException("Cannot create Maven project", e);
            }

            setContextBool(project, PROJECT_ID, true);
        } else {
            metaborgProject = projectService.get(basedirLocation);
        }
    }


    public @Nullable MavenProject getProject() {
        return project;
    }

    public PluginDescriptor getPlugin() {
        return plugin;
    }


    public @Nullable File getBasedir() {
        return basedir;
    }

    public @Nullable FileObject getBasedirLocation() {
        return basedirLocation;
    }

    public @Nullable IProject getMetaborgProject() {
        return metaborgProject;
    }


    public @Nullable File getBuildDirectory() {
        return getAbsoluteFile(buildDirectory);
    }

    public @Nullable File getJavaOutputDirectory() {
        return getAbsoluteFile(javaOutputDirectory);
    }

    public @Nullable File getAbsoluteFile(@Nullable File file) {
        if(file == null) {
            return basedir;
        }
        return file.isAbsolute() ? file : new File(basedir, file.getPath());
    }

    public @Nullable File getAbsoluteFile(@Nullable String path) {
        if(path == null) {
            return basedir;
        }
        return getAbsoluteFile(new File(path));
    }


    public void discoverLanguages() throws MojoExecutionException {
        if(project == null) {
            throw new MojoExecutionException("Cannot discover languages without a project");
        }

        if(getContextBool(project, DISCOVERED_ID)) {
            return;
        }

        getLog().info("Collecting language dependencies");

        final Iterable<Artifact> dependencies;
        try {
            final Iterable<Artifact> allDependencies = allDependencies();
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
            final FileObject location = metaborgProject.location();
            final Iterable<FileObject> resources = ResourceUtils.find(location, new SpoofaxIgnoresSelector());
            final Iterable<ResourceChange> creations = ResourceUtils.toChanges(resources, ResourceChangeKind.Create);
            processorRunner.updateDialects(location, creations).schedule().block();
        } catch(FileSystemException | InterruptedException e) {
            throw new MojoExecutionException("Error(s) occurred while loading dialects");
        }

        setContextBool(project, DISCOVERED_ID, true);
    }

    /**
     * Get the dependency tree so that we also see dependencies that have been omitted by Maven. Maven does conflict
     * resolution so that it only has to load a single version of the artifact in the JVM, which makes sense for Java,
     * but not for Spoofax. We actually want to load multiple versions of the same language for bootstrapping purposes.
     */
    private Iterable<Artifact> allDependencies() throws DependencyTreeBuilderException {
        final Set<Artifact> dependencies = Sets.newHashSet();
        final DependencyNode node =
            dependencyTreeBuilder.buildDependencyTree(project, localRepository, new ArtifactFilter() {
                @Override public boolean include(Artifact artifact) {
                    return true;
                }
            });
        node.accept(new DependencyNodeVisitor() {
            @Override public boolean visit(DependencyNode node) {
                final Artifact artifact = node.getArtifact();
                if(artifact.getType().equalsIgnoreCase(SpoofaxMavenConstants.PACKAGING_TYPE)) {
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
     * @return Loaded components, or null if an error occured.
     */
    private Iterable<ILanguageComponent> loadComponents(Artifact artifact) {
        final LanguageVersion version = LanguageVersion.parse(artifact.getVersion());
        final LanguageIdentifier identifier =
            new LanguageIdentifier(artifact.getGroupId(), artifact.getArtifactId(), version);
        final ILanguageComponent existingComponent = languageService.getComponent(identifier);
        if(existingComponent != null) {
            return Iterables2.empty();
        }

        final File file = artifact.getFile();
        if(file != null && file.exists()) {
            final String url = (file.isDirectory() ? "file:" : "zip:") + file.getPath();
            final FileObject artifactLocation = resourceService.resolve(url);

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
                final FileObject packageFile = resourceService.resolve("zip:" + packageLocation.getName().getPath());

                final Iterable<ILanguageComponent> components;
                if(packageFile.exists()) {
                    components = languageDiscoveryService.discover(packageFile);
                } else {
                    components = languageDiscoveryService.discover(artifactLocation);
                }

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
}
