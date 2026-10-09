package com.lumenmusic;

/** Avoids the JVM's special JavaFX main-class lookup for classpath execution. */
public final class WindowsTaskbarSmokeLauncher {
    public static void main(String[] args) { WindowsTaskbarSmokeTest.main(args); }
}
