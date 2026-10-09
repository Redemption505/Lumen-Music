package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/** Keeps the existing plain properties format and retains damaged preferences for recovery. */
public final class AppearancePreferencesStore {
    public record LoadResult(Properties properties, String warning, Path recoveryFile) {
        public LoadResult {
            properties = (Properties) Objects.requireNonNull(properties).clone();
            warning = Objects.requireNonNull(warning);
        }

        @Override public Properties properties() { return (Properties) properties.clone(); }
    }

    /** File operations are overridable only within the package for controlled failure tests. */
    interface FileOperations {
        default byte[] read(Path file) throws IOException { return Files.readAllBytes(file); }
        default void preserve(Path file, byte[] bytes) throws IOException { Files.write(file, bytes); }
        default void write(Path file, Properties values) throws IOException {
            try (var output = Files.newOutputStream(file)) { values.store(output, "Lumen Music"); }
        }
        default void replace(Path temporary, Path file) throws IOException {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private final Path file;
    private final FileOperations operations;
    private Path recoveryFile;

    public AppearancePreferencesStore(Path file) { this(file, new FileOperations() { }); }

    AppearancePreferencesStore(Path file, FileOperations operations) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.operations = Objects.requireNonNull(operations);
    }

    public synchronized LoadResult load() {
        byte[] bytes;
        try {
            bytes = operations.read(file);
        } catch (NoSuchFileException error) {
            recoveryFile = null;
            return new LoadResult(new Properties(), "", null);
        } catch (IOException error) {
            return new LoadResult(new Properties(), "外观偏好读取失败，原文件已保留；恢复可读取的文件前暂停保存。", null);
        }
        try {
            Properties values = parse(bytes);
            recoveryFile = null;
            return new LoadResult(values, "", null);
        } catch (IllegalArgumentException | IOException error) {
            try {
                if (!hasRecovery(bytes)) recoveryFile = preserve(bytes);
                return new LoadResult(new Properties(), "外观偏好已损坏，已使用默认设置；原始内容的恢复副本：" + recoveryFile,
                        recoveryFile);
            } catch (IOException preservationError) {
                recoveryFile = null;
                return new LoadResult(new Properties(), "外观偏好已损坏，已使用默认设置；原文件已保留，恢复副本创建失败，暂停保存。", null);
            }
        }
    }

    public synchronized void save(Properties values) throws IOException {
        Objects.requireNonNull(values, "Appearance preferences are required");
        ensureRecoverableBeforeReplacing();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "lumen-appearance-", ".tmp");
        try {
            operations.write(temporary, values);
            operations.replace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureRecoverableBeforeReplacing() throws IOException {
        byte[] bytes;
        try {
            bytes = operations.read(file);
        } catch (NoSuchFileException error) {
            return;
        } catch (IOException error) {
            throw new IOException("外观偏好原文件无法读取，已保留并暂停保存。", error);
        }
        try {
            parse(bytes);
        } catch (IllegalArgumentException | IOException error) {
            if (!hasRecovery(bytes))
                throw new IOException("外观偏好原文件已损坏且没有完整恢复副本，已保留并暂停保存。", error);
        }
    }

    private boolean hasRecovery(byte[] bytes) throws IOException {
        if (recoveryFile == null) return false;
        try { return Arrays.equals(bytes, operations.read(recoveryFile)); }
        catch (NoSuchFileException error) { return false; }
    }

    private Path preserve(byte[] bytes) throws IOException {
        Path backup = Files.createTempFile(file.getParent(), file.getFileName() + ".damaged-", ".bak");
        boolean complete = false;
        try {
            operations.preserve(backup, bytes);
            complete = true;
            return backup;
        } finally {
            if (!complete) Files.deleteIfExists(backup);
        }
    }

    private static Properties parse(byte[] bytes) throws IOException {
        Properties values = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) { values.load(input); }
        return values;
    }
}
