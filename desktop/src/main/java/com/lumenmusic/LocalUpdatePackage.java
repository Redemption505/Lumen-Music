package com.lumenmusic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Validates and stages a local update; only the bundled helper may replace installed files. */
final class LocalUpdatePackage {
    private static final long MAX_FILE = 256L * 1024 * 1024;
    private static final long MAX_PAYLOAD = 512L * 1024 * 1024;
    private static final int MAX_XML = 1024 * 1024;
    private static final int MAX_BUILD = 8192;
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<String> RESULT_STATES = Set.of("success", "already_current", "failed_unchanged", "rolled_back", "rollback_failed");

    record FileEntry(String scope, String path, String sha256, long size) {
        String target() { return scope.equals("root") ? path : "app/" + path; }
        String payload() { return "payload/" + (scope.equals("root") ? "root/" : "") + path; }
    }
    record Requirement(String path, String sha256, long size) { }
    record Manifest(String fromVersion, String toVersion, String oldJar, String newJar,
                    String buildId, String packageId, List<FileEntry> files, List<Requirement> requirements) {
        Manifest { files = List.copyOf(files); requirements = List.copyOf(requirements); }
    }
    record Inspection(Path zip, Path installRoot, String prefix, Manifest manifest) { }
    record Prepared(Inspection inspection, Path stageRoot) { }
    record Result(String status, String fromVersion, String toVersion, String buildId, Path backupPath, String message) { }

    static Optional<Path> installationRoot() {
        try {
            Path code = Path.of(LocalUpdatePackage.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(code) || !code.getFileName().toString().endsWith(".jar")) return Optional.empty();
            Path app = code.toRealPath().getParent();
            if (!app.getFileName().toString().equalsIgnoreCase("app")) return Optional.empty();
            Path root = app.getParent();
            checkInstallation(root);
            return Optional.of(root);
        } catch (Exception error) { return Optional.empty(); }
    }

