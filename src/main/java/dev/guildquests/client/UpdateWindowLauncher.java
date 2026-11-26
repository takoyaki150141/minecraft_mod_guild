package dev.guildquests.client;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.Platform;
import dev.guildquests.update.UpdateChecker;
import dev.guildquests.update.UpdateInstaller;
import dev.guildquests.updater.UpdaterInstall;
import dev.guildquests.updater.UpdaterMain;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

/**
 * Opens the standalone updater window before the game starts and waits for the player's choice.
 * <p>
 * The window needs the Mod classes but must not keep the mods-folder JAR open, so a copy is
 * written to {@code config/guildquests/update/} and that copy becomes the classpath.
 */
public final class UpdateWindowLauncher {
    private static final String RESTART_MARKER = "restart-request";

    private UpdateWindowLauncher() { }

    /**
     * Shows the updater window and blocks until it closes.
     *
     * @return false when the window could not be started (dev runs, missing JAR, no display)
     */
    public static boolean offer(String currentVersion, String loader, UpdateChecker.UpdateInfo info) {
        Process process;
        Path staging;
        try {
            Path modJar = Platform.getModJar();
            if (modJar == null || !Files.isRegularFile(modJar)) return false;
            if (info.sha256() == null || !info.sha256().matches("[0-9a-f]{64}")) return false;

            staging = Platform.configDir().resolve("update");
            Files.createDirectories(staging);
            UpdaterInstall.cleanUpdaterCopies(staging);
            Path self = staging.resolve("guildquests-updater-" + System.nanoTime() + ".jar");
            Files.copy(modJar, self, StandardCopyOption.REPLACE_EXISTING);

            Properties p = new Properties();
            p.setProperty("currentVersion", currentVersion);
            p.setProperty("latestVersion", info.latestVersion());
            p.setProperty("loader", loader);
            p.setProperty("minecraftVersion", "1.21.1");
            p.setProperty("assetUrl", info.downloadUrl());
            p.setProperty("assetSize", Long.toString(info.size()));
            p.setProperty("sha256", info.sha256());
            p.setProperty("modsDir", Platform.getModsDir().toAbsolutePath().normalize().toString());
            p.setProperty("currentJar", modJar.toAbsolutePath().normalize().toString());
            p.setProperty("currentSha256", UpdateInstaller.sha256(modJar));
            p.setProperty("stagingDir", staging.toAbsolutePath().normalize().toString());
            ProcessHandle current = ProcessHandle.current();
            p.setProperty("parentPid", Long.toString(current.pid()));
            p.setProperty("parentStartedAt", current.info().startInstant().orElse(Instant.now()).toString());
            p.setProperty("selfJar", self.toAbsolutePath().normalize().toString());

            Path props = staging.resolve("updater.properties");
            try (OutputStream out = Files.newOutputStream(props)) {
                p.store(out, "GuildQuests updater");
            }

            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path javaExe = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
            process = new ProcessBuilder(javaExe.toString(), "-cp", self.toString(),
                    UpdaterMain.class.getName(), props.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            GuildQuestsMod.LOGGER.info("[Update] Waiting for the updater window (v{})", info.latestVersion());
        } catch (Exception e) {
            GuildQuestsMod.LOGGER.warn("[Update] Could not open the updater window", e);
            return false;
        }

        // The game must not continue until the player chooses. The window writes the marker
        // when it is about to replace the JAR, which asks this JVM to shut down cleanly.
        Path marker = staging.resolve(RESTART_MARKER);
        try {
            Files.deleteIfExists(marker);
        } catch (Exception ignored) {
        }
        try {
            while (process.isAlive()) {
                if (Files.exists(marker)) {
                    GuildQuestsMod.LOGGER.info("[Update] Restart requested; shutting down for the swap");
                    System.exit(0);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }
}
