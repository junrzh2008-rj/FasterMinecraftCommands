import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Launches the real (modded) Minecraft 1.21.8 Fabric instance that lives in the user's PCL launcher,
 * with the FMCC jar added to an isolated game directory inside this workspace.
 *
 * <p>This exists because the DSH probe only supports Forge 1.20.1; this launcher gives us a genuine
 * 1.21.8 + Sodium/Iris/REI/Carpet environment for compatibility testing.
 *
 * <p>Usage:
 * {@code java -cp gson.jar LaunchModpackClient.java key=value ...}
 * Keys: versionJson, gameDir, assetsDir, javaExe, modsSource, modJar, log, waitFor, waitTimeoutMs,
 *       width, height, killAfterReady, jvmArg (repeatable via jvmArg.1, jvmArg.2...), programArg (same).
 */
public final class LaunchModpackClient {
    private static final String OS_NAME = "windows";
    private static final String OS_ARCH = "x86_64";

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> jvmArgs = new ArrayList<>();
        List<String> programArgs = new ArrayList<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 0) continue;
            String key = arg.substring(0, eq);
            String value = arg.substring(eq + 1);
            if (key.startsWith("jvmArg.")) jvmArgs.add(value);
            else if (key.startsWith("programArg.")) programArgs.add(value);
            else opts.put(key, value);
        }
        Path versionJson = Paths.get(required(opts, "versionJson"));
        Path gameDir = Paths.get(required(opts, "gameDir"));
        Path assetsDir = Paths.get(required(opts, "assetsDir"));
        Path javaExe = Paths.get(required(opts, "javaExe"));
        Path modsSource = opts.containsKey("modsSource") ? Paths.get(opts.get("modsSource")) : null;
        Path modJar = opts.containsKey("modJar") ? Paths.get(opts.get("modJar")) : null;
        Path logFile = Paths.get(required(opts, "log"));
        String waitFor = opts.getOrDefault("waitFor", "");
        long waitTimeoutMs = Long.parseLong(opts.getOrDefault("waitTimeoutMs", "240000"));
        boolean killAfterReady = Boolean.parseBoolean(opts.getOrDefault("killAfterReady", "false"));
        int width = Integer.parseInt(opts.getOrDefault("width", "1280"));
        int height = Integer.parseInt(opts.getOrDefault("height", "720"));

        JsonObject root = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        String versionId = root.get("id").getAsString();
        String mainClass = root.get("mainClass").getAsString();
        String assetIndex = root.has("assetIndex") ? root.getAsJsonObject("assetIndex").get("id").getAsString() : "legacy";
        Path mcRoot = versionJson.getParent().getParent().getParent();
        Path librariesRoot = mcRoot.resolve("libraries");
        Path clientJar = versionJson.getParent().resolve(versionId + ".jar");
        if (!Files.exists(clientJar)) {
            // Fall back to the downloads metadata path if the flattened jar is not next to the json.
            clientJar = versionJson.getParent().resolve(versionId + ".jar");
        }

        List<String> classpath = new ArrayList<>();
        List<Path> nativeJars = new ArrayList<>();
        // Maven-style conflict resolution: one jar per group:artifact, highest version wins.
        Map<String, String[]> resolved = new LinkedHashMap<>();
        Map<String, Path> resolvedPaths = new LinkedHashMap<>();
        int missing = 0;
        JsonArray libraries = root.getAsJsonArray("libraries");
        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            if (!isAllowedOnThisPlatform(lib)) {
                continue;
            }
            JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : null;
            String name = lib.get("name").getAsString();
            Path jar;
            if (downloads != null && downloads.has("artifact")) {
                String relative = downloads.getAsJsonObject("artifact").get("path").getAsString();
                jar = librariesRoot.resolve(relative.replace('/', File.separatorChar));
            } else {
                // PCL writes some Fabric libraries with only maven coordinates.
                jar = librariesRoot.resolve(mavenPath(name));
            }
            if (!Files.exists(jar)) {
                missing++;
                continue;
            }
            boolean nativeJar = name.endsWith(":natives-windows") || name.endsWith(":natives-windows-x86_64");
            if (nativeJar) {
                nativeJars.add(jar);
                continue;
            }
            String[] parts = name.split(":");
            String key = parts[0] + ":" + parts[1];
            String version = parts.length > 2 ? parts[2] : "";
            String[] current = resolved.get(key);
            if (current == null || compareVersions(version, current[0]) > 0) {
                resolved.put(key, new String[] {version, name});
                resolvedPaths.put(key, jar);
            }
        }
        for (String key : resolved.keySet()) {
            classpath.add(resolvedPaths.get(key).toString());
        }
        if (!Files.exists(clientJar)) {
            throw new IllegalStateException("client jar missing: " + clientJar);
        }
        classpath.add(clientJar.toString());

        Path nativesDir = gameDir.resolve("natives");
        Files.createDirectories(nativesDir);
        for (Path nativeJar : nativeJars) {
            try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
                        continue;
                    }
                    Path target = nativesDir.resolve(Paths.get(entry.getName()).getFileName().toString());
                    if (Files.exists(target)) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target);
                    }
                }
            }
        }

        Files.createDirectories(gameDir);
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        if (modsSource != null && Files.isDirectory(modsSource)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsSource, "*.jar")) {
                for (Path source : stream) {
                    linkOrCopy(source, modsDir.resolve(source.getFileName().toString()));
                }
            }
        }
        if (modJar != null && Files.exists(modJar)) {
            linkOrCopy(modJar, modsDir.resolve(modJar.getFileName().toString()));
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.add("-Xmx4G");
        command.add("-Djava.library.path=" + nativesDir);
        command.add("-Dfabric.skipMcProvider=false");
        String zipfsPatch = opts.get("zipfsPatch");
        if (zipfsPatch != null && !zipfsPatch.isBlank()) {
            // Sandbox workaround: Files.isWritable() is false for every path, which turns the JDK
            // zip filesystem read-only; Fabric's tiny-remapper needs to write jars.
            command.add("--patch-module");
            command.add("jdk.zipfs=" + zipfsPatch);
        }
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(mainClass);
        command.add("--username");
        command.add(opts.getOrDefault("username", "Dev"));
        command.add("--version");
        command.add(opts.getOrDefault("versionName", "fmcc-compat"));
        command.add("--gameDir");
        command.add(gameDir.toString());
        command.add("--assetsDir");
        command.add(assetsDir.toString());
        command.add("--assetIndex");
        command.add(assetIndex);
        command.add("--uuid");
        command.add("00000000-0000-0000-0000-000000000000");
        command.add("--accessToken");
        command.add("0");
        command.add("--clientId");
        command.add("0");
        command.add("--xuid");
        command.add("0");
        command.add("--userType");
        command.add("legacy");
        command.add("--versionType");
        command.add("release");
        command.add("--width");
        command.add(String.valueOf(width));
        command.add("--height");
        command.add(String.valueOf(height));
        command.addAll(programArgs);

        Files.createDirectories(logFile.getParent());
        ProcessBuilder builder = new ProcessBuilder(command).directory(gameDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        System.out.println("[launcher] classpath entries=" + classpath.size() + ", native jars=" + nativeJars.size()
                + ", missing=" + missing);
        System.out.println("[launcher] log=" + logFile.toAbsolutePath());
        Process process = builder.start();
        System.out.println("[launcher] pid=" + process.pid());

        if (!waitFor.isEmpty()) {
            long start = System.currentTimeMillis();
            boolean ready = false;
            while (System.currentTimeMillis() - start < waitTimeoutMs) {
                if (Files.exists(logFile)) {
                    String content = Files.readString(logFile, StandardCharsets.UTF_8);
                    if (content.contains(waitFor)) {
                        ready = true;
                        break;
                    }
                    if (process.isAlive() == false) {
                        break;
                    }
                }
                Thread.sleep(500);
            }
            System.out.println("[launcher] ready=" + ready);
            if (!ready) {
                System.out.println("[launcher] log tail:");
                System.out.println(tail(logFile, 40));
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                System.exit(2);
            }
            if (killAfterReady) {
                process.destroy();
                process.waitFor();
                System.out.println("[launcher] killed after ready marker");
                System.exit(0);
            }
        }
        int exit = process.waitFor();
        System.out.println("[launcher] exit=" + exit);
        System.out.println("[launcher] log tail:");
        System.out.println(tail(logFile, 30));
        System.exit(exit);
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+_]");
        String[] pb = b.split("[.\\-+_]");
        int length = Math.max(pa.length, pb.length);
        for (int i = 0; i < length; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int result;
            try {
                result = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } catch (NumberFormatException e) {
                result = sa.compareTo(sb);
            }
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String mavenPath(String name) {
        String[] parts = name.split(":");
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing argument " + key);
        }
        return value;
    }

    private static boolean isAllowedOnThisPlatform(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement ruleElement : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleElement.getAsJsonObject();
            boolean matches = matchesRule(rule);
            if (matches) {
                String action = rule.get("action").getAsString();
                allowed = "allow".equals(action);
            }
        }
        return allowed;
    }

    private static boolean matchesRule(JsonObject rule) {
        if (rule.has("os")) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !OS_NAME.equals(os.get("name").getAsString().replace("osx", "macos"))) {
                return false;
            }
            if (os.has("arch") && !OS_ARCH.equals(os.get("arch").getAsString())) {
                return false;
            }
        }
        return true;
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            Files.delete(target);
        }
        try {
            Files.createLink(target, source);
        } catch (Exception linkFailure) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String tail(Path file, int lines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (IOException e) {
            return "(could not read log: " + e + ")";
        }
    }
}
