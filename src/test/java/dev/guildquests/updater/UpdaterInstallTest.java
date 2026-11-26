package dev.guildquests.updater;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterInstallTest {
    private static UpdaterInstall.UpdaterContext context(String version) {
        return new UpdaterInstall.UpdaterContext(
                "26.17.5", version, "neoforge", "1.21.1",
                "https://example.invalid/mod.jar", 100, "a".repeat(64),
                Path.of("mods"), Path.of("mods", "guildquests_neoforge-1.21.1_26.17.5.jar"),
                "b".repeat(64), Path.of("update"), 1L, "2026-01-01T00:00:00Z",
                Path.of("update", "updater.jar"));
    }

    @Test
    void assetNameMatchesTheReleaseConvention() {
        assertEquals("guildquests_neoforge-1.21.1_26.18.0.jar",
                UpdaterInstall.fileName("neoforge", "1.21.1", "26.18.0"));
        assertEquals("guildquests_neoforge-1.21.1_26.18.0.jar", context("26.18.0").fileName());
    }

    @Test
    void aGuildQuestsJarWithPinnedVersionPasses() throws Exception {
        Path jar = jar("""
                modLoader="javafml"
                [[mods]]
                modId="guildquests"
                version="26.18.0"
                """);
        UpdaterInstall.verifyJar(jar, context("26.18.0"));
    }

    @Test
    void theGradlePlaceholderVersionIsAccepted() throws Exception {
        Path jar = jar("""
                modLoader="javafml"
                [[mods]]
                modId="guildquests"
                version="${file.jarVersion}"
                """);
        UpdaterInstall.verifyJar(jar, context("26.18.0"));
    }

    @Test
    void anotherModOrVersionIsRejected() throws Exception {
        Path other = jar("""
                [[mods]]
                modId="othermod"
                version="26.18.0"
                """);
        assertThrows(Exception.class, () -> UpdaterInstall.verifyJar(other, context("26.18.0")));

        Path wrongVersion = jar("""
                [[mods]]
                modId="guildquests"
                version="26.17.5"
                """);
        assertThrows(Exception.class, () -> UpdaterInstall.verifyJar(wrongVersion, context("26.18.0")));
    }

    @Test
    void missingMetadataIsRejected() throws Exception {
        Path jar = Files.createTempFile("guildquests", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("empty".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(Exception.class, () -> UpdaterInstall.verifyJar(jar, context("26.18.0")));
    }

    @Test
    void humanReadableSizes() {
        assertEquals("512 B", UpdaterInstall.human(512));
        assertEquals("2 KB", UpdaterInstall.human(2048));
        assertTrue(UpdaterInstall.human(5 * 1024 * 1024).endsWith("MB"));
    }

    private static Path jar(String metadata) throws Exception {
        Path jar = Files.createTempFile("guildquests", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
