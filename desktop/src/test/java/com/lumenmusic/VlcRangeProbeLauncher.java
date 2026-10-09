package com.lumenmusic;

/** Avoid the JDK's module-path JavaFX application launch check for classpath smoke runs. */
public final class VlcRangeProbeLauncher {
    public static void main(String[] args) { VlcRangeProbe.main(args); }
}
