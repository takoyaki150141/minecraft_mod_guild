package dev.guildquests.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/** Checks GitHub Releases for a newer version and auto-downloads it. */
public final class UpdateChecker {
    private static final String API_URL =
            "https://api.github.com/repos/takoyaki150141/minecraft_mod_guild/releases/latest";

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("guildquests");

    private UpdateChecker() {
    }

    /** Result of a successful update check. */
    public record UpdateInfo(String latestVersion, String downloadUrl, long size, String sha256) {
        public UpdateInfo(String latestVersion, String downloadUrl) {
            this(latestVersion, downloadUrl, -1, null);
        }
    }

    /**
     * Queries GitHub in a background thread. If a newer version exists,
     * invokes onUpdate with the result (on that background thread).
     */
    public static void checkAsync(String currentVersion, String loader, Consumer<UpdateInfo> onUpdate) {
        new Thread(() -> {
            try {
                LOGGER.info("[Update] Checking GitHub for updates... (current={}, loader={})", currentVersion, loader);
                UpdateInfo info = check(currentVersion, loader);
                if (info != null) {
                    LOGGER.info("[Update] Found: v{}", info.latestVersion());
                    if (onUpdate != null) {
                        onUpdate.accept(info);
                    }
                } else {
                    LOGGER.info("[Update] No update found or check failed");
                }
            } catch (Exception e) {
                LOGGER.warn("[Update] Check failed with exception", e);
            }
        }, "GuildQuests-UpdateCheck").start();
    }

    /**
     * Queries GitHub for the latest release. Returns null if up-to-date,
     * on network error, or if no matching JAR asset is found.
     */
    public static UpdateInfo check(String currentVersion, String loader) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GuildQuests-Mod/" + currentVersion)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.info("[Update] GitHub API response: {}", response.statusCode());
            if (response.statusCode() != 200) {
                return null;
            }

