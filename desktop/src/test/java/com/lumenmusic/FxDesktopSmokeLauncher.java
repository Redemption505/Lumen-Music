package com.lumenmusic;

/** Avoids the JVM's special JavaFX Application main-class lookup on a classpath run. */
public final class FxDesktopSmokeLauncher {
    public static void main(String[] args) { FxDesktopSmokeTest.main(args); }
}
