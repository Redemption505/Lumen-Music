package com.lumenmusic;

import javafx.application.Application;

public final class FrontendRenderingSmokeLauncher {
    private FrontendRenderingSmokeLauncher() { }

    public static void main(String[] args) {
        Application.launch(FrontendRenderingSmoke.class, args);
        System.exit(FrontendRenderingSmoke.exitCode());
    }
}
