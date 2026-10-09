package com.lumenmusic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AppearancePreferencesStoreTest {
    @TempDir Path temporary;

    @Test void missingFileUsesDefaultsWithoutWarningAndCanSave() throws Exception {
        Path file = temporary.resolve("new-directory/appearance.properties");
        var store = new AppearancePreferencesStore(file);

        var loaded = store.load();

        assertTrue(loaded.properties().isEmpty());
        assertEquals("", loaded.warning());
        assertNull(loaded.recoveryFile());
        assertFalse(Files.exists(file));
        store.save(allPreferences());
        assertEquals(allPreferences(), new AppearancePreferencesStore(file).load().properties());
    }

    @Test void allExistingOptionsRoundTripAcrossRestartInPlainPropertiesFormat() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        new AppearancePreferencesStore(file).save(allPreferences());

        var loaded = new AppearancePreferencesStore(file).load();

        assertEquals(allPreferences(), loaded.properties());
        assertEquals("", loaded.warning());
        assertNull(loaded.recoveryFile());
        Properties conventional = new Properties();
        try (var input = Files.newInputStream(file)) { conventional.load(input); }
        assertEquals(allPreferences(), conventional);
        assertFalse(Files.readString(file).contains("<?xml"));
        assertEquals(List.of(file), entries());
    }

    @Test void damagedUnicodeReturnsDefaultsAndRetainsRecoverableBytesBeforeSaving() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        byte[] damaged = damagedBytes();
        Files.write(file, damaged);
        var store = new AppearancePreferencesStore(file);

        var loaded = store.load();

        assertTrue(loaded.properties().isEmpty(), "Do not apply values parsed before the damaged escape");
        assertTrue(loaded.warning().contains("已损坏"));
        assertNotNull(loaded.recoveryFile());
        assertTrue(loaded.warning().contains(loaded.recoveryFile().toString()));
        assertArrayEquals(damaged, Files.readAllBytes(file));
        assertArrayEquals(damaged, Files.readAllBytes(loaded.recoveryFile()));
        store.save(allPreferences());
        assertEquals(allPreferences(), new AppearancePreferencesStore(file).load().properties());
        assertArrayEquals(damaged, Files.readAllBytes(loaded.recoveryFile()));
        Path restored = temporary.resolve("restored-original.properties");
        Files.copy(loaded.recoveryFile(), restored);
        assertArrayEquals(damaged, Files.readAllBytes(restored));
    }

    @Test void truncatedUnicodeAlsoReturnsDefaultsAndRetainsBytes() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        byte[] damaged = ("theme=light\nbroken=" + "\\" + "u123\n").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file, damaged);

        var loaded = new AppearancePreferencesStore(file).load();

        assertTrue(loaded.properties().isEmpty());
        assertTrue(loaded.warning().contains("已损坏"));
        assertArrayEquals(damaged, Files.readAllBytes(file));
        assertArrayEquals(damaged, Files.readAllBytes(loaded.recoveryFile()));
    }

    @Test void repeatedLoadReusesTheCompleteRecoveryCopy() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        Files.write(file, damagedBytes());
        var store = new AppearancePreferencesStore(file);

        var first = store.load();
        var second = store.load();

        assertEquals(first.recoveryFile(), second.recoveryFile());
        assertEquals(2, entries().size());
    }

    @Test void failedRecoveryCopyKeepsOriginalBlocksSavingAndCleansPartialCopy() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        byte[] damaged = damagedBytes();
        Files.write(file, damaged);
        var store = new AppearancePreferencesStore(file, new AppearancePreferencesStore.FileOperations() {
            @Override public void preserve(Path backup, byte[] bytes) throws IOException {
                Files.write(backup, new byte[] { 1, 2 });
                throw new IOException("controlled backup failure");
            }
        });

        var loaded = store.load();

        assertTrue(loaded.properties().isEmpty());
        assertTrue(loaded.warning().contains("暂停保存"));
        assertNull(loaded.recoveryFile());
        assertThrows(IOException.class, () -> store.save(allPreferences()));
        assertArrayEquals(damaged, Files.readAllBytes(file));
        assertEquals(List.of(file), entries());
    }

    @Test void readFailureIsNotTreatedAsMissingAndDoesNotOverwriteUnreadableOriginal() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        new AppearancePreferencesStore(file).save(allPreferences());
        byte[] original = Files.readAllBytes(file);
        var store = new AppearancePreferencesStore(file, new AppearancePreferencesStore.FileOperations() {
            @Override public byte[] read(Path target) throws IOException { throw new IOException("controlled read failure"); }
        });

        var loaded = store.load();

        assertTrue(loaded.properties().isEmpty());
        assertTrue(loaded.warning().contains("读取失败"));
        assertTrue(loaded.warning().contains("暂停保存"));
        assertNull(loaded.recoveryFile());
        assertThrows(IOException.class, () -> store.save(allPreferences()));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(List.of(file), entries());
    }

    @Test void savingWithoutLoadingCannotDiscardDamagedFile() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        Files.write(file, damagedBytes());

        assertThrows(IOException.class, () -> new AppearancePreferencesStore(file).save(allPreferences()));

        assertArrayEquals(damagedBytes(), Files.readAllBytes(file));
        assertEquals(List.of(file), entries());
    }

    @Test void corruptionAfterNormalLoadCannotBeOverwritten() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        var store = new AppearancePreferencesStore(file);
        store.save(allPreferences());
        assertEquals("", store.load().warning());
        Files.write(file, damagedBytes());

        assertThrows(IOException.class, () -> store.save(allPreferences()));

        assertArrayEquals(damagedBytes(), Files.readAllBytes(file));
        assertEquals(List.of(file), entries());
    }

    @Test void changedDamagedFileOrLostRecoveryCopyPreventsReplacement() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        Files.write(file, damagedBytes());
        var store = new AppearancePreferencesStore(file);
        var loaded = store.load();
        byte[] changed = ("changed=true\n" + new String(damagedBytes(), StandardCharsets.ISO_8859_1))
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(file, changed);

        assertThrows(IOException.class, () -> store.save(allPreferences()));
        assertArrayEquals(changed, Files.readAllBytes(file));
        assertArrayEquals(damagedBytes(), Files.readAllBytes(loaded.recoveryFile()));
        Files.write(file, damagedBytes());
        Files.delete(loaded.recoveryFile());
        assertThrows(IOException.class, () -> store.save(allPreferences()));
        assertArrayEquals(damagedBytes(), Files.readAllBytes(file));
    }

    @Test void failedTemporaryWriteLeavesOldValidFileByteForByteAndCleansTemporaryFile() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        new AppearancePreferencesStore(file).save(allPreferences());
        byte[] original = Files.readAllBytes(file);
        var store = new AppearancePreferencesStore(file, new AppearancePreferencesStore.FileOperations() {
            @Override public void write(Path target, Properties values) throws IOException {
                Files.writeString(target, "partial write");
                throw new IOException("controlled write failure");
            }
        });

        assertThrows(IOException.class, () -> store.save(new Properties()));

        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(allPreferences(), new AppearancePreferencesStore(file).load().properties());
        assertEquals(List.of(file), entries());
    }

    @Test void failedReplacementLeavesOldValidFileByteForByteAndCleansTemporaryFile() throws Exception {
        Path file = temporary.resolve("appearance.properties");
        new AppearancePreferencesStore(file).save(allPreferences());
        byte[] original = Files.readAllBytes(file);
        var store = new AppearancePreferencesStore(file, new AppearancePreferencesStore.FileOperations() {
            @Override public void replace(Path source, Path target) throws IOException {
                throw new IOException("controlled replacement failure");
            }
        });

        assertThrows(IOException.class, () -> store.save(new Properties()));

        assertArrayEquals(original, Files.readAllBytes(file));
        assertEquals(allPreferences(), new AppearancePreferencesStore(file).load().properties());
        assertEquals(List.of(file), entries());
    }

    private List<Path> entries() throws IOException {
        try (var paths = Files.list(temporary)) { return paths.sorted().toList(); }
    }

    private static byte[] damagedBytes() {
        return ("theme=light\naccent=PINK\nbroken=" + "\\" + "u12XZ\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Properties allPreferences() {
        Properties values = new Properties();
        values.setProperty("theme", "light");
        values.setProperty("accent", "BLUE");
        values.setProperty("pet", "BLACK_CAT");
        values.setProperty("pet.desktop.immersive", "false");
        values.setProperty("pet.desktop.minimized", "true");
        values.setProperty("pet.desktop.x", "123.5");
        values.setProperty("pet.desktop.y", "456.75");
        values.setProperty("sidebar.collapsed", "true");
        values.setProperty("sidebar.width", "256.0");
        values.setProperty("queue.width", "420.0");
        values.setProperty("audio.normalization", "true");
        values.setProperty("audio.preload", "false");
        values.setProperty("audio.shortFade", "true");
        values.setProperty("motion.reduced", "true");
        values.setProperty("background", "D:\\合成音乐资料\\背景.png");
        return values;
    }
}