    static Inspection inspect(Path zip, Path installRoot, String currentVersion) throws IOException {
        Path source = zip.toAbsolutePath().normalize();
        Path root = installRoot.toRealPath();
        checkInstallation(root);
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_PAYLOAD) throw invalid("更新 ZIP 文件无效或超过 512 MiB");
        try (ZipFile archive = new ZipFile(source.toFile())) {
            Map<String, ZipEntry> entries = inventory(archive);
            List<ZipEntry> manifests = entries.values().stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().toLowerCase(Locale.ROOT).matches("(?:[^/]+/)?manifest\\.xml")).toList();
            if (manifests.size() != 1) throw invalid("更新包必须有且只有一个根清单或单层发布目录清单");
            ZipEntry manifestEntry = manifests.get(0);
            String name = manifestEntry.getName();
            String prefix = name.contains("/") ? name.substring(0, name.indexOf('/') + 1) : "";
            Manifest manifest = readManifest(read(archive, manifestEntry, MAX_XML));
            if (!manifest.fromVersion().equals(currentVersion)) throw invalid("更新包不适用于当前版本 " + currentVersion);
            validateConfig(Files.readString(root.resolve("app/LumenMusic.cfg")), manifest.oldJar(), manifest.fromVersion(), manifest.requirements());
            Path oldMarker = root.resolve("app/.jpackage.xml");
            if (Files.isRegularFile(oldMarker)) validateImage(readFile(requireFile(root, "app/.jpackage.xml"), MAX_XML), manifest.fromVersion());
            requireFile(root, "app/" + manifest.oldJar());
            for (Requirement requirement : manifest.requirements()) {
                verifyFile(requireFile(root, requirement.path()), requirement.sha256(), requirement.size());
            }
            for (FileEntry file : manifest.files()) {
                safeTarget(root, file.target());
                ZipEntry entry = entry(entries, prefix + file.payload());
                if (entry.getSize() != file.size()) throw invalid("更新载荷大小不符：" + file.path());
                try (InputStream input = archive.getInputStream(entry)) { verify(input, null, file.sha256(), file.size()); }
            }
            validateConfig(new String(read(archive, entry(entries, prefix + "payload/LumenMusic.cfg"), MAX_XML),
                    java.nio.charset.StandardCharsets.UTF_8), manifest.newJar(), manifest.toVersion(), manifest.requirements());
            validateImage(read(archive, entry(entries, prefix + "payload/.jpackage.xml"), MAX_XML), manifest.toVersion());
            try (InputStream input = archive.getInputStream(entry(entries, prefix + "payload/" + manifest.newJar()))) {
                validateBuild(input, manifest);
            }
            return new Inspection(source, root, prefix, manifest);
        }
    }

    static Prepared stage(Inspection inspection, Path stagingParent) throws IOException {
        Inspection fresh = inspect(inspection.zip(), inspection.installRoot(), inspection.manifest().fromVersion());
        if (!fresh.manifest().equals(inspection.manifest()) || !fresh.prefix().equals(inspection.prefix()))
            throw invalid("更新包在确认后发生变化，请重新检查");
        Path parent = stagingParent.toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, "update-");
        try (ZipFile archive = new ZipFile(fresh.zip().toFile())) {
            Map<String, ZipEntry> entries = inventory(archive);
            byte[] manifest = read(archive, entry(entries, fresh.prefix() + "manifest.xml"), MAX_XML);
            if (!readManifest(manifest).equals(fresh.manifest())) throw invalid("更新包清单已改变");
            Files.write(stage.resolve("manifest.xml"), manifest);
            for (FileEntry file : fresh.manifest().files()) {
                Path target = stage.resolve(file.payload());
                Files.createDirectories(target.getParent());
                try (InputStream input = archive.getInputStream(entry(entries, fresh.prefix() + file.payload()));
                     OutputStream output = Files.newOutputStream(target)) {
                    verify(input, output, file.sha256(), file.size());
                }
            }
            verifyStaged(fresh, stage);
            return new Prepared(fresh, stage);
        } catch (IOException | RuntimeException error) {
            try { removeStage(stage); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static void launch(Prepared prepared, long parentPid, boolean restart) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) throw invalid("本地更新仅支持 Windows");
        if (parentPid <= 0) throw invalid("更新等待进程无效");
        verifyStaged(prepared.inspection(), prepared.stageRoot());
        Path helper = prepared.stageRoot().resolve("apply-local-update.ps1");
        try (InputStream input = LocalUpdatePackage.class.getResourceAsStream("/update/apply-local-update.ps1")) {
            if (input == null) throw invalid("当前程序缺少内置更新工具");
            Files.copy(input, helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        String windows = System.getenv("SystemRoot");
        if (windows == null || windows.isBlank()) throw invalid("找不到 Windows 系统目录");
        Path powershell = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) throw invalid("找不到系统 PowerShell");
        List<String> command = new ArrayList<>(List.of(powershell.toString(), "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", helper.toString(),
                "-InstallRoot", prepared.inspection().installRoot().toString(), "-PackageRoot", prepared.stageRoot().toString(),
                "-ParentPid", Long.toString(parentPid), "-ResultPath", prepared.stageRoot().resolve("result.xml").toString()));
        if (restart) command.add("-Restart");
        new ProcessBuilder(command).directory(prepared.stageRoot().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(prepared.stageRoot().resolve("update.log").toFile())).start();
    }

    static Optional<Result> latestResult(Path stagingParent) throws IOException {
        if (!Files.isDirectory(stagingParent)) return Optional.empty();
        List<Path> reports;
        try (var directories = Files.list(stagingParent)) {
            reports = directories.filter(Files::isDirectory).map(path -> path.resolve("result.xml"))
                    .filter(Files::isRegularFile).toList();
        }
        Path latest = null;
        for (Path report : reports) {
            if (latest == null || Files.getLastModifiedTime(report).compareTo(Files.getLastModifiedTime(latest)) > 0) latest = report;
        }
        if (latest == null) return Optional.empty();
        if (Files.size(latest) > MAX_XML) throw invalid("更新结果文件过大");
        Element result = xml(Files.readAllBytes(latest));
        if (!result.getTagName().equals("result") || !RESULT_STATES.contains(result.getAttribute("status")))
            throw invalid("更新结果格式无效");
        String backup = result.getAttribute("backupPath");
        return Optional.of(new Result(result.getAttribute("status"), result.getAttribute("fromVersion"),
                result.getAttribute("toVersion"), result.getAttribute("buildId"),
                backup.isBlank() ? null : Path.of(backup), result.getAttribute("message")));
    }

    private static Manifest readManifest(byte[] bytes) throws IOException {
        Element root = xml(bytes);
        attributes(root, Set.of("format", "app", "platform", "arch", "fromVersion", "toVersion", "oldJar", "newJar", "buildId", "packageId"));
        if (!root.getTagName().equals("update") || !root.getAttribute("format").equals("1")
                || !root.getAttribute("app").equals("LumenMusic") || !root.getAttribute("platform").equals("windows")
                || !root.getAttribute("arch").equals("x64")) throw invalid("更新包应用、格式或平台不匹配");
        String from = root.getAttribute("fromVersion"), to = root.getAttribute("toVersion");
        if (compareVersions(to, from) <= 0) throw invalid("目标版本必须高于来源版本");
        String oldJar = "lumen-desktop-" + from + ".jar", newJar = "lumen-desktop-" + to + ".jar";
        if (!root.getAttribute("oldJar").equals(oldJar) || !root.getAttribute("newJar").equals(newJar)) throw invalid("更新包主 JAR 名称与版本不一致");
        String build = root.getAttribute("buildId"), packageId = root.getAttribute("packageId");
        if (!BUILD_ID.matcher(build).matches()) throw invalid("更新构建标识无效");
        try { if (!UUID.fromString(packageId).toString().equals(packageId)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw invalid("更新包身份无效"); }
        Element filesNode = null, requirementsNode = null;
        for (Element child : children(root)) {
            if (child.getTagName().equals("files") && filesNode == null) filesNode = child;
            else if (child.getTagName().equals("requirements") && requirementsNode == null) requirementsNode = child;
            else throw invalid("更新清单包含未知或重复元素");
        }
        if (filesNode == null || requirementsNode == null) throw invalid("更新清单缺少载荷或运行时要求");
        attributes(filesNode, Set.of()); attributes(requirementsNode, Set.of());
        List<FileEntry> files = new ArrayList<>();
        List<Requirement> requirements = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        long total = 0;
        for (Element node : children(filesNode)) {
            attributes(node, Set.of("scope", "path", "sha256", "size"));
            leafFile(node);
            String scope = node.getAttribute("scope"), path = node.getAttribute("path");
            safeName(path);
            if (!(scope.equals("root") && (path.equals("LumenMusic.exe") || path.equals("runtime/lib/modules")))
                    && !(scope.equals("app") && (path.equals(newJar) || path.equals("LumenMusic.cfg")
                    || path.equals(".jpackage.xml") || path.equals("stream-proxy/lumen-stream-proxy.exe")
                    || path.matches("service/lumen_service/[A-Za-z_][A-Za-z_0-9]*\\.py")))) throw invalid("更新目标不属于程序文件：" + path);
            FileEntry file = new FileEntry(scope, path, hash(node), size(node));
            if (!targets.add(lower(file.target()))) throw invalid("更新目标重复：" + path);
            if (file.size() > MAX_FILE || (total += file.size()) > MAX_PAYLOAD || files.size() >= 1024)
                throw invalid("更新载荷超过允许大小或文件数量");
            files.add(file);
        }
        for (String required : List.of("LumenMusic.exe", "app/" + newJar, "app/LumenMusic.cfg", "app/.jpackage.xml",
                "app/stream-proxy/lumen-stream-proxy.exe", "app/service/lumen_service/__main__.py", "app/service/lumen_service/__init__.py")) {
            if (!targets.contains(lower(required))) throw invalid("更新清单缺少必要程序文件：" + required);
        }
        Set<String> requiredPaths = new HashSet<>();
        for (Element node : children(requirementsNode)) {
            attributes(node, Set.of("path", "sha256", "size")); leafFile(node);
            String path = node.getAttribute("path"); safeName(path);
            if (!(path.startsWith("runtime/") || path.startsWith("app/python/") || path.startsWith("app/vlc/")
                    || path.matches("app/[A-Za-z0-9_.-]+\\.jar")) || lower(path).equals(lower("app/" + oldJar))
                    || targets.contains(lower(path))) throw invalid("保留运行时路径无效：" + path);
            if (!requiredPaths.add(lower(path))) throw invalid("运行时要求重复：" + path);
            if (requirements.size() >= 8192) throw invalid("运行时要求过多");
            long bytesRequired = size(node);
            if (bytesRequired > MAX_FILE) throw invalid("运行时要求文件大小超限：" + path);
            requirements.add(new Requirement(path, hash(node), bytesRequired));
        }
        if (requirements.isEmpty()) throw invalid("更新清单缺少保留运行时要求");
        return new Manifest(from, to, oldJar, newJar, build, packageId, files, requirements);
    }

    private static void verifyStaged(Inspection inspection, Path stage) throws IOException {
        Path manifest = requireFile(stage, "manifest.xml");
        if (Files.size(manifest) > MAX_XML || !readManifest(Files.readAllBytes(manifest)).equals(inspection.manifest()))
            throw invalid("暂存更新清单无效或已改变");
        for (FileEntry file : inspection.manifest().files()) verifyFile(requireFile(stage, file.payload()), file.sha256(), file.size());
        for (Requirement requirement : inspection.manifest().requirements())
            verifyFile(requireFile(inspection.installRoot(), requirement.path()), requirement.sha256(), requirement.size());
        try (InputStream input = Files.newInputStream(stage.resolve("payload/" + inspection.manifest().newJar()))) {
            validateBuild(input, inspection.manifest());
        }
        validateConfig(Files.readString(stage.resolve("payload/LumenMusic.cfg")), inspection.manifest().newJar(), inspection.manifest().toVersion(), inspection.manifest().requirements());
        validateImage(readFile(stage.resolve("payload/.jpackage.xml"), MAX_XML), inspection.manifest().toVersion());
    }

    private static void validateBuild(InputStream input, Manifest manifest) throws IOException {
        boolean found = false;
        long expanded = 0;
        try (ZipInputStream jar = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = jar.getNextEntry()) != null) {
                boolean metadata = entry.getName().equals("lumen-build.properties");
                ByteArrayOutputStream properties = metadata ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = jar.read(buffer)) >= 0) {
                    if ((expanded += read) > MAX_PAYLOAD) throw invalid("主 JAR 解压大小超限");
                    if (metadata) {
                        if (properties.size() + read > MAX_BUILD) throw invalid("主 JAR 构建属性过大");
                        properties.write(buffer, 0, read);
                    }
                }
                if (metadata) {
                    if (found) throw invalid("主 JAR 构建属性重复");
                    found = true;
                    Map<String, String> values = new HashMap<>();
                    Pattern property = Pattern.compile("^\\s*(version|build\\.id)\\s*=\\s*(.*?)\\s*$");
                    for (String line : properties.toString(java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                        var match = property.matcher(line);
                        if (match.matches() && values.putIfAbsent(match.group(1), match.group(2)) != null)
                            throw invalid("主 JAR 构建属性重复");
                    }
                    if (!manifest.toVersion().equals(values.get("version")) || !manifest.buildId().equals(values.get("build.id")))
                        throw invalid("主 JAR 版本或构建标识与更新清单不一致");
                }
            }
        }
        if (!found) throw invalid("主 JAR 缺少构建属性");
    }

    private static void validateConfig(String config, String jar, String version, List<Requirement> requirements) throws IOException {
        int found = 0, main = 0, versionProperties = 0;
        Set<String> dependencies = new HashSet<>();
        for (Requirement requirement : requirements) dependencies.add(lower(requirement.path()));
        for (String line : config.split("\\R")) {
            String value = line;
            if (value.equals("app.mainclass=com.lumenmusic.LumenLauncher")) main++;
            if (value.startsWith("app.classpath=")) {
                if (!value.startsWith("app.classpath=$APPDIR\\")) throw invalid("启动配置 classpath 路径无效");
                String relative = value.substring("app.classpath=$APPDIR\\".length()).replace('\\', '/');
                safeName(relative);
                if (relative.startsWith("lumen-desktop-")) {
                    if (!relative.equals(jar)) throw invalid("启动配置主 JAR 与版本不一致");
                    found++;
                } else if (!dependencies.contains(lower("app/" + relative))) {
                    throw invalid("启动配置依赖未列入运行时要求：" + relative);
                }
            }
            if (value.startsWith("java-options=-Djpackage.app-version=")) {
                if (!value.equals("java-options=-Djpackage.app-version=" + version)) throw invalid("启动配置版本属性不一致");
                versionProperties++;
            }
        }
        if (found != 1 || main != 1 || versionProperties != 1) throw invalid("启动配置必须包含唯一的主 JAR、入口和版本属性");
    }

    private static void validateImage(byte[] bytes, String version) throws IOException {
        Element marker = xml(bytes);
        List<Element> versions = children(marker).stream().filter(child -> child.getTagName().equals("app-version")).toList();
        if (!marker.getTagName().equals("jpackage-state") || versions.size() != 1 || !versions.get(0).getTextContent().equals(version))
            throw invalid("安装镜像版本标记不一致");
    }

    private static void checkInstallation(Path root) throws IOException {
        requireFile(root, "LumenMusic.exe"); requireFile(root, "app/LumenMusic.cfg");
        if (!Files.isRegularFile(root.resolve("app/.package")) && !Files.isRegularFile(root.resolve("app/.jpackage.xml")))
            throw invalid("目标不是有效的 Lumen Music 安装目录");
    }

    private static Path requireFile(Path root, String relative) throws IOException {
        Path file = safeTarget(root, relative);
        if (!Files.isRegularFile(file)) throw invalid("缺少更新或安装文件：" + relative);
        return file;
    }

    private static Path safeTarget(Path root, String relative) throws IOException {
        safeName(relative);
        Path boundary = root.toRealPath();
        Path target = boundary.resolve(relative).normalize();
        if (!target.startsWith(boundary)) throw invalid("更新路径超出目录");
        Path existing = target;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(boundary) || Files.isSymbolicLink(existing)) throw invalid("更新路径经过目录外链接");
        return target;
    }

    private static void safeName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(value -> "<>\"|?*".indexOf(value) >= 0)) throw invalid("更新路径无效：" + name);
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(value -> value < 32) || part.matches("(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                throw invalid("更新路径无效：" + name);
        }
    }

    private static Map<String, ZipEntry> inventory(ZipFile archive) throws IOException {
        Map<String, ZipEntry> entries = new HashMap<>();
        Enumeration<? extends ZipEntry> iterator = archive.entries();
        while (iterator.hasMoreElements()) {
            ZipEntry entry = iterator.nextElement();
            String name = entry.getName();
            safeName(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
            String key = lower(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
            if (entries.putIfAbsent(key, entry) != null) throw invalid("ZIP 包含大小写重复路径：" + name);
            if (entries.size() > 20000) throw invalid("ZIP 文件条目过多");
        }
        return entries;
    }

    private static ZipEntry entry(Map<String, ZipEntry> entries, String name) throws IOException {
        ZipEntry entry = entries.get(lower(name));
        if (entry == null || entry.isDirectory()) throw invalid("更新 ZIP 缺少载荷：" + name);
        return entry;
    }

    private static byte[] read(ZipFile archive, ZipEntry entry, int limit) throws IOException {
        if (entry.getSize() > limit) throw invalid("更新清单或配置大小超限");
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw invalid("更新清单或配置大小超限");
            return bytes;
        }
    }

    private static byte[] readFile(Path file, int limit) throws IOException {
        if (Files.size(file) > limit) throw invalid("更新配置或版本标记大小超限");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw invalid("更新配置或版本标记大小超限");
            return bytes;
        }
    }

    private static void verifyFile(Path path, String hash, long size) throws IOException {
        if (Files.size(path) != size) throw invalid("文件大小校验失败：" + path.getFileName());
        try (InputStream input = Files.newInputStream(path)) { verify(input, null, hash, size); }
    }

    private static void verify(InputStream input, OutputStream output, String expected, long size) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException(error); }
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if ((count += read) > size) throw invalid("文件解压大小超过清单声明");
            digest.update(buffer, 0, read);
            if (output != null) output.write(buffer, 0, read);
        }
        if (count != size || !HexFormat.of().formatHex(digest.digest()).equals(expected)) throw invalid("文件 SHA-256 或大小校验失败");
    }

    private static Element xml(byte[] bytes) throws IOException {
        if (bytes.length > MAX_XML) throw invalid("更新 XML 过大");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception error) { throw new IOException("更新 XML 格式无效", error); }
    }

    private static void attributes(Element element, Set<String> allowed) throws IOException {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            if (!allowed.contains(element.getAttributes().item(index).getNodeName())) throw invalid("更新 XML 包含未知属性");
        }
        for (String name : allowed) if (!element.hasAttribute(name)) throw invalid("更新 XML 缺少属性：" + name);
    }

    private static List<Element> children(Element parent) throws IOException {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) children.add(element);
            else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) && !node.getTextContent().isBlank())
                throw invalid("更新 XML 包含意外文本");
        }
        return children;
    }

    private static void leafFile(Element node) throws IOException {
        if (!node.getTagName().equals("file") || !children(node).isEmpty()) throw invalid("更新 XML 需要不含子元素的 file");
    }
    private static String hash(Element node) throws IOException {
        String value = node.getAttribute("sha256");
        if (!value.matches("[0-9a-f]{64}")) throw invalid("SHA-256 格式无效");
        return value;
    }
    private static long size(Element node) throws IOException {
        String value = node.getAttribute("size");
        try {
            if (!value.matches("(?:0|[1-9]\\d*)")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException error) { throw invalid("文件大小格式无效"); }
    }
    private static int compareVersions(String left, String right) throws IOException {
        if (!left.matches("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)")
                || !right.matches("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)")) throw invalid("版本号格式无效");
        String[] first = left.split("\\."), second = right.split("\\.");
        for (int index = 0; index < first.length; index++) {
            int order = new java.math.BigInteger(first[index]).compareTo(new java.math.BigInteger(second[index]));
            if (order != 0) return order;
        }
        return 0;
    }
    private static void removeStage(Path stage) throws IOException {
        try (var files = Files.walk(stage)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
    private static IOException invalid(String message) { return new IOException(message); }
    private LocalUpdatePackage() { }
}
