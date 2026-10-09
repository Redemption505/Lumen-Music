package com.lumenmusic;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Blocking filesystem work. Call from the application's import worker, not its UI thread. */
final class LocalAudioImporter {
    private static final Set<String> EXTENSIONS = Set.of("mp3", "flac", "m4a", "mp4", "wav", "aiff", "aif", "ogg", "opus");
    record Imported(Track track, String lyrics) { }
    record Result(List<Imported> imported, int duplicates, int failures) { }

    static boolean supports(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static Result scan(Collection<Path> selections, Collection<Track> existing, Path dataDirectory) {
        Set<String> known = new HashSet<>();
        for (Track track : existing) {
            URI uri = track.mediaUri();
            if (!track.isSegment() && "local".equals(track.source()) && uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                try { known.add(key(Path.of(uri))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        List<Path> candidates = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        int[] failures = {0};
        for (Path selection : selections) {
            if (selection == null) continue;
            Path absolute = selection.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)) continue;
            if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (attributes.isRegularFile() && !attributes.isSymbolicLink() && supports(file)) candidates.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
                            failures[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
                            if (exception != null) failures[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException | SecurityException exception) { failures[0]++; }
            } else if (Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) && supports(absolute)) candidates.add(absolute);
            else if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) failures[0]++;
        }
        candidates.sort(Comparator.comparing(LocalAudioImporter::key).thenComparing(Path::toString));
        List<Imported> imported = new ArrayList<>();
        int duplicates = 0;
        for (Path candidate : candidates) {
            String key = key(candidate);
            if (!selected.add(key)) continue; // overlapping folders and explicit file selections count once
            if (!known.add(key)) { duplicates++; continue; }
            try {
                LocalAudioMetadata.Metadata metadata = LocalAudioMetadata.read(candidate, dataDirectory);
                String filename = candidate.getFileName().toString();
                String title = metadata.title().isBlank() ? filename.substring(0, filename.lastIndexOf('.')) : metadata.title();
                String artist = metadata.artist().isBlank() ? "本地文件" : metadata.artist();
                URI uri = candidate.toAbsolutePath().normalize().toUri();
                Track track = new Track(uri.toString(), title, artist, metadata.durationSeconds(), "local", uri, null, 0, metadata.coverUri());
                imported.add(new Imported(track, metadata.lyrics()));
            } catch (IOException | SecurityException exception) { failures[0]++; }
        }
        return new Result(List.copyOf(imported), duplicates, failures[0]);
    }

    private static String key(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? key.toLowerCase(Locale.ROOT) : key;
    }
}
