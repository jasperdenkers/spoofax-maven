package org.metaborg.spoofax.maven.plugin.mojo.test;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.metaborg.core.MetaborgException;
import org.metaborg.core.language.ILanguageImpl;
import org.metaborg.core.language.LanguageIdentifier;
import org.metaborg.spoofax.maven.plugin.AbstractSpoofaxMojo;
import org.metaborg.spoofax.maven.plugin.SpoofaxInit;
import org.metaborg.spt.core.SPTRunner;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.resource.FileSelectorUtils;

import com.google.common.collect.Iterables;

@Mojo(name = "test", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyCollection = ResolutionScope.TEST,
    requiresDependencyResolution = ResolutionScope.TEST)
public class TestMojo extends AbstractSpoofaxMojo {
    private static final ILogger logger = LoggerUtils.logger(TestMojo.class);

    @Parameter(property = "spoofax.test.skip", defaultValue = "false") boolean skip;
    @Parameter(defaultValue = "${maven.test.skip}", readonly = true) private boolean mvnTestSkip;
    @Parameter(defaultValue = "${skipTests}", readonly = true) private boolean mvnSkipTests;
    
    @Parameter(property = "languageUnderTest", required = true) String languageUnderTest;

    @Override public void execute() throws MojoFailureException, MojoExecutionException {
        if(skip || mvnTestSkip || mvnSkipTests || skipAll) {
            return;
        }
        super.execute();

        try {
            final FileObject[] sptFiles = basedirLocation().findFiles(FileSelectorUtils.extension("spt"));
            if(sptFiles == null || sptFiles.length == 0) {
                // Skip silently
                return;
            }
        } catch(FileSystemException e) {
            throw new MojoExecutionException("Error determining files to test", e);
        }

        discoverLanguages();
        discoverSelf();

        final Iterable<? extends ILanguageImpl> sptLangs =
            SpoofaxInit.spoofax().languageService.getAllImpls("org.metaborg", "org.metaborg.meta.lang.spt");
        final int sptLangsSize = Iterables.size(sptLangs);
        if(sptLangsSize == 0) {
            logger.info(
                "Skipping tests because SPT language implementation (org.metaborg:org.metaborg.meta.lang.spt) is not a dependency");
            return;
        }
        if(sptLangsSize > 1) {
            throw new MojoExecutionException("Multiple SPT language implementations were found");
        }
        final ILanguageImpl sptLang = Iterables.get(sptLangs, 0);

        final LanguageIdentifier id = LanguageIdentifier.parseFull(languageUnderTest);
        final ILanguageImpl testLang = SpoofaxInit.spoofax().languageService.getImpl(id);
        if(testLang == null) {
            logger.info("Skipping tests because language under test was not found");
            return;
        }

        try {
            logger.info("Running SPT tests");
            SpoofaxInit.sptInjector().getInstance(SPTRunner.class).test(project(), sptLang, testLang);
        } catch(MetaborgException e) {
            throw new MojoFailureException("Error testing", e);
        }
    }
}
