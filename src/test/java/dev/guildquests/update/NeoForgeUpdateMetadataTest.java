package dev.guildquests.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NeoForgeUpdateMetadataTest {
    @TempDir Path temporary;

    private static final Map<String, String> INSTALLED =
            Map.of("neoforge", "21.1.267", "minecraft", "1.21.1", "java", "21");

    private static final String METADATA = """
            modLoader = "javafml"
            loaderVersion = "[4,)"
            [[mods]]
            modId = "guildquests"
            version = "26.17.2"
            description = '''Valid NeoForge server update.'''
            [[dependencies.guildquests]]
            modId = "neoforge"
            type = "required"
            versionRange = "[21.1.0,)"
            side = "BOTH"
            [[dependencies.guildquests]]
            modId = "minecraft"
            type = "required"
            versionRange = "[1.21.1,1.21.2)"
            side = "BOTH"
            [[dependencies.guildquests]]
            modId = "create"
            type = "optional"
            versionRange = "[6.0.0,)"
            side = "BOTH"
            """;

    private Path jar(String metadata, String entryName, String manifestVersion) throws IOException {
        Path path = Files.createTempFile(temporary, "test-", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (manifestVersion != null) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write(("Manifest-Version: 1.0\r\nImplementation-Version: " + manifestVersion + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private void validate(String metadata, Map<String, String> installed) throws IOException {
        try (ZipFile zip = new ZipFile(jar(metadata, "META-INF/neoforge.mods.toml", null).toFile())) {
            NeoForgeUpdateMetadata.validate(zip, "26.17.2", true, installed);
        }
    }

    @Test
    void acceptsUpdateWithoutOptionalCreate() {
        assertDoesNotThrow(() -> validate(METADATA, INSTALLED));
    }

    @Test
    void requiresTheNeoForgeMetadataFile() throws Exception {
        try (ZipFile zip = new ZipFile(jar("{}", "META-INF/mods.toml", null).toFile())) {
            assertThrows(IOException.class,
                    () -> NeoForgeUpdateMetadata.validate(zip, "26.17.2", true, INSTALLED));
        }
    }

    @Test
    void rejectsAnotherLoaderOrModOrVersion() {
        assertThrows(IOException.class,
                () -> validate(METADATA.replace("modId = \"guildquests\"", "modId = \"othermod\""), INSTALLED));
        assertThrows(IOException.class,
                () -> validate(METADATA.replace("version = \"26.17.2\"", "version = \"26.17.3\""), INSTALLED));
        assertThrows(IOException.class,
                () -> validate(METADATA.replace("javafml", "anotherloader"), INSTALLED));
    }

    @Test
    void rejectsIncompatibleMinecraftAndNeoForge() {
        assertThrows(IOException.class,
                () -> validate(METADATA.replace("[1.21.1,1.21.2)", "[1.22,1.23)"), INSTALLED));
        assertThrows(IOException.class,
                () -> validate(METADATA.replace("[21.1.0,)", "[21.2.0,)"), INSTALLED));
    }

    @Test
    void validatesOptionalDependencyWhenItIsInstalled() {
        assertDoesNotThrow(() -> validate(METADATA,
                Map.of("neoforge", "21.1.267", "minecraft", "1.21.1", "java", "21", "create", "6.0.10")));
        assertThrows(IOException.class, () -> validate(METADATA,
                Map.of("neoforge", "21.1.267", "minecraft", "1.21.1", "java", "21", "create", "5.0.0")));
    }

    @Test
    void rejectsMissingMandatoryDependency() {
        assertThrows(IOException.class, () -> validate(
                METADATA.replace("type = \"optional\"", "type = \"required\""), INSTALLED));
    }

    @Test
    void skipsClientOnlyDependencyOnDedicatedServer() {
        String metadata = METADATA.replace("type = \"optional\"", "type = \"required\"");
        int side = metadata.lastIndexOf("side = \"BOTH\"");
        String adjusted = metadata.substring(0, side) + "side = \"CLIENT\"\n";
        String finalMetadata = adjusted;
        assertDoesNotThrow(() -> validate(finalMetadata, INSTALLED));
    }

    @Test
    void rejectsIncompatibleModWhenInstalled() {
        String metadata = METADATA.replace("type = \"optional\"", "type = \"incompatible\"");
        assertDoesNotThrow(() -> validate(metadata, INSTALLED));
        assertThrows(IOException.class, () -> validate(metadata,
                Map.of("neoforge", "21.1.267", "minecraft", "1.21.1", "java", "21", "create", "6.0.10")));
    }

    @Test
    void rejectsJava21FeatureOnJava17() {
        assertThrows(IOException.class, () -> validate(
                METADATA + "\n[features.guildquests]\njavaVersion = \"[22,)\"\n", INSTALLED));
    }

    @Test
    void resolvesManifestVersionPlaceholder() throws Exception {
        String metadata = METADATA.replace("version = \"26.17.2\"", "version = \"${file.jarVersion}\"");
        try (ZipFile zip = new ZipFile(jar(metadata, "META-INF/neoforge.mods.toml", "26.17.2").toFile())) {
            assertDoesNotThrow(() -> NeoForgeUpdateMetadata.validate(zip, "26.17.2", true, INSTALLED));
        }
        try (ZipFile zip = new ZipFile(jar(metadata, "META-INF/neoforge.mods.toml", "26.17.1").toFile())) {
            assertThrows(IOException.class,
                    () -> NeoForgeUpdateMetadata.validate(zip, "26.17.2", true, INSTALLED));
        }
    }

    @Test
    void parsesTomlInsteadOfMatchingDescriptionText() {
        assertDoesNotThrow(() -> validate(
                METADATA.replace("Valid NeoForge server update.", "modId = \"wrong\"\nversion = \"999.0.0\""), INSTALLED));
        assertThrows(IOException.class, () -> validate("not = [valid toml", INSTALLED));
    }
}
