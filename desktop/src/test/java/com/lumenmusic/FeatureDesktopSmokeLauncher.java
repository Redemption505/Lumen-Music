package com.lumenmusic;

import javafx.application.Application;

public final class FeatureDesktopSmokeLauncher {
    public static void main(String[] args) throws Exception {
        Application.launch(FeatureDesktopSmoke.class, CachePlaybackSmokeLauncher.createFixture().toString());
    }
}
