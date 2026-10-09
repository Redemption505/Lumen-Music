package com.lumenmusic;

public final class Phase4CoreSmokeLauncher {
    private Phase4CoreSmokeLauncher() { }
    public static void main(String[] args) {
        try {
            if (java.util.List.of("prepared", "ready").contains(System.getenv().getOrDefault("LUMEN_PHASE4_SEEK_WINDOW", ""))) {
                java.nio.file.Path fixture = CachePlaybackSmokeLauncher.createFixture();
                javafx.application.Application.launch(Phase4CoreSmoke.class, fixture.toString());
            } else javafx.application.Application.launch(Phase4CoreSmoke.class, args);
        } catch (Exception error) { throw new IllegalStateException("Isolated audio fixture failed", error); }
    }
}
