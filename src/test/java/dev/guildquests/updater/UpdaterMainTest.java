package dev.guildquests.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdaterMainTest {
    /** The launcher writes the file with Properties.store, which must round-trip Windows paths. */
    @Test
    void readsThePropertiesWrittenByTheLauncher(@TempDir Path dir) throws Exception {
        Path mods = dir.resolve("mods");
        Path staging = dir.resolve("update");
        Path current = mods.resolve("guildquests_neoforge-1.21.1_26.17.5.jar");
        Path self = staging.resolve("guildquests-updater-1.jar");

        Properties p = new Properties();
        p.setProperty("currentVersion", "26.17.5");
        p.setProperty("latestVersion", "26.18.0");
        p.setProperty("loader", "neoforge");
        p.setProperty("minecraftVersion", "1.21.1");
        p.setProperty("assetUrl", "https://example.invalid/a.jar");
        p.setProperty("assetSize", "512");
        p.setProperty("sha256", "a".repeat(64));
        p.setProperty("modsDir", mods.toAbsolutePath().toString());
        p.setProperty("currentJar", current.toAbsolutePath().toString());
        p.setProperty("currentSha256", "b".repeat(64));
        p.setProperty("stagingDir", staging.toAbsolutePath().toString());
        p.setProperty("parentPid", "4242");
        p.setProperty("parentStartedAt", "2026-01-01T00:00:00Z");
        p.setProperty("selfJar", self.toAbsolutePath().toString());

        Path file = dir.resolve("updater.properties");
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "GuildQuests updater");
        }

        UpdaterInstall.UpdaterContext context = UpdaterMain.load(file);
        assertEquals("26.17.5", context.currentVersion());
        assertEquals("26.18.0", context.latestVersion());
        assertEquals(mods.toAbsolutePath(), context.modsDir().toAbsolutePath());
        assertEquals(current.toAbsolutePath(), context.currentJar().toAbsolutePath());
        assertEquals(4242L, context.parentPid());
        assertEquals("guildquests_neoforge-1.21.1_26.18.0.jar", context.fileName());
    }

    @Test
    void aMissingKeyIsReported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("updater.properties");
        Files.writeString(file, "currentVersion=26.17.5\n");
        try {
            UpdaterMain.load(file);
            throw new AssertionError("expected an IOException");
        } catch (java.io.IOException e) {
            assertEquals(true, e.getMessage().contains("latestVersion"));
        }
    }
}
