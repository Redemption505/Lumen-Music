package com.lumenmusic;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** The packaged JAR, settings and update manifest share these build properties. */
public record BuildInfo(String version, String buildId) {
    private static final BuildInfo CURRENT = read();

    public BuildInfo {
        if (version == null || !version.matches("(?:[0-9]+\\.[0-9]+\\.[0-9]+|development)"))
            throw new IllegalArgumentException("Invalid application version");
        if (buildId == null || !buildId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid build identifier");
    }

    public static BuildInfo current() { return CURRENT; }

    public static String releaseNotes() {
        try (var input = BuildInfo.class.getResourceAsStream("/release-notes.txt")) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException error) { return ""; }
    }

    private static BuildInfo read() {
        try (var input = BuildInfo.class.getResourceAsStream("/lumen-build.properties")) {
            if (input != null) {
                Properties values = new Properties();
                values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
                return new BuildInfo(values.getProperty("version"), values.getProperty("build.id"));
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("Application build metadata is unavailable");
        }
        return new BuildInfo("development", "unpackaged");
    }
}
