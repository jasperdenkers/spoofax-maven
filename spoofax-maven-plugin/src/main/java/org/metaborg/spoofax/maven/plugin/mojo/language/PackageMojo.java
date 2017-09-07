package org.metaborg.spoofax.maven.plugin.mojo.language;

import java.io.File;

import org.apache.commons.vfs2.FileObject;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.metaborg.core.MetaborgException;
import org.metaborg.core.language.ILanguageComponent;
import org.metaborg.core.language.ILanguageDiscoveryRequest;
import org.metaborg.spoofax.maven.plugin.SpoofaxInit;
import org.metaborg.spoofax.meta.core.build.SpoofaxLangSpecCommonPaths;

import com.google.common.collect.Iterables;

@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
public class PackageMojo extends AbstractSpoofaxLanguageMojo {
    @Component(role = Archiver.class, hint = "zip") private ZipArchiver zipArchiver;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true) private File buildDirectory;
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true) private File javaOutputDirectory;

    @Parameter(defaultValue = "${project.build.finalName}") private String finalName;
    @Parameter(property = "spoofax.package.skip", defaultValue = "false") private boolean skip;


    @Override public void execute() throws MojoFailureException, MojoExecutionException {
        super.execute();

        final SpoofaxLangSpecCommonPaths paths = new SpoofaxLangSpecCommonPaths(basedirLocation());
        final FileObject spxArchiveFile = paths.spxArchiveFile(languageSpec().config().identifier().toFileString());
        final File localSpxArchiveFile = SpoofaxInit.spoofax().resourceService.localFile(spxArchiveFile);
        mavenProject().getArtifact().setFile(localSpxArchiveFile);

        if(skip || skipAll) {
            return;
        }

        getLog().info("Packaging Spoofax language");

        try {
            SpoofaxInit.spoofaxMeta().metaBuilder.pkg(buildInput());
            SpoofaxInit.spoofaxMeta().metaBuilder.archive(buildInput());
        } catch(Exception e) {
            throw new MojoFailureException(e.getMessage(), e);
        }

        // Resolve to contents of the archive (zip) file, such that discovery looks inside the zip file.
        final FileObject zipSpxArchiveFile =
            SpoofaxInit.spoofax().resourceService.resolve("zip:" + spxArchiveFile.getName().getURI() + "!/");
        getLog().info("Reloading language from: " + zipSpxArchiveFile);
        try {
            final Iterable<ILanguageDiscoveryRequest> request =
                SpoofaxInit.spoofax().languageDiscoveryService.request(zipSpxArchiveFile);
            final Iterable<ILanguageComponent> components =
                SpoofaxInit.spoofax().languageDiscoveryService.discover(request);
            if(Iterables.isEmpty(components)) {
                throw new MojoExecutionException("Failed to reload language, no components were discovered");
            }
        } catch(MetaborgException e) {
            throw new MojoExecutionException("Failed to reload language", e);
        }
    }
}
