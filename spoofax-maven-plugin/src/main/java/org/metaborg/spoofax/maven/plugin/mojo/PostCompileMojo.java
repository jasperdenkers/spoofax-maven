package org.metaborg.spoofax.maven.plugin.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.metaborg.spoofax.maven.plugin.AbstractSpoofaxLifecycleMojo;
import org.metaborg.spoofax.maven.plugin.SpoofaxInit;

@Mojo(name = "post-compile", defaultPhase = LifecyclePhase.COMPILE)
public class PostCompileMojo extends AbstractSpoofaxLifecycleMojo {
    @Parameter(property = "spoofax.compile.skip", defaultValue = "false") private boolean skip;

    @Override public void execute() throws MojoFailureException, MojoExecutionException {
        if(skip || skipAll) {
            return;
        }
        super.execute();

        try {
            SpoofaxInit.spoofaxMeta().metaBuilder.compilePostJava(buildInput());
        } catch(Exception e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }
}
