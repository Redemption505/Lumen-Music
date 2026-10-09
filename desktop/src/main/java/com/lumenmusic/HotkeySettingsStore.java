package com.lumenmusic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** A caller-supplied file keeps shortcut settings inside the application's selected profile. */
public final class HotkeySettingsStore {
    private final Path file;
    public HotkeySettingsStore(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath().normalize(); }

    public Map<GlobalHotkeys.Action, GlobalHotkeys.Combination> load() throws IOException {
        if (!Files.exists(file)) return GlobalHotkeys.defaults();
        Properties values = new Properties();
        try {
            try (InputStream input = Files.newInputStream(file)) { values.load(input); }
            if (!"1".equals(values.getProperty("version"))) throw new IllegalArgumentException("Unsupported version");
            EnumMap<GlobalHotkeys.Action, GlobalHotkeys.Combination> result = new EnumMap<>(GlobalHotkeys.Action.class);
            for (GlobalHotkeys.Action action : GlobalHotkeys.Action.values()) {
                String key = values.getProperty(action.name() + ".key");
                if (key != null) result.put(action, new GlobalHotkeys.Combination(
                        Integer.parseInt(values.getProperty(action.name() + ".modifiers")), Integer.parseInt(key)));
            }
            return Map.copyOf(result);
        } catch (RuntimeException error) { throw new IOException("快捷键设置无效：" + file, error); }
    }

    public void save(Map<GlobalHotkeys.Action, GlobalHotkeys.Combination> bindings) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "1");
        bindings.forEach((action, binding) -> {
            values.setProperty(action.name() + ".key", Integer.toString(binding.virtualKey()));
            values.setProperty(action.name() + ".modifiers", Integer.toString(binding.modifiers()));
        });
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "hotkeys-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { values.store(output, "Lumen Music hotkeys"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
