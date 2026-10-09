package com.lumenmusic;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Presentation uses only the saved preference, user label and actual segment flag. */
final class VersionReading {
    private VersionReading() { }

    static boolean preferred(VersionCatalog.Song song, VersionCatalog.Version version) {
        return song != null && song.preferredVersionId() != null && !song.preferredVersionId().isBlank()
                && version != null && Objects.equals(version.id(), song.preferredVersionId());
    }

    static List<VersionCatalog.Version> ordered(VersionCatalog.Song song) {
        return song == null ? List.of() : song.versions().stream()
                .sorted(Comparator.comparing(version -> !preferred(song, version))).toList();
    }

    static String label(VersionCatalog.Version version) {
        String value = version.label() == null || version.label().isBlank() ? "未标注版本" : version.label();
        return value + (version.track() != null && version.track().isSegment() ? " · 片段" : "");
    }
}
