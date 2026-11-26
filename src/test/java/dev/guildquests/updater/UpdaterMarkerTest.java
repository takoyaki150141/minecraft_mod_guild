package dev.guildquests.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterMarkerTest {
    @TempDir
    Path temp;

    @Test
    void theRestartMarkerLivesInTheStagingDirectory() {
        UpdaterInstall.UpdaterContext context = new UpdaterInstall.UpdaterContext(
                "26.17.5", "26.18.0", "neoforge", "1.21.1",
                "https://example.invalid/mod.jar", 100, "a".repeat(64),
                temp.resolve("mods"), temp.resolve("mods/guildquests_neoforge-1.21.1_26.17.5.jar"),
                "b".repeat(64), temp.resolve("update"), 1L, "2026-01-01T00:00:00Z",
                temp.resolve("updater.jar"));
        assertTrue(UpdaterInstall.restartMarker(context).startsWith(context.stagingDir()));
    }
}
