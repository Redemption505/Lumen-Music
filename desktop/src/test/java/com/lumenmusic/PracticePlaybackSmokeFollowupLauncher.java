package com.lumenmusic;

public final class PracticePlaybackSmokeFollowupLauncher {
    public static void main(String[] args) {
        System.setProperty("lumen.practice.smoke.followupOnly", "true");
        javafx.application.Application.launch(PracticePlaybackSmoke.class, args);
    }
}
