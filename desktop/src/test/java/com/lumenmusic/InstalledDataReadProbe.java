package com.lumenmusic;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Opt-in, read-only format verification against an isolated personal backup. */
public final class InstalledDataReadProbe {
    public static void main(String[] args) throws Exception {
        Path backup = Path.of(args[0]).toRealPath();
        Path expectedJar = Path.of(args[1]).toRealPath();
        check(Files.isRegularFile(backup.resolve("inventory.json")), "Verified backup inventory required");
        check(Path.of(LumenFxApp.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath().equals(expectedJar), "Production JAR origin mismatch");
        Map<Path, String> before = inventory(backup);
        Path local = backup.resolve("local");
        Path directory = local.resolve("lyrics-selections");
        int selections = 0;
        int lines = 0;
        if (Files.isDirectory(directory)) {
            LyricsSelectionStore store = new LyricsSelectionStore(directory);
            var load = LyricsSelectionStore.class.getDeclaredMethod("loadFile", String.class, Path.class,
                    LyricsSelectionStore.Origin.class);
            load.setAccessible(true);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
                    Properties values = new Properties();
                    try (InputStream input = Files.newInputStream(file)) { values.load(input); }
                    String identity = values.getProperty("trackIdentity");
                    check(identity != null, "Legacy lyric identity missing");
                    boolean automatic = file.getFileName().toString().endsWith(".automatic.properties");
                    String suffix = automatic ? ".automatic.properties" : ".properties";
                    check(file.getFileName().toString().equals(hash(identity.getBytes(StandardCharsets.UTF_8)) + suffix),
                            "Legacy lyric filename identity mismatch");
                    var origin = automatic ? LyricsSelectionStore.Origin.AUTOMATIC : LyricsSelectionStore.Origin.MANUAL;
                    @SuppressWarnings("unchecked")
                    Optional<LyricsSelectionStore.Selection> saved = (Optional<LyricsSelectionStore.Selection>)
                            load.invoke(store, identity, file, origin);
                    check(saved.isPresent(), "Legacy lyric cache was not read");
                    var selection = saved.orElseThrow();
                    check(selection.offsetMillis() == Integer.parseInt(values.getProperty("offsetMillis"))
                            && selection.startMillis() == Long.parseLong(values.getProperty("startMillis", "0")),
                            "Legacy lyric calibration changed");
                    for (Lyrics.Line line : Lyrics.parse(selection.lrc())) {
                        check(!line.text().matches(".*<\\d{1,3}:\\d{2}(?:[.:]\\d+)?>.*"),
                                "Enhanced lyric timestamp leaked into display text");
                        lines++;
                    }
                    selections++;
                }
            }
        }
        int queueSize = 0;
        if (Files.isRegularFile(local.resolve("playback-state.xml"))) {
            var state = new PlaybackStateStore(local.resolve("playback-state.xml")).read();
            check(state.isPresent(), "Legacy playback snapshot not read");
            queueSize = state.orElseThrow().tracks().size();
        }
        Path preferences = backup.resolve("roaming/preferences.properties");
        int preferenceCount = 0;
        if (Files.isRegularFile(preferences)) {
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(preferences)) { values.load(input); }
            preferenceCount = values.size();
            check(preferenceCount > 0, "Legacy preferences are empty");
        }
        check(before.equals(inventory(backup)), "Read-only verification modified the backup");
        System.out.printf("PASS actual legacy data read-only: lyric caches=%d, parsed lines=%d, queue=%d, preference keys=%d; all backup bytes unchanged%n",
                selections, lines, queueSize, preferenceCount);
    }

    private static Map<Path, String> inventory(Path root) throws Exception {
        Map<Path, String> result = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path), hash(Files.readAllBytes(path)));
        }
        return result;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
