package com.lumenmusic;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Songs group resource versions without changing the resources themselves. */
public record VersionCatalog(List<Song> songs) {
    private static final Pattern QUOTED_TITLE = Pattern.compile("《([^》]+)》");
    private static final Pattern BRACKETED = Pattern.compile("\\(([^)]*)\\)|\\[([^]]*)\\]|【([^】]*)】");
    private static final Pattern VERSION_LABEL = Pattern.compile(
            "(?i)(?:live(?:\\s+(?:ver\\.?|version))?|现场(?:版)?|cover(?:\\s+(?:ver\\.?|version))?|"
                    + "翻唱(?:版)?|纯音乐|instrumental|studio(?:\\s+(?:ver\\.?|version))?|"
                    + "录音室(?:版)?|hi-?res|无损)");
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "(?i)\\s*[-|·]\\s*(?:live|现场版?|cover|翻唱版?|纯音乐|instrumental|"
                    + "studio(?:\\s+version)?|录音室版?|hi-?res|无损)\\s*$");

    public VersionCatalog {
        songs = List.copyOf(songs);
    }

    public record Version(String id, String label, String performer, Track track) {
        public Version(String label, String performer, Track track) {
            this(null, label, performer, track);
        }
    }

    public record Song(String id, String title, String note, String preferredVersionId,
                       List<Version> versions) {
        public Song {
            versions = List.copyOf(versions);
        }

        public Optional<Version> preferredVersion() {
            return versions.stream().filter(v -> Objects.equals(v.id(), preferredVersionId)).findFirst()
                    .or(() -> versions.stream().findFirst());
        }

        public Optional<Track> preferredTrack() {
            return preferredVersion().map(Version::track);
        }
    }

    public Optional<Song> findByResourceId(String resourceId) {
        return songs.stream().filter(song -> song.versions().stream()
                .anyMatch(version -> version.track() != null
                        && Objects.equals(version.track().id(), resourceId))).findFirst();
    }

    public Optional<Version> versionByResourceId(String resourceId) {
        return songs.stream().flatMap(song -> song.versions().stream())
                .filter(version -> version.track() != null
                        && Objects.equals(version.track().id(), resourceId)).findFirst();
    }

    public Track preferredTrack(Track resource) {
        if (resource == null) return null;
        return findByResourceId(resource.id()).flatMap(Song::preferredTrack).orElse(resource);
    }

    public static boolean related(Track first, Track second) {
        if (first == null || second == null) return false;
        String title = normalizedTitle(first.title());
        return !title.isEmpty() && title.equals(normalizedTitle(second.title()));
    }

    /** Suggestions are never applied automatically; performer and version labels stay distinct. */
    public List<Song> suggestionsFor(Track track) {
        if (track == null) return List.of();
        String title = normalizedTitle(track.title());
        if (title.isEmpty()) return List.of();
        return songs.stream().filter(song -> title.equals(normalizedTitle(song.title())))
                .toList();
    }

    static String normalizedTitle(String value) {
        if (value == null) return "";
        String title = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        Matcher quoted = QUOTED_TITLE.matcher(title);
        if (quoted.find()) title = quoted.group(1);
        Matcher bracketed = BRACKETED.matcher(title);
        StringBuilder cleaned = new StringBuilder();
        while (bracketed.find()) {
            String label = bracketed.group(1) != null ? bracketed.group(1)
                    : bracketed.group(2) != null ? bracketed.group(2) : bracketed.group(3);
            if (VERSION_LABEL.matcher(label.strip()).matches()) bracketed.appendReplacement(cleaned, "");
            else bracketed.appendReplacement(cleaned, Matcher.quoteReplacement(bracketed.group()));
        }
        bracketed.appendTail(cleaned);
        title = VERSION_SUFFIX.matcher(cleaned.toString()).replaceFirst("");
        return title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
