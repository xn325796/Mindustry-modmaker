package dev.modmaker;

import dev.modmaker.cli.ModMakerCli;

/**
 * JVM entry point.
 *
 * <p>The main class deliberately does not extend Application: the JDK launcher only demands the
 * javafx.graphics module when the class named on the command line is an Application subclass.
 * Going through this class keeps JavaFX working with plain classpath jars.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        // Headless commands must not start the toolkit: the classpath-only setup would then demand
        // the javafx.graphics module for a CI run that never opens a window.
        if (ModMakerCli.handles(args)) {
            System.exit(ModMakerCli.run(args));
        }
        App.main(args);
    }
}
