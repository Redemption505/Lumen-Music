package com.lumenmusic;

public final class PracticePlaybackSmokeEndpointLauncher {
    public static void main(String[] args) {
        System.setProperty("lumen.practice.smoke.edgeOnly", "true");
        javafx.application.Application.launch(PracticePlaybackSmoke.class, args);
    }
}
