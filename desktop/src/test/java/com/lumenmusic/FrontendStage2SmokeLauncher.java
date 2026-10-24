package com.lumenmusic;

/** Runs the shared isolated visual fixture's stage-two interaction checks. */
public final class FrontendStage2SmokeLauncher {
    private FrontendStage2SmokeLauncher() { }
    public static void main(String[] args) {
        javafx.application.Application.launch(VisualLanguageSmoke.class, "--stage2-only");
    }
}
