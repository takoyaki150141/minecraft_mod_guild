package dev.guildquests.updater;

import dev.guildquests.update.UpdateInstaller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Download, verification and hand-off for the standalone updater window.
 * <p>
 * Runs in its own JVM with the copied mod JAR on the classpath, so it may not touch Minecraft
 * classes. Installation is delegated to {@link UpdateInstaller}, which waits for the game
 * process to exit before replacing the JAR.
 */
public final class UpdaterInstall {
    /** Same asset name the release uses. */
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private UpdaterInstall() { }

    public static String fileName(String loader, String minecraftVersion, String version) {
        return "guildquests_" + loader + "-" + minecraftVersion + "_" + version + ".jar";
    }

    /**
     * Downloads the asset next to the staging folder and verifies size, digest and mod metadata.
     *
     * @param progress receives 0..100; may be called from the download thread
     */
    public static Path download(UpdaterContext context, Progress progress) throws Exception {
        if (!SHA256.matcher(context.sha256()).matches()) throw new IOException("リリースの SHA-256 が不正です");
        if (!VERSION.matcher(context.latestVersion()).matches()) throw new IOException("バージョン表記が不正です");
        if (!"neoforge".equals(context.loader()) && !"fabric".equals(context.loader()) && !"forge".equals(context.loader())) {
            throw new IOException("ローダー名が不正です");
        }

        Path staging = context.stagingDir();
        Files.createDirectories(staging);
        Path staged = staging.resolve(context.fileName());
        Path partial = staging.resolve(context.fileName() + ".part");
        Files.deleteIfExists(partial);
        Files.deleteIfExists(staged);
        Files.deleteIfExists(staged.resolveSibling(staged.getFileName() + ".sha256"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(context.assetUrl()))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "GuildQuests-Updater/" + context.currentVersion())
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("ダウンロードに失敗しました（HTTP " + response.statusCode() + "）");
        }
        long total = context.assetSize() > 0 ? context.assetSize() : response.headers()
                .firstValueAsLong("Content-Length").orElse(-1);
        long written = 0;
        progress.update(0, "ダウンロード中…");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                written += count;
                if (written > MAX_BYTES) throw new IOException("ファイルが大きすぎます");
                if (total > 0) progress.update((int) Math.min(99, written * 100 / total),
                        "ダウンロード中… " + human(written) + " / " + human(total));
            }
        }

        if (written <= 0) throw new IOException("空のファイルが返されました");
        if (context.assetSize() > 0 && written != context.assetSize()) {
            Files.deleteIfExists(partial);
            throw new IOException("サイズが一致しません（" + written + " / " + context.assetSize() + "）");
        }

        progress.update(100, "検証中…");
        String actual = UpdateInstaller.sha256(partial);
        if (!context.sha256().equalsIgnoreCase(actual)) {
            Files.deleteIfExists(partial);
            throw new IOException("SHA-256 が一致しません");
        }
        verifyJar(partial, context);
        Files.move(partial, staged, StandardCopyOption.REPLACE_EXISTING);
        return staged;
    }

    /** Minimal metadata check: the JAR must declare the guildquests mod for this release. */
    static void verifyJar(Path jar, UpdaterContext context) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String metadata = readEntry(zip, "META-INF/neoforge.mods.toml");
            if (metadata == null) metadata = readEntry(zip, "fabric.mod.json");
            if (metadata == null) throw new IOException("MOD のメタデータが見つかりません");
            if (!metadata.contains("guildquests")) throw new IOException("別の MOD の JAR です");
            boolean versionKnown = metadata.contains("\"" + context.latestVersion() + "\"")
                    || metadata.contains("'" + context.latestVersion() + "'");
            String manifest = readEntry(zip, "META-INF/MANIFEST.MF");
            boolean manifestVersion = manifest != null
                    && manifest.contains("Implementation-Version: " + context.latestVersion());
            if (!versionKnown && !manifestVersion && !metadata.contains("${file.jarVersion}")) {
                throw new IOException("バージョンが一致しません");
            }
        }
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes the installation plan {@link UpdateInstaller} expects and starts the helper.
     * The helper waits for {@code parentPid} to exit, so the game can keep running.
     *
     * @return the plan file, which the helper deletes once the swap succeeded
     */
    public static Path scheduleInstall(UpdaterContext context, Path staged, Path selfJar) throws IOException {
        Path plan = context.stagingDir().resolve("pending-install-" + System.nanoTime() + ".properties");
        Properties properties = buildPlan(context, staged);
        try (OutputStream out = Files.newOutputStream(plan)) {
            properties.store(out, "GuildQuests update plan");
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        new ProcessBuilder(javaExe.toString(), "-cp", selfJar.toString(),
                UpdateInstaller.class.getName(), plan.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return plan;
    }

    /** Marker the game watches; its appearance asks the game to shut down for the swap. */
    public static Path restartMarker(UpdaterContext context) {
        return context.stagingDir().resolve("restart-request");
    }

    /** Waits for the game process to end. Returns false on timeout. */
    public static boolean waitForProcess(long pid, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Plan handed to {@link UpdateInstaller}; separated out so it can be checked without spawning. */
    static Properties buildPlan(UpdaterContext context, Path staged) throws IOException {
        if (!Files.isRegularFile(context.currentJar())) throw new IOException("現在の MOD JAR が見つかりません");
        if (!SHA256.matcher(context.currentSha256()).matches()) throw new IOException("現在の JAR のハッシュがありません");
        if (!staged.getFileName().toString().equals(context.fileName())) {
            throw new IOException("ステージされたファイル名が一致しません");
        }

        Properties p = new Properties();
        p.setProperty("parentPid", Long.toString(context.parentPid()));
        p.setProperty("parentStartedAt", context.parentStartedAt());
        p.setProperty("modsDir", context.modsDir().toAbsolutePath().normalize().toString());
        p.setProperty("currentFile", context.currentJar().getFileName().toString());
        p.setProperty("targetFile", staged.getFileName().toString());
        p.setProperty("stagedFile", staged.getFileName().toString());
        p.setProperty("backupFile", context.currentJar().getFileName() + ".bak");
        p.setProperty("sha256", context.sha256());
        p.setProperty("currentSha256", context.currentSha256());
        p.setProperty("loader", context.loader());
        return p;
    }

    /** Deletes previously copied updater JARs so they do not accumulate. */
    public static void cleanUpdaterCopies(Path stagingDir) {
        try (var stream = Files.list(stagingDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                String name = path.getFileName().toString();
                if (name.startsWith("guildquests-updater-") && name.endsWith(".jar")) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
        }
    }

    static String human(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /** Values handed from the game to the updater window. */
    public record UpdaterContext(String currentVersion, String latestVersion, String loader,
                                 String minecraftVersion, String assetUrl, long assetSize,
                                 String sha256, Path modsDir, Path currentJar, String currentSha256,
                                 Path stagingDir, long parentPid, String parentStartedAt,
                                 Path selfJar) {
        public String fileName() {
            return UpdaterInstall.fileName(loader, minecraftVersion, latestVersion);
        }

        public String releaseUrl() {
            return "https://github.com/takoyaki150141/minecraft_mod_guild/releases/tag/v" + latestVersion;
        }

        static String now() {
            return Instant.now().toString();
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(int percent, String message);
    }
}