            String body = response.body();
            UpdateInfo info = parseRelease(body, currentVersion, loader);
            if (info != null) {
                LOGGER.info("[Update] Found v{} ({} bytes, sha256={})",
                        info.latestVersion(), info.size(), info.sha256());
            } else {
                LOGGER.info("[Update] No usable update asset (release body length={})", body.length());
            }
            return info;
        } catch (Exception e) {
            LOGGER.warn("[Update] Exception during check", e);
            return null;
        }
    }

    /**
     * Parses a GitHub release body. The matching JAR must carry a {@code sha256} digest; an update
     * without one is ignored instead of being downloaded unverified.
     */
    static UpdateInfo parseRelease(String json, String currentVersion, String loader) {
        JsonObject release = JsonParser.parseString(json).getAsJsonObject();
        if (isTrue(release, "draft") || isTrue(release, "prerelease")) return null;
        if (!release.has("tag_name")) return null;

        String tagName = release.get("tag_name").getAsString();
        String latest = tagName.startsWith("v") || tagName.startsWith("V") ? tagName.substring(1) : tagName;
        if (compareVersions(currentVersion, latest) >= 0) return null;

        String jarName = "guildquests_" + loader + "-1.21.1_" + latest + ".jar";
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return null;
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (!asset.has("name") || !jarName.equals(asset.get("name").getAsString())) continue;
            if (!asset.has("browser_download_url")) return null;
            String url = ServerReleaseClient.requireHttpUri(asset.get("browser_download_url").getAsString()).toString();
            long size = asset.has("size") && !asset.get("size").isJsonNull() ? asset.get("size").getAsLong() : -1;
            if (size <= 0 || size > ServerReleaseClient.MAX_JAR_BYTES) {
                throw new IllegalArgumentException("Invalid update asset size");
            }
            if (!asset.has("digest") || asset.get("digest").isJsonNull()) {
                LOGGER.warn("[Update] Release asset {} has no SHA-256 digest; refusing the update", jarName);
                return null;
            }
            String digest = asset.get("digest").getAsString();
            if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid SHA-256 digest");
            }
            return new UpdateInfo(latest, url, size, digest.substring(7).toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static boolean isTrue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    /** Outcome of {@link #download}. */
    public enum DownloadResult {
        /** Installed into the mods folder; a restart applies it. */
        OK,
        /** Downloaded and scheduled to replace the old JAR automatically after the game exits. */
        SCHEDULED,
        /** Downloaded but parked outside mods; the user must move it in manually. */
        STAGED,
        /** Network or I/O failure. */
        FAILED
    }

    /** Targets that already have a post-exit swap scheduled this session. */
    private static final java.util.Set<String> SCHEDULED_SWAPS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Downloads the update JAR and makes it take effect.
     * <p>
     * Both Fabric and Forge lock the running mod JAR while the game is open, so it cannot be
     * replaced in-process; Forge additionally hard-fails when two JARs share a modId. The JAR is
     * therefore always downloaded to a staging folder outside mods and swapped in automatically
     * after the game exits (see {@link #scheduleSwap}). If the running JAR happens not to be
     * locked, it is installed immediately instead.
     */
    public static DownloadResult download(UpdateInfo info, Path modsDir, String loader) {
        try {
            if (info.sha256() == null || !info.sha256().matches("[0-9a-f]{64}")) {
                LOGGER.warn("[Update] Refusing to download {} without a SHA-256 digest", info.latestVersion());
                return DownloadResult.FAILED;
            }
            String fileName = "guildquests_" + loader + "-1.21.1_" + info.latestVersion() + ".jar";
            LOGGER.info("[Update] Download URL: {} (sha256={})", info.downloadUrl(), info.sha256());

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(info.downloadUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            Path stagingDir = dev.guildquests.platform.Platform.configDir().resolve("update");
            java.nio.file.Files.createDirectories(stagingDir);
            Path staged = stagingDir.resolve(fileName);
            Path target = modsDir.resolve(fileName);
            LOGGER.info("[Update] Download start: loader={} mods={} staging={}", loader, modsDir, stagingDir);
            java.nio.file.Files.deleteIfExists(staged); // clear any partial prior attempt
            java.nio.file.Files.deleteIfExists(checksumFile(staged));
            LOGGER.info("[Update] Downloading to staging: {}", staged);

            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(staged));
            long downloaded = java.nio.file.Files.exists(staged) ? java.nio.file.Files.size(staged) : -1;
            LOGGER.info("[Update] Download response: {} ({} bytes)", response.statusCode(), downloaded);
            if (response.statusCode() != 200) {
                deleteStaged(staged);
                LOGGER.warn("[Update] Download failed with status {}; staged file removed", response.statusCode());
                return DownloadResult.FAILED;
            }
            if (downloaded <= 0 || downloaded > ServerReleaseClient.MAX_JAR_BYTES
                    || (info.size() > 0 && downloaded != info.size())) {
                deleteStaged(staged);
                LOGGER.warn("[Update] Downloaded JAR size does not match the release asset; staged file removed");
                return DownloadResult.FAILED;
            }
            String actual = UpdateInstaller.sha256(staged);
            if (!info.sha256().equalsIgnoreCase(actual)) {
                deleteStaged(staged);
                LOGGER.warn("[Update] SHA-256 mismatch (expected {}, got {}); staged file removed",
                        info.sha256(), actual);
                return DownloadResult.FAILED;
            }
            writeChecksum(staged, actual);

            // Verify again immediately before touching the mods folder.
            if (!verify(staged, info.sha256())) {
                deleteStaged(staged);
                LOGGER.warn("[Update] Staged JAR changed after verification; update aborted");
                return DownloadResult.FAILED;
            }

            // If the running JAR is not locked, install right away.
            if (retireExistingJars(modsDir)) {
                try {
                    java.nio.file.Files.move(staged, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    java.nio.file.Files.deleteIfExists(checksumFile(staged));
                    LOGGER.info("[Update] Installed {} into mods (immediate, SHA-256 verified)", fileName);
                    return DownloadResult.OK;
                } catch (Exception e) {
                    LOGGER.warn("[Update] Could not install staged JAR", e);
                }
            }
            // Otherwise swap automatically once the game has exited.
            if (scheduleSwap(modsDir, staged, target, info.sha256())) {
                LOGGER.info("[Update] Update SCHEDULED: {} will replace the old JAR on exit", fileName);
                return DownloadResult.SCHEDULED;
            }
            LOGGER.warn("[Update] Update STAGED only (manual swap needed): {}", staged);
            return DownloadResult.STAGED;
        } catch (Exception e) {
            LOGGER.error("[Update] Download failed", e);
            return DownloadResult.FAILED;
        }
    }

    /**
     * Writes a small helper script and registers a shutdown hook that runs it after the game
     * exits, when the running JAR is no longer locked. The helper removes every guildquests
     * JAR from the mods folder and moves the staged JAR into place.
     */
    public static boolean scheduleSwap(Path modsDir, Path staged, Path target) {
        String expected = readChecksum(staged);
        if (!verify(staged, expected)) {
            LOGGER.warn("[Update] Staged JAR has no valid SHA-256 record; refusing to schedule it");
            return false;
        }
        return scheduleSwap(modsDir, staged, target, expected);
    }

    public static boolean scheduleSwap(Path modsDir, Path staged, Path target, String expectedSha256) {
        if (!verify(staged, expectedSha256)) {
            LOGGER.warn("[Update] Staged JAR does not match the expected SHA-256; refusing to schedule it");
            return false;
        }
        String key = target.toString();
        if (!SCHEDULED_SWAPS.add(key)) {
            return true; // already scheduled this session
        }
        try {
            // Preferred: a small Java helper launched in its own JVM after the game exits
            // (cross-platform, no shell scripts). It re-verifies the hash before moving the file.
            Path selfJar = resolveSelfJar(modsDir);
            if (selfJar != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> spawnJavaHelper(selfJar, modsDir, staged, target, expectedSha256),
                        "GuildQuests-UpdateSwap"));
                LOGGER.info("[Update] Scheduled post-exit Java swap helper (jar={}, sha256={})",
                        selfJar, expectedSha256);
                return true;
            }
            // Fallback: a shell script. The JAR was verified above; the helper JVM path is preferred.
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("win");
            Path script = staged.resolveSibling(windows ? "guildquests-swap.bat" : "guildquests-swap.sh");
            String body = windows ? windowsScript(modsDir, staged, target) : unixScript(modsDir, staged, target);
            java.nio.file.Files.writeString(script, body, java.nio.charset.StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> spawnScript(script, windows), "GuildQuests-UpdateSwap"));
            LOGGER.info("[Update] Scheduled post-exit swap script: {}", script);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Update] Could not schedule swap", e);
            SCHEDULED_SWAPS.remove(key);
            return false;
        }
    }

    /** Locates the JAR containing this class (the running mod JAR), for launching the helper JVM. */
    private static Path resolveSelfJar(Path modsDir) {
        try {
            var cs = UpdateChecker.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path p = Path.of(cs.getLocation().toURI());
                if (java.nio.file.Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        try (var stream = java.nio.file.Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("guildquests_") && name.endsWith(".jar")) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void spawnJavaHelper(Path jar, Path modsDir, Path staged, Path target, String expectedSha256) {
        try {
            // Copy the mod JAR OUT of the mods folder first. The helper JVM would otherwise hold
            // its own classpath JAR (the very one it must delete) open, and Windows would refuse
            // the delete — leaving the update stuck. The copy lives outside mods, so it is never
            // a delete target.
            Path updaterJar = staged.resolveSibling("guildquests-updater-" + System.nanoTime() + ".jar");
            java.nio.file.Files.copy(jar, updaterJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String javaHome = System.getProperty("java.home");
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("win");
            Path javaExe = Path.of(javaHome, "bin", windows ? "javaw.exe" : "java");
            LOGGER.info("[Update] Spawning swap helper: {} -cp {} dev.guildquests.update.UpdateApplier {} {} {} sha256={}",
                    javaExe, updaterJar, modsDir, staged, target, expectedSha256);
            new ProcessBuilder(javaExe.toString(), "-cp", updaterJar.toString(),
                    "dev.guildquests.update.UpdateApplier",
                    modsDir.toString(), staged.toString(), target.toString(), expectedSha256)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception e) {
            LOGGER.warn("[Update] Could not spawn Java swap helper", e);
        }
    }

    private static void spawnScript(Path script, boolean windows) {
        try {
            if (windows) {
                new ProcessBuilder("cmd", "/c", "start", "", "/min", script.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } else {
                new ProcessBuilder("/bin/sh", "-c", "nohup sh '" + script + "' >/dev/null 2>&1 &").start();
            }
        } catch (Exception e) {
            LOGGER.warn("[Update] Could not spawn swap helper", e);
        }
    }

    private static String windowsScript(Path modsDir, Path staged, Path target) {
        return "@echo off\r\n"
                + "setlocal enabledelayedexpansion\r\n"
                + "set \"MODS=" + modsDir + "\"\r\n"
                + "set \"STAGED=" + staged + "\"\r\n"
                + "set \"TARGET=" + target + "\"\r\n"
                + "if not exist \"%STAGED%\" ( del /f /q \"%~f0\" >nul 2>&1 & exit /b )\r\n"
                + "set /a n=0\r\n"
                + ":loop\r\n"
                + "del /f /q \"%MODS%\\guildquests_*.jar\" >nul 2>&1\r\n"
                + "if exist \"%MODS%\\guildquests_*.jar\" (\r\n"
                + "  set /a n+=1\r\n"
                + "  if !n! lss 150 (\r\n"
                + "    ping -n 2 127.0.0.1 >nul\r\n"
                + "    goto loop\r\n"
                + "  )\r\n"
                + ")\r\n"
                + "move /y \"%STAGED%\" \"%TARGET%\" >nul 2>&1\r\n"
                + "del /f /q \"%~f0\" >nul 2>&1\r\n";
    }

    private static String unixScript(Path modsDir, Path staged, Path target) {
        return "#!/bin/sh\n"
                + "MODS='" + modsDir + "'\n"
                + "STAGED='" + staged + "'\n"
                + "TARGET='" + target + "'\n"
                + "[ -f \"$STAGED\" ] || { rm -f \"$0\"; exit 0; }\n"
                + "n=0\n"
                + "while [ $n -lt 150 ]; do\n"
                + "  rm -f \"$MODS\"/guildquests_*.jar 2>/dev/null\n"
                + "  if ! ls \"$MODS\"/guildquests_*.jar >/dev/null 2>&1; then break; fi\n"
                + "  n=$((n+1))\n"
                + "  sleep 1\n"
                + "done\n"
                + "mv -f \"$STAGED\" \"$TARGET\" 2>/dev/null\n"
                + "rm -f \"$0\"\n";
    }

    /**
     * Re-schedules (or cleans up) any staged update JAR left by a previous session, so an
     * update still applies if the game was killed before the post-exit swap could run.
     */
    public static void recoverPendingSwap(Path modsDir, String loader, String currentVersion) {
        if (modsDir == null || currentVersion == null || loader == null) {
            return;
        }
        try {
            Path stagingDir = dev.guildquests.platform.Platform.configDir().resolve("update");
            if (!java.nio.file.Files.isDirectory(stagingDir)) {
                return;
            }
            LOGGER.info("[Update] Recover: scanning {} (current={})", stagingDir, currentVersion);
            // Remove helper copies from previous runs (best-effort; a running helper's copy stays).
            try (var stream = java.nio.file.Files.list(stagingDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("guildquests-updater") && name.endsWith(".jar")) {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            String prefix = "guildquests_" + loader + "-";
            java.util.List<Path> candidates = new java.util.ArrayList<>();
            Path newest = null;
            String newestVer = null;
            try (var stream = java.nio.file.Files.list(stagingDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith(prefix) || !name.endsWith(".jar")) {
                        continue;
                    }
                    String ver = versionFromJarName(name);
                    if (ver == null) {
                        continue;
                    }
                    String checksum = readChecksum(p);
                    if (!verify(p, checksum)) {
                        deleteStaged(p);
                        LOGGER.warn("[Update] Removed staged JAR without a valid SHA-256 record: {}", name);
                        continue;
                    }
                    if (compareVersions(currentVersion, ver) < 0) {
                        candidates.add(p);
                        if (newestVer == null || compareVersions(newestVer, ver) < 0) {
                            newest = p;
                            newestVer = ver;
                        }
                    } else {
                        deleteStaged(p);
                        LOGGER.info("[Update] Removed stale staged JAR: {}", name);
                    }
                }
            }
            if (newest != null) {
                // Keep only the newest staged JAR so a single swap runs at exit.
                for (Path p : candidates) {
                    if (!p.getFileName().toString().equals(newest.getFileName().toString())) {
                        deleteStaged(p);
                    }
                }
                scheduleSwap(modsDir, newest, modsDir.resolve(newest.getFileName().toString()),
                        readChecksum(newest));
                LOGGER.info("[Update] Pending update {} will be applied on exit (SHA-256 verified).",
                        newest.getFileName());
            }
        } catch (Exception ignored) {
        }
    }

    private static String versionFromJarName(String name) {
        int us = name.lastIndexOf('_');
        int dot = name.lastIndexOf(".jar");
        if (us < 0 || dot < 0 || dot <= us) {
            return null;
        }
        return name.substring(us + 1, dot);
    }

    /**
     * Moves every guildquests JAR out of the mods folder (to {@code .jar.old}).
     * Returns true only if all of them moved; a locked (running) JAR makes this fail.
     */
    private static boolean retireExistingJars(Path modsDir) {
        boolean allMoved = true;
        try (var stream = java.nio.file.Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.startsWith("guildquests_") || !name.endsWith(".jar")) {
                    continue;
                }
                try {
                    java.nio.file.Files.move(p, p.resolveSibling(name + ".old"));
                    LOGGER.info("[Update] Moved aside old JAR: {}", name);
                } catch (Exception e) {
                    LOGGER.warn("[Update] Could not move aside {}: {}", name, e.toString());
                    allMoved = false;
                }
            }
        } catch (Exception ignored) {
        }
        return allMoved;
    }

    /**
     * Deletes old guildquests JARs in the mods folder (previous auto-update leftovers).
     * The currently-running JAR (matching keepVersion) is left alone; Fabric/Forge only
     * load the newest version, so older copies are not locked and can be removed directly.
     * Also deletes any leftover .jar.old files from earlier cleanup schemes.
     * Returns the number of files deleted.
     */
    public static int cleanupOldJars(Path modsDir, String keepVersion) {
        if (modsDir == null || keepVersion == null) {
            return 0;
        }
        int removed = 0;
        try (var stream = java.nio.file.Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.startsWith("guildquests_")) {
                    continue;
                }

                // Leftover .old from the previous two-stage scheme
                if (name.endsWith(".jar.old")) {
                    removed += tryDelete(p, name);
                    continue;
                }

                // Old version JAR (not the one we're running) — safe to delete directly
                if (name.endsWith(".jar") && !name.contains(keepVersion)) {
                    removed += tryDelete(p, name);
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    private static int tryDelete(Path p, String name) {
        try {
            java.nio.file.Files.delete(p);
            LOGGER.info("[Update] Deleted old file: {}", name);
            return 1;
        } catch (Exception e) {
            LOGGER.warn("[Update] Could not delete {}: {}", name, e.toString());
            return 0;
        }
    }

    /** Sidecar file holding the SHA-256 of a staged JAR. */
    private static Path checksumFile(Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".sha256");
    }

    private static void writeChecksum(Path staged, String sha256) throws java.io.IOException {
        java.nio.file.Files.writeString(checksumFile(staged), sha256 + "\n", StandardCharsets.UTF_8);
    }

    private static String readChecksum(Path staged) {
        try {
            String value = java.nio.file.Files.readString(checksumFile(staged), StandardCharsets.UTF_8).trim();
            return value.matches("[0-9a-f]{64}") ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean verify(Path file, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) return false;
        try {
            return expectedSha256.equalsIgnoreCase(UpdateInstaller.sha256(file));
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteStaged(Path staged) {
        try {
            java.nio.file.Files.deleteIfExists(staged);
            java.nio.file.Files.deleteIfExists(checksumFile(staged));
        } catch (Exception ignored) {
        }
    }

    /** Compares dotted version strings numerically. Returns negative if a < b. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }
}
