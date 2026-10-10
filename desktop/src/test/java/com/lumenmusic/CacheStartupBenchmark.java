package com.lumenmusic;

import com.lumenmusic.audio.PersistentAudioCache;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Component timing with generated cache bytes; this is not a native launch benchmark. */
public final class CacheStartupBenchmark {
    private CacheStartupBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Provide build-directory and sample count");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path build = Path.of("build").toAbsolutePath().normalize();
        if (!root.startsWith(build) || root.equals(build))
            throw new IllegalArgumentException("Synthetic benchmark requires a dedicated directory under build");
        int samples = Integer.parseInt(args[1]);
        if (samples < 3 || samples > 20) throw new IllegalArgumentException("Use 3 to 20 samples");
        Files.createDirectories(root);
        if (!root.toRealPath().startsWith(build.toRealPath()))
            throw new IllegalArgumentException("Benchmark directory escaped build");
        Path populated = root.resolve("populated-cache");
        Path empty = root.resolve("empty-cache");
        String key = hash("synthetic-startup-cache".getBytes(StandardCharsets.UTF_8));
        Path payload = populated.resolve(key + ".m4a");
        if (!Files.exists(payload)) {
            Files.createDirectories(populated);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < block.length; i++) block[i] = (byte) (i % 251);
            try (OutputStream output = Files.newOutputStream(payload)) {
                for (int i = 0; i < 256; i++) { output.write(block); digest.update(block); }
            }
            Properties entry = new Properties();
            entry.setProperty("version", "1");
            entry.setProperty("size", Long.toString(Files.size(payload)));
            entry.setProperty("sha256", HexFormat.of().formatHex(digest.digest()));
            try (OutputStream output = Files.newOutputStream(populated.resolve(key + ".properties"))) {
                entry.store(output, "Synthetic benchmark payload, not playable audio");
            }
            Properties settings = new Properties();
            settings.setProperty("version", "1");
            settings.setProperty("enabled", "false");
            settings.setProperty("maxBytes", Long.toString(512L * 1024 * 1024));
            try (OutputStream output = Files.newOutputStream(populated.resolve("settings.properties"))) {
                settings.store(output, "Disabled cache with retained complete files");
            }
        }
        long size = Files.size(payload);
        if (size != 256L * 1024 * 1024) throw new AssertionError("Unexpected synthetic payload size");
        double[] emptyTimes = new double[samples];
        double[] populatedTimes = new double[samples];
        for (int i = 0; i < samples; i++) {
            // Alternate order; all timings are in one JVM and the existing system session.
            if (i % 2 == 0) {
                emptyTimes[i] = measure(empty, 0);
                populatedTimes[i] = measure(populated, size);
            } else {
                populatedTimes[i] = measure(populated, size);
                emptyTimes[i] = measure(empty, 0);
            }
        }
        System.out.println("ProductionClassOrigin=" + PersistentAudioCache.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("{\"payloadBytes\":" + size + ",\"samples\":" + samples
                + ",\"emptyCacheMilliseconds\":" + java.util.Arrays.toString(emptyTimes)
                + ",\"disabledPopulatedCacheMilliseconds\":" + java.util.Arrays.toString(populatedTimes)
                + ",\"nativeLaunchBenchmark\":false}");
    }

    private static double measure(Path directory, long expectedBytes) throws Exception {
        long start = System.nanoTime();
        PersistentAudioCache cache = new PersistentAudioCache(directory);
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        if (cache.enabled() || cache.usedBytes() != expectedBytes)
            throw new AssertionError("Cache settings or retained payload were altered");
        return elapsed;
    }

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
