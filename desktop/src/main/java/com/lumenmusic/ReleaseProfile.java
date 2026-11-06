package com.lumenmusic;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

/** Build-time product boundary for the full app and isolated local preview. */
public record ReleaseProfile(String id) {
    private static final ReleaseProfile FULL = new ReleaseProfile("full");
    private static final ReleaseProfile LOCAL_FIRST = new ReleaseProfile("local-first");
    private static final ReleaseProfile CURRENT = load();

    public ReleaseProfile {
        if (!"full".equals(id) && !"local-first".equals(id))
            throw new IllegalArgumentException("Unknown Lumen release profile: " + id);
    }

    public static ReleaseProfile current() { return CURRENT; }
    public static ReleaseProfile from(String value) {
        if (value == null || value.isBlank() || value.equals("full")) return FULL;
        if (value.equals("local-first")) return LOCAL_FIRST;
        throw new IllegalArgumentException("Unknown Lumen release profile: " + value);
    }

    public boolean localFirst() { return id.equals("local-first"); }
    public boolean allowsAudioUri(URI uri) {
        return !localFirst() || (uri != null && "file".equalsIgnoreCase(uri.getScheme()));
    }
    public boolean allowsCoverUri(URI uri) {
        if (!localFirst()) return true;
        String scheme = uri == null ? null : uri.getScheme();
        return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme);
    }
    public String provider() { return localFirst() ? "local" : "bilibili"; }
    public String serviceArgumentProvider(String configuredProvider) {
        return localFirst() ? "bilibili" : configuredProvider;
    }
    public String appDirectoryName() { return localFirst() ? "Lumen Music Local Preview" : "Lumen Music"; }
    public int servicePort() { return localFirst() ? 18765 : 8765; }
    public int streamPort() { return localFirst() ? 18766 : 8766; }

    public URI serviceUri() { return endpoint("LUMEN_SERVICE_URL", servicePort()); }
    public URI streamUri() { return endpoint("LUMEN_STREAM_URL", streamPort()); }

    public Path appDataDirectory() {
        String base;
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) base = Path.of(System.getProperty("user.home"),
                    "AppData", "Local").toString();
        } else {
            base = System.getenv("XDG_DATA_HOME");
            if (base == null || base.isBlank()) base = Path.of(System.getProperty("user.home"),
                    ".local", "share").toString();
        }
        return Path.of(base).resolve(appDirectoryName());
    }

    public Path dataDirectory() {
        if (localFirst()) return appDataDirectory();
        String configured = System.getenv("LUMEN_LIBRARY_PATH");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath().getParent();
        return appDataDirectory();
    }

    public Path preferencesPath() {
        if (localFirst()) return appDataDirectory().resolve("preferences.properties");
        String configured = System.getenv("LUMEN_PREFERENCES_PATH");
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank())
            return Path.of(appData, "Lumen Music", "preferences.properties");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("lumen-music").resolve("preferences.properties");
    }

    private URI endpoint(String envName, int defaultPort) {
        String configured = System.getenv(envName);
        URI uri = configured == null || configured.isBlank()
                ? URI.create("http://127.0.0.1:" + defaultPort) : URI.create(configured);
        if (localFirst() && (!"http".equalsIgnoreCase(uri.getScheme())
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()))))
            throw new IllegalArgumentException(envName + " 必须指向本机服务");
        return uri;
    }

    private static ReleaseProfile load() {
        Properties values = new Properties();
        try (var input = ReleaseProfile.class.getResourceAsStream("/lumen-build.properties")) {
            if (input == null) return FULL;
            values.load(input);
            return from(values.getProperty("release.profile", "full").trim());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read Lumen release profile", error);
        }
    }
}
