package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalUpdatePackageTest {
    @TempDir Path temporary;

    @Test void acceptsRootAndSingleReleaseDirectoryPackages() throws Exception {
        Fixture fixture = fixture();
        for (String prefix : List.of("", "Lumen-Music-v0.2.0-Update/")) {
            Path zip = fixture.zip(prefix, UnaryOperator.identity(), Map.of());
            var inspected = LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1");
            assertEquals(prefix, inspected.prefix());
            assertEquals("0.2.0", inspected.manifest().toVersion());
            assertEquals("build-test", inspected.manifest().buildId());
            assertEquals(7, inspected.manifest().files().size());
            assertEquals(1, inspected.manifest().requirements().size());
        }
    }

    @Test void stagingCopiesOnlyDeclaredPayloadAndPreservesInstallationAndPersonalFiles() throws Exception {
        Fixture fixture = fixture();
        Path personal = fixture.install.resolve("app/custom-user-data.json");
        Files.writeString(personal, "preserve");
        Path zip = fixture.zip("Release/", UnaryOperator.identity(), Map.of(
                "Release/apply-update.ps1", bytes("DO NOT EXECUTE"), "Release/SOURCE.zip", bytes("source"),
                "Release/README.txt", bytes("readme")));
        byte[] oldJar = Files.readAllBytes(fixture.install.resolve("app/lumen-desktop-0.1.1.jar"));
        var prepared = LocalUpdatePackage.stage(LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"), temporary.resolve("staging"));
        assertTrue(Files.isRegularFile(prepared.stageRoot().resolve("manifest.xml")));
        for (var entry : prepared.inspection().manifest().files())
            assertArrayEquals(fixture.payload.get(entry.payload()), Files.readAllBytes(prepared.stageRoot().resolve(entry.payload())));
        assertFalse(Files.exists(prepared.stageRoot().resolve("apply-update.ps1")));
        assertFalse(Files.exists(prepared.stageRoot().resolve("SOURCE.zip")));
        assertFalse(Files.exists(prepared.stageRoot().resolve("README.txt")));
        assertEquals("preserve", Files.readString(personal));
        assertArrayEquals(oldJar, Files.readAllBytes(fixture.install.resolve("app/lumen-desktop-0.1.1.jar")));
        assertFalse(Files.exists(fixture.install.resolve("app/lumen-desktop-0.2.0.jar")));
    }

    @Test void sameVersionHotfixAndStageBuildSourcesDoNotNeedOneOldJarHash() throws Exception {
        Fixture fixture = fixture();
        Path zip = fixture.zip();
        for (String old : List.of("original", "lyrics-hotfix", "local-phase3-build")) {
            Files.writeString(fixture.install.resolve("app/lumen-desktop-0.1.1.jar"), old);
            assertNotNull(LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void acceptsAndStagesTheExactManagedJimageWithoutRequiringOldImageBytes() throws Exception {
        Fixture fixture = fixture();
        Path oldImage = fixture.install.resolve("runtime/lib/modules");
        Files.createDirectories(oldImage.getParent());
        byte[] previous = bytes("independently linked old image");
        Files.write(oldImage, previous);
        Path currentImage = Path.of(System.getProperty("java.home"), "lib", "modules");
        fixture.payload.put("payload/root/runtime/lib/modules", Files.readAllBytes(currentImage));
        Path zip = fixture.zip();
        var inspected = LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1");
        var managed = inspected.manifest().files().stream()
                .filter(file -> file.target().equals("runtime/lib/modules")).findFirst().orElseThrow();
        assertEquals("root", managed.scope());
        assertEquals("payload/root/runtime/lib/modules", managed.payload());
        assertEquals(Files.size(currentImage), managed.size());
        assertTrue(inspected.manifest().requirements().stream().noneMatch(file -> file.path().equals("runtime/lib/modules")));
        var prepared = LocalUpdatePackage.stage(inspected, temporary.resolve("staging"));
        assertEquals(-1L, Files.mismatch(currentImage, prepared.stageRoot().resolve(managed.payload())));
        assertArrayEquals(previous, Files.readAllBytes(oldImage));
        Files.writeString(fixture.install.resolve("runtime/release"), "modified retained runtime");
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
    }

    @Test void rejectsManagedJimageWithWrongHashOrSize() throws Exception {
        Fixture fixture = fixture();
        byte[] image = bytes("jimage integrity fixture");
        fixture.payload.put("payload/root/runtime/lib/modules", image);
        for (UnaryOperator<String> change : List.<UnaryOperator<String>>of(
                xml -> xml.replace(sha(image), "0".repeat(64)),
                xml -> xml.replace(fixture.fileXml("payload/root/runtime/lib/modules", image),
                        fixture.fileXml("payload/root/runtime/lib/modules", image).replace("size=\"" + image.length + "\"", "size=\"999\"")))) {
            Path zip = fixture.zip("", change, Map.of());
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsOtherManagedRuntimePathsAndAppScopedJimage() throws Exception {
        for (String payload : List.of("payload/root/runtime/release", "payload/root/runtime/bin/java.dll",
                "payload/root/runtime/lib/jvm.cfg", "payload/root/runtime/lib/modules.old",
                "payload/root/runtime/lib/MODULES", "payload/runtime/lib/modules")) {
            Fixture fixture = fixture();
            fixture.payload.put(payload, bytes("unexpected managed runtime"));
            Path zip = fixture.zip();
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsJimageDeclaredAsBothManagedAndRetainedIncludingCaseAliases() throws Exception {
        for (String retainedPath : List.of("runtime/lib/modules", "runtime/lib/MODULES")) {
            Fixture fixture = fixture();
            byte[] image = bytes("same image in conflicting scopes");
            fixture.payload.put("payload/root/runtime/lib/modules", image);
            fixture.requirements.put(retainedPath, image);
            Path installed = fixture.install.resolve(retainedPath);
            Files.createDirectories(installed.getParent());
            Files.write(installed, image);
            Path zip = fixture.zip();
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsWrongFromSameOrOlderTargetAndWrongPlatform() throws Exception {
        Fixture fixture = fixture();
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.0"));
        for (UnaryOperator<String> change : List.<UnaryOperator<String>>of(
                xml -> xml.replace("toVersion=\"0.2.0\"", "toVersion=\"0.1.1\""),
                xml -> xml.replace("toVersion=\"0.2.0\"", "toVersion=\"0.1.0\""),
                xml -> xml.replace("platform=\"windows\"", "platform=\"linux\""),
                xml -> xml.replace("arch=\"x64\"", "arch=\"arm64\""),
                xml -> xml.replace("fromVersion=\"0.1.1\"", "fromVersion=\"00.1.1\""))) {
            Path zip = fixture.zip("", change, Map.of());
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsUnknownXmlDtdInvalidIdentityAndBuildIdentifiers() throws Exception {
        Fixture fixture = fixture();
        for (UnaryOperator<String> change : List.<UnaryOperator<String>>of(
                xml -> "<!DOCTYPE update [<!ENTITY extra SYSTEM 'file:///missing'>]>" + xml,
                xml -> xml.replace("format=\"1\"", "format=\"1\" unknown=\"x\""),
                xml -> xml.replace("<files>", "<files><unknown/>"),
                xml -> xml.replace("buildId=\"build-test\"", "buildId=\"含中文\""),
                xml -> xml.replace(fixture.packageId, fixture.packageId.toUpperCase()),
                xml -> xml.replace("oldJar=\"lumen-desktop-0.1.1.jar\"", "oldJar=\"other.jar\""))) {
            Path zip = fixture.zip("", change, Map.of());
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsMissingOrAmbiguousManifestsAndDeepDirectoryLayouts() throws Exception {
        Fixture fixture = fixture();
        Path absent = temporary.resolve("absent.zip");
        writeZip(absent, Map.of("README.txt", bytes("readme")));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(absent, fixture.install, "0.1.1"));
        Path ambiguous = fixture.zip("", UnaryOperator.identity(), Map.of("Other/manifest.xml", bytes(fixture.manifest())));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(ambiguous, fixture.install, "0.1.1"));
        Path deep = fixture.zip("One/Two/", UnaryOperator.identity(), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(deep, fixture.install, "0.1.1"));
    }

    @Test void rejectsWrongHashSizeAndMissingPayloadBeforeStaging() throws Exception {
        Fixture fixture = fixture();
        Path changed = fixture.zip("", UnaryOperator.identity(), Map.of("payload/root/LumenMusic.exe", bytes("tampered")));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(changed, fixture.install, "0.1.1"));
        Path wrongHash = fixture.zip("", xml -> xml.replace(sha(fixture.payload.get("payload/root/LumenMusic.exe")), "0".repeat(64)), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(wrongHash, fixture.install, "0.1.1"));
        Path wrongSize = fixture.zip("", xml -> xml.replaceFirst("size=\"[0-9]+\"", "size=\"999\""), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(wrongSize, fixture.install, "0.1.1"));
        String manifest = fixture.manifest();
        fixture.payload.remove("payload/LumenMusic.cfg");
        Path missing = fixture.zip("", ignored -> manifest, Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(missing, fixture.install, "0.1.1"));
    }

    @Test void rejectsWindowsCaseDuplicatesTraversalAndUnexpectedTargets() throws Exception {
        Fixture fixture = fixture();
        Path alias = fixture.zip("", UnaryOperator.identity(), Map.of("PAYLOAD/ROOT/lumenmusic.EXE", bytes("alias")));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(alias, fixture.install, "0.1.1"));
        for (String path : List.of("../outside.txt", "/absolute.txt", "C:/absolute.txt", "payload\\wrong.txt", "payload/CON.txt")) {
            Path zip = fixture.zip("", UnaryOperator.identity(), Map.of(path, bytes("bad")));
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
        Path wrongTarget = fixture.zip("", xml -> xml.replace("path=\"LumenMusic.cfg\"", "path=\"library.json\""), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(wrongTarget, fixture.install, "0.1.1"));
        Path repeated = fixture.zip("", xml -> xml.replace("</files>", fixture.fileXml("payload/LumenMusic.cfg", fixture.payload.get("payload/LumenMusic.cfg")) + "</files>"), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(repeated, fixture.install, "0.1.1"));
    }

    @Test void rejectsInvalidInstallationAndWrongSourceOrTargetConfig() throws Exception {
        Fixture fixture = fixture();
        Path zip = fixture.zip();
        Path missing = temporary.resolve("not-installed"); Files.createDirectory(missing);
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, missing, "0.1.1"));
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.0"));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.1"));
        fixture.payload.put("payload/LumenMusic.cfg", bytes(config("0.1.1")));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
    }

    @Test void validatesRetainedRuntimeAndRejectsRequirementsForReplacedOrUserFiles() throws Exception {
        Fixture fixture = fixture();
        Path zip = fixture.zip();
        Files.writeString(fixture.install.resolve("runtime/release"), "changed-runtime");
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        Files.write(fixture.install.resolve("runtime/release"), fixture.requirements.get("runtime/release"));
        for (String path : List.of("app/lumen-desktop-0.1.1.jar", "app/LumenMusic.cfg", "app/library.json")) {
            Path altered = fixture.zip("", xml -> xml.replace("path=\"runtime/release\"", "path=\"" + path + "\""), Map.of());
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(altered, fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsJarWithoutMatchingVersionOrBuildMetadata() throws Exception {
        Fixture fixture = fixture();
        for (byte[] jar : List.of(jar("version=0.2.1\nbuild.id=build-test\n"), jar("version=0.2.0\nbuild.id=other\n"), jar(null))) {
            fixture.payload.put("payload/lumen-desktop-0.2.0.jar", jar);
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        }
    }

    @Test void requiresExactlyOneVersionPropertyInBothSourceAndTargetConfig() throws Exception {
        Fixture fixture = fixture();
        Path zip = fixture.zip();
        for (String config : List.of(config("0.1.1").replace("java-options=-Djpackage.app-version=0.1.1\n", ""),
                config("0.1.1") + "java-options=-Djpackage.app-version=0.1.1\n")) {
            Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config);
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1"));
        }
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.1"));
        for (String config : List.of(config("0.2.0").replace("java-options=-Djpackage.app-version=0.2.0\n", ""),
                config("0.2.0") + "java-options=-Djpackage.app-version=0.2.0\n")) {
            fixture.payload.put("payload/LumenMusic.cfg", bytes(config));
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        }
    }

    @Test void everySourceAndTargetClasspathDependencyMustBeCoveredByRequirements() throws Exception {
        Fixture fixture = fixture();
        String dependency = "app.classpath=$APPDIR\\javafx-controls.jar\n";
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.1") + dependency);
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.1"));
        fixture.payload.put("payload/LumenMusic.cfg", bytes(config("0.2.0") + dependency));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        fixture.requirements.put("app/javafx-controls.jar", bytes("unchanged dependency"));
        Files.write(fixture.install.resolve("app/javafx-controls.jar"), fixture.requirements.get("app/javafx-controls.jar"));
        Files.writeString(fixture.install.resolve("app/LumenMusic.cfg"), config("0.1.1") + dependency);
        assertNotNull(LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
    }

    @Test void validatesSourceImageMarkersAndTargetImageVersionWhileSupportingMsiMarkers() throws Exception {
        Fixture fixture = fixture();
        assertNotNull(LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        Files.writeString(fixture.install.resolve("app/.jpackage.xml"), image("0.1.0"));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        Files.writeString(fixture.install.resolve("app/.jpackage.xml"), image("0.1.1"));
        assertNotNull(LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        fixture.payload.put("payload/.jpackage.xml", bytes(image("0.1.1")));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        fixture.payload.put("payload/.jpackage.xml", bytes("<other><app-version>0.2.0</app-version></other>"));
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
    }

    @Test void rejectsDuplicateBuildPropertiesAndMetadataOverTheHelperLimit() throws Exception {
        Fixture fixture = fixture();
        for (String metadata : List.of("version=0.2.0\nbuild.id=build-test\nversion=0.2.0\n",
                "version=0.2.0\nbuild.id=build-test\n#" + "x".repeat(8192))) {
            fixture.payload.put("payload/lumen-desktop-0.2.0.jar", jar(metadata));
            assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1"));
        }
    }

    @Test void rejectsPayloadAndArchiveSizeLimitsWithoutExpandingLargeData() throws Exception {
        Fixture fixture = fixture();
        Path oversized = fixture.zip("", xml -> xml.replaceFirst("size=\"[0-9]+\"", "size=\"268435457\""), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(oversized, fixture.install, "0.1.1"));
        Path total = fixture.zip("", xml -> xml.replaceAll("size=\"[0-9]+\"", "size=\"200000000\""), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(total, fixture.install, "0.1.1"));
        Path largeZip = temporary.resolve("large.zip");
        try (RandomAccessFile file = new RandomAccessFile(largeZip.toFile(), "rw")) { file.setLength(512L * 1024 * 1024 + 1); }
        assertThrows(IOException.class, () -> LocalUpdatePackage.inspect(largeZip, fixture.install, "0.1.1"));
    }

    @Test void stageRechecksChangedPayloadManifestAndRuntimeAfterReview() throws Exception {
        Fixture fixture = fixture();
        Path zip = fixture.zip();
        var inspected = LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1");
        fixture.payload.put("payload/root/LumenMusic.exe", bytes("another valid launcher"));
        fixture.overwrite(zip, "", UnaryOperator.identity(), Map.of());
        assertThrows(IOException.class, () -> LocalUpdatePackage.stage(inspected, temporary.resolve("staging")));
        assertFalse(Files.exists(temporary.resolve("staging")));
        var reviewed = LocalUpdatePackage.inspect(zip, fixture.install, "0.1.1");
        Files.writeString(fixture.install.resolve("runtime/release"), "modified");
        assertThrows(IOException.class, () -> LocalUpdatePackage.stage(reviewed, temporary.resolve("staging")));
    }

    @Test void emptyPythonModuleIsAllowedAndStagedWithoutInventingContents() throws Exception {
        Fixture fixture = fixture();
        fixture.payload.put("payload/service/lumen_service/__init__.py", new byte[0]);
        var inspected = LocalUpdatePackage.inspect(fixture.zip(), fixture.install, "0.1.1");
        var staged = LocalUpdatePackage.stage(inspected, temporary.resolve("staging"));
        assertEquals(0, Files.size(staged.stageRoot().resolve("payload/service/lumen_service/__init__.py")));
    }

    @Test void latestResultReadsNewestStatusAndDoesNotExecuteAnything() throws Exception {
        Path parent = temporary.resolve("staging");
        assertTrue(LocalUpdatePackage.latestResult(parent).isEmpty());
        for (int index = 0; index < 5; index++) {
            String status = List.of("success", "already_current", "failed_unchanged", "rolled_back", "rollback_failed").get(index);
            Path result = parent.resolve("update-" + index).resolve("result.xml");
            Files.createDirectories(result.getParent());
            Files.writeString(result, "<result status=\"" + status + "\" fromVersion=\"0.1.1\" toVersion=\"0.2.0\" buildId=\"build-test\" backupPath=\"\" message=\"已完成 &amp; 已核对\"/>");
            Files.setLastModifiedTime(result, FileTime.fromMillis(1_000L * (index + 1)));
            var read = LocalUpdatePackage.latestResult(parent).orElseThrow();
            assertEquals(status, read.status());
            assertEquals("0.1.1", read.fromVersion());
            assertEquals("0.2.0", read.toVersion());
            assertEquals("build-test", read.buildId());
            assertEquals("已完成 & 已核对", read.message());
            assertNull(read.backupPath());
        }
    }

    @Test void developmentClassesAreNotMistakenForAnInstalledApplication() {
        assertTrue(LocalUpdatePackage.installationRoot().isEmpty());
    }

    private Fixture fixture() throws Exception { return new Fixture(temporary.resolve("install-" + UUID.randomUUID())); }

    private final class Fixture {
        final Path install;
        final String packageId = "12345678-abcd-4abc-8abc-123456789abc";
        final Map<String, byte[]> payload = new LinkedHashMap<>();
        final Map<String, byte[]> requirements = new LinkedHashMap<>(Map.of("runtime/release", bytes("JAVA_VERSION=21")));
        Fixture(Path install) throws Exception {
            this.install = install;
            Files.createDirectories(install.resolve("app")); Files.createDirectories(install.resolve("runtime"));
            Files.writeString(install.resolve("LumenMusic.exe"), "old-launcher");
            Files.writeString(install.resolve("app/LumenMusic.cfg"), config("0.1.1"));
            Files.writeString(install.resolve("app/.package"), "LumenMusic");
            Files.writeString(install.resolve("app/lumen-desktop-0.1.1.jar"), "old-main-jar");
            for (var entry : requirements.entrySet()) Files.write(install.resolve(entry.getKey()), entry.getValue());
            payload.put("payload/root/LumenMusic.exe", bytes("new-launcher"));
            payload.put("payload/lumen-desktop-0.2.0.jar", jar("version=0.2.0\nbuild.id=build-test\n"));
            payload.put("payload/LumenMusic.cfg", bytes(config("0.2.0")));
            payload.put("payload/.jpackage.xml", bytes(image("0.2.0")));
            payload.put("payload/stream-proxy/lumen-stream-proxy.exe", bytes("proxy"));
            payload.put("payload/service/lumen_service/__main__.py", bytes("print('main')"));
            payload.put("payload/service/lumen_service/__init__.py", bytes("# module"));
        }
        String manifest() {
            StringBuilder xml = new StringBuilder("<update format=\"1\" app=\"LumenMusic\" platform=\"windows\" arch=\"x64\" fromVersion=\"0.1.1\" toVersion=\"0.2.0\" oldJar=\"lumen-desktop-0.1.1.jar\" newJar=\"lumen-desktop-0.2.0.jar\" buildId=\"build-test\" packageId=\"")
                    .append(packageId).append("\"><files>");
            payload.forEach((name, contents) -> xml.append(fileXml(name, contents)));
            xml.append("</files><requirements>");
            requirements.forEach((name, contents) -> xml.append("<file path=\"").append(name).append("\" sha256=\"")
                    .append(sha(contents)).append("\" size=\"").append(contents.length).append("\"/>"));
            return xml.append("</requirements></update>").toString();
        }
        String fileXml(String name, byte[] contents) {
            boolean root = name.startsWith("payload/root/");
            String path = name.substring(root ? "payload/root/".length() : "payload/".length());
            return "<file scope=\"" + (root ? "root" : "app") + "\" path=\"" + path + "\" sha256=\"" + sha(contents) + "\" size=\"" + contents.length + "\"/>";
        }
        Path zip() throws IOException { return zip("", UnaryOperator.identity(), Map.of()); }
        Path zip(String prefix, UnaryOperator<String> change, Map<String, byte[]> extra) throws IOException {
            Path zip = temporary.resolve("package-" + UUID.randomUUID() + ".zip");
            overwrite(zip, prefix, change, extra); return zip;
        }
        void overwrite(Path zip, String prefix, UnaryOperator<String> change, Map<String, byte[]> extra) throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(prefix + "manifest.xml", bytes(change.apply(manifest())));
            payload.forEach((name, contents) -> entries.put(prefix + name, contents));
            entries.putAll(extra); writeZip(zip, entries);
        }
    }

    private static String config(String version) {
        return "[Application]\napp.classpath=$APPDIR\\lumen-desktop-" + version + ".jar\napp.mainclass=com.lumenmusic.LumenLauncher\n[JavaOptions]\njava-options=-Djpackage.app-version=" + version + "\n";
    }
    private static String image(String version) { return "<jpackage-state><app-version>" + version + "</app-version></jpackage-state>"; }
    private static byte[] jar(String properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("class-placeholder")); zip.write(bytes("class")); zip.closeEntry();
            if (properties != null) { zip.putNextEntry(new ZipEntry("lumen-build.properties")); zip.write(bytes(properties)); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static void writeZip(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
