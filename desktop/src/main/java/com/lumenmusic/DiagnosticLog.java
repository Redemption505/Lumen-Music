package com.lumenmusic;

import com.lumenmusic.audio.JavaFxAudioPlayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Only fixed error codes are retained. No message, track or application file is collected. */
public final class DiagnosticLog {
    private static final int LIMIT = 20;
    private static final Object FILE_LOCK = new Object();
    private static final AtomicBoolean HANDLER_INSTALLED = new AtomicBoolean();
    private static final Set<String> CODES = Set.of("UNKNOWN_ERROR", "SERVICE_START_ERROR", "PROXY_START_ERROR",
            "VLC_START_ERROR", "LIBRARY_ERROR", "SEARCH_ERROR", "LYRICS_ERROR", "UPDATE_ERROR",
            "PREFERENCES_ERROR", "SHUTDOWN_ERROR",
            "VIDEO_NOT_FOUND", "VIDEO_DELETED", "VIDEO_ACCESS_DENIED", "TRACK_PAGE_NOT_FOUND",
            "AUDIO_UNAVAILABLE", "AUDIO_FORMAT_UNSUPPORTED", "PLAYBACK_FORMAT_UNSUPPORTED",
            "LOCAL_FILE_MISSING", "LOCAL_FILE_NOT_FOUND", "UPSTREAM_TIMEOUT", "UPSTREAM_CONNECTION_LOST",
            "UPSTREAM_TLS_ERROR", "UPSTREAM_UNAVAILABLE", "AUDIO_SOURCE_ERROR", "HTTP_502", "HTTP_503",
            "HTTP_504", "UPSTREAM_REJECTED", "AUDIO_SOURCE_EXPIRED", "PLAYBACK_ERROR", "UNCAUGHT_ERROR");
    public enum Engine { JAVA_FX, VLC }
    public record Event(Instant time, String code) { }
    public record Snapshot(Engine engine, JavaFxAudioPlayer.State state, boolean normalization,
                           boolean preloading, boolean shortFade, boolean reducedMotion) {
        public Snapshot { Objects.requireNonNull(engine); Objects.requireNonNull(state); }
    }
    private final Path file;
    private final Clock clock;
    private List<Event> events = List.of();

    public DiagnosticLog(Path file) throws IOException { this(file, Clock.systemUTC()); }

    DiagnosticLog(Path file, Clock clock) throws IOException {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
        if (!Files.exists(this.file)) return;
        try {
            if (Files.size(this.file) > 65_536) throw new IOException("Diagnostic record is too large");
            Properties values = LocalStateFile.read(this.file, "diagnostics");
            int count = Integer.parseInt(values.getProperty("count", "-1"));
            if (count < 0 || count > LIMIT) throw new IOException("Invalid diagnostic count");
            List<Event> loaded = new ArrayList<>();
            for (int index = 0; index < count; index++)
                loaded.add(new Event(Instant.parse(values.getProperty(index + ".time")),
                        safeCode(values.getProperty(index + ".code"))));
            events = List.copyOf(loaded);
        } catch (RuntimeException error) { throw new IOException("Invalid diagnostic record", error); }
    }

    public synchronized void record(String code) throws IOException {
        synchronized (FILE_LOCK) {
            // Startup workers and the UI may hold separate log objects for the same file.
            var next = new ArrayList<>(Files.exists(file) ? new DiagnosticLog(file, clock).events : events);
            next.add(new Event(clock.instant(), safeCode(code)));
            if (next.size() > LIMIT) next.remove(0);
            Properties values = LocalStateFile.values("diagnostics");
            values.setProperty("count", Integer.toString(next.size()));
            for (int index = 0; index < next.size(); index++) {
                values.setProperty(index + ".time", next.get(index).time().toString());
                values.setProperty(index + ".code", next.get(index).code());
            }
            LocalStateFile.save(file, values);
            events = List.copyOf(next);
        }
    }

    public synchronized List<Event> recent() {
        synchronized (FILE_LOCK) {
            try { if (Files.exists(file)) events = new DiagnosticLog(file, clock).events; }
            catch (IOException ignored) { }
            return events;
        }
    }

    public void export(Path destination, Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot);
        BuildInfo build = BuildInfo.current();
        StringBuilder text = new StringBuilder("Lumen Music Diagnostics\n")
                .append("Generated (UTC): ").append(clock.instant()).append('\n')
                .append("Application: ").append(build.version()).append('\n')
                .append("Build: ").append(build.buildId()).append('\n')
                .append("Platform: ").append(platform()).append('\n')
                .append("Java: ").append(Runtime.version()).append('\n')
                .append("Engine: ").append(snapshot.engine()).append('\n')
                .append("Playback state: ").append(snapshot.state()).append('\n')
                .append("Normalization: ").append(snapshot.normalization()).append('\n')
                .append("Preloading: ").append(snapshot.preloading()).append('\n')
                .append("Short fade: ").append(snapshot.shortFade()).append('\n')
                .append("Reduced motion: ").append(snapshot.reducedMotion()).append('\n')
                .append("\nRecent errors (UTC, code only):\n");
        List<Event> recent = recent();
        if (recent.isEmpty()) text.append("None\n");
        else for (Event event : recent) text.append(event.time()).append(' ').append(event.code()).append('\n');
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "lumen-diagnostic-", ".tmp");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("diagnostics.txt"));
                zip.write(text.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static void recordStartup(String code) {
        try { new DiagnosticLog(defaultFile()).record(code); }
        catch (IOException | RuntimeException error) { System.err.println("Diagnostic record is unavailable"); }
    }

    /** Store a fixed code for failures that otherwise disappear with the packaged window. */
    static void installUncaughtExceptionHandler() {
        if (!HANDLER_INSTALLED.compareAndSet(false, true)) return;
        Thread.setDefaultUncaughtExceptionHandler(uncaughtHandler(defaultFile(),
                Thread.getDefaultUncaughtExceptionHandler()));
    }

    static Thread.UncaughtExceptionHandler uncaughtHandler(Path file,
                                                          Thread.UncaughtExceptionHandler previous) {
        return (thread, error) -> {
            try { new DiagnosticLog(file).record("UNCAUGHT_ERROR"); }
            catch (IOException | RuntimeException unavailable) {
                System.err.println("Diagnostic record is unavailable");
            }
            if (previous != null) previous.uncaughtException(thread, error);
            else System.err.println("Lumen Music 未处理错误：" + error.getClass().getSimpleName());
        };
    }

    static Path defaultFile() {
        String configured = System.getenv("LUMEN_LIBRARY_PATH");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().getParent().resolve("diagnostics.xml");
        String local = System.getenv("LOCALAPPDATA");
        Path root = local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(local);
        return root.resolve("Lumen Music").resolve("diagnostics.xml");
    }

    private static String safeCode(String code) { return CODES.contains(code == null ? "" : code) ? code : "UNKNOWN_ERROR"; }

    private static String platform() {
        String os = System.getProperty("os.name", "");
        String family = os.startsWith("Windows") ? "Windows" : os.startsWith("Mac") ? "macOS" : os.equals("Linux") ? "Linux" : "Other";
        String arch = System.getProperty("os.arch", "");
        return family + " " + (Set.of("amd64", "x86_64", "aarch64", "x86").contains(arch) ? arch : "unknown");
    }
}
