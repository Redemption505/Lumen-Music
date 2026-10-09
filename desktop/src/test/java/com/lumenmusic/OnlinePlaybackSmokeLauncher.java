package com.lumenmusic;

/** Classpath launcher for the opt-in installed JavaFX online acceptance. */
public final class OnlinePlaybackSmokeLauncher {
    private OnlinePlaybackSmokeLauncher() { }
    public static void main(String[] args) {
        System.out.println("OnlinePlaybackSmokeLauncher: Java " + System.getProperty("java.version"));
        System.out.flush();
        OnlinePlaybackSmoke.main(args);
    }
}
