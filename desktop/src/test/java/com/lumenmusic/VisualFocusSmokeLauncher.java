package com.lumenmusic;

/** Targeted reproduction; shares the actual controls, playback and assertions of the full matrix. */
public final class VisualFocusSmokeLauncher {
    private VisualFocusSmokeLauncher() { }
    public static void main(String[] args) {
        javafx.application.Application.launch(VisualLanguageSmoke.class, "--focus-only");
    }
}
