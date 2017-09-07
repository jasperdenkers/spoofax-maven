package org.metaborg.spoofax.maven.plugin.mojo.language.manual;

import java.io.IOException;
import java.util.Collection;

import javax.annotation.Nullable;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.metaborg.core.language.LanguageVersion;
import org.metaborg.core.project.ProjectException;
import org.metaborg.spoofax.maven.plugin.AbstractSpoofaxMojo;
import org.metaborg.spoofax.maven.plugin.SpoofaxInit;
import org.metaborg.spoofax.meta.core.config.SdfVersion;
import org.metaborg.spoofax.meta.core.generator.general.AnalysisType;
import org.metaborg.spoofax.meta.core.generator.general.ContinuousLanguageSpecGenerator;
import org.metaborg.spoofax.meta.core.generator.general.LangSpecGenerator;
import org.metaborg.spoofax.meta.core.generator.general.LangSpecGeneratorSettings;
import org.metaborg.spoofax.meta.core.generator.general.LangSpecGeneratorSettingsBuilder;
import org.metaborg.spoofax.meta.core.generator.general.SyntaxType;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.prompt.Prompter;

import com.google.common.base.Joiner;

@Mojo(name = "generate", requiresDirectInvocation = true, requiresProject = false)
public class GenerateProjectMojo extends AbstractSpoofaxMojo {
    private static final ILogger logger = LoggerUtils.logger(GenerateProjectMojo.class);

    @Parameter(defaultValue = "${groupId}", required = false) private String groupId;
    @Parameter(defaultValue = "${id}", required = false) private String id;
    @Parameter(defaultValue = "${version}", required = false) private String version;
    @Parameter(defaultValue = "${name}", required = false) private String name;
    @Parameter(defaultValue = "${plugin.version}", required = false) private String metaborgVersion;
    @Parameter(defaultValue = "${extension}", required = false) private Collection<String> extensions;
    @Parameter(defaultValue = "${syntaxType}", required = false) private SyntaxType syntaxType;
    @Parameter(defaultValue = "${analysisType}", required = false) private AnalysisType analysisType;


    @Override public void execute() throws MojoFailureException, MojoExecutionException {
        super.execute();

        final MavenProject project = mavenProject();

        if(project != null && project.getFile() != null) {
            final String message = logger.format("Found existing project {}, not continuing", project.getName());
            throw new MojoFailureException(message);
        }

        // @formatter:off
        final LangSpecGeneratorSettingsBuilder settingsBuilder = new LangSpecGeneratorSettingsBuilder()
            .withGroupId(groupId)
            .withId(id)
            .withVersion((version != null && LanguageVersion.valid(version)) ? LanguageVersion.parse(version) : null)
            .withName(name)
            .withMetaborgVersion(metaborgVersion)
            .withExtensions(extensions)
            .withSyntaxType(syntaxType)
            .withAnalysisType(analysisType)
            ;
        // @formatter:on

        if(!settingsBuilder.isComplete()) {
            Prompter prompter;
            try {
                prompter = Prompter.get();
            } catch(IOException e) {
                throw new MojoFailureException("Must run interactively", e);
            }
            settingsBuilder.configureFromPrompt(prompter);
        }

        generate(settingsBuilder);
    }


    private void generate(LangSpecGeneratorSettingsBuilder settingsBuilder) throws MojoFailureException {
        if(settingsBuilder.canBuild()) {
            try {
                final LangSpecGeneratorSettings settings =
                    settingsBuilder.build(basedirLocation(), SpoofaxInit.spoofaxMeta().languageSpecConfigBuilder());

                final LangSpecGenerator newGenerator = new LangSpecGenerator(settings);
                newGenerator.generateAll();
                final @Nullable SdfVersion version;
                final boolean enabled;
                if(settings.syntaxType == SyntaxType.SDF2) {
                    version = SdfVersion.sdf2;
                    enabled = true;
                } else if(settings.syntaxType == SyntaxType.SDF3) {
                    version = SdfVersion.sdf3;
                    enabled = true;
                } else {
                    version = null;
                    enabled = false;
                }
                final ContinuousLanguageSpecGenerator generator =
                    new ContinuousLanguageSpecGenerator(settings.generatorSettings, enabled, version);
                generator.generateAll();
            } catch(IOException ex) {
                throw new MojoFailureException("Failed to generate project files", ex);
            } catch(ProjectException ex) {
                throw new MojoFailureException("Invalid project settings", ex);
            }
        } else {
            throw new MojoFailureException("Missing required " + Joiner.on(", ").join(settingsBuilder.stillRequired()));
        }
    }
}
