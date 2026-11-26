package dev.guildquests.updater;

import com.sun.net.httpserver.HttpServer;
import dev.guildquests.update.UpdateInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterDownloadTest {
    @TempDir
    Path temp;

    @Test
    void downloadsVerifiesAndPreparesThePlan() throws Exception {
        Path jar = fakeJar("26.18.0");
        byte[] bytes = Files.readAllBytes(jar);
        String sha = UpdateInstaller.sha256(jar);
        HttpServer server = serve(bytes);
        try {
            UpdaterInstall.UpdaterContext context = context(server, bytes.length, sha, "26.18.0");
            AtomicInteger lastPercent = new AtomicInteger(-1);
            Path staged = UpdaterInstall.download(context, (percent, message) -> lastPercent.set(percent));

            assertTrue(Files.isRegularFile(staged));
            assertEquals(sha, UpdateInstaller.sha256(staged));
            assertEquals(100, lastPercent.get());

            Properties plan = UpdaterInstall.buildPlan(context, staged);
            assertEquals("guildquests_neoforge-1.21.1_26.18.0.jar", plan.getProperty("targetFile"));
            assertEquals("guildquests_neoforge-1.21.1_26.17.5.jar", plan.getProperty("currentFile"));
            assertEquals("neoforge", plan.getProperty("loader"));
            assertEquals(sha, plan.getProperty("sha256"));
            assertTrue(plan.getProperty("targetFile")
                    .matches("guildquests_neoforge-1\\.21\\.1_[0-9]+(?:\\.[0-9]+)*\\.jar"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDigestMismatchRemovesTheDownload() throws Exception {
        byte[] bytes = Files.readAllBytes(fakeJar("26.18.0"));
        HttpServer server = serve(bytes);
        try {
            String wrong = "0".repeat(64);
            UpdaterInstall.UpdaterContext context = context(server, bytes.length, wrong, "26.18.0");
            assertThrows(Exception.class, () -> UpdaterInstall.download(context, (p, m) -> { }));
            assertFalse(Files.exists(staging().resolve(context.fileName())));
            assertFalse(Files.exists(staging().resolve(context.fileName() + ".part")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aJarWithoutTheModMetadataIsRejected() throws Exception {
        Path jar = temp.resolve("not-ours.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("nothing here".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = Files.readAllBytes(jar);
        String sha = UpdateInstaller.sha256(jar);
        HttpServer server = serve(bytes);
        try {
            UpdaterInstall.UpdaterContext context = context(server, bytes.length, sha, "26.18.0");
            assertThrows(Exception.class, () -> UpdaterInstall.download(context, (p, m) -> { }));
            assertFalse(Files.exists(staging().resolve(context.fileName())));
        } finally {
            server.stop(0);
        }
    }

    private UpdaterInstall.UpdaterContext context(HttpServer server, long size, String sha, String latest)
            throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path current = mods.resolve("guildquests_neoforge-1.21.1_26.17.5.jar");
        if (!Files.exists(current)) Files.writeString(current, "old jar");
        Path staging = staging();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/"
                + UpdaterInstall.fileName("neoforge", "1.21.1", latest);
        return new UpdaterInstall.UpdaterContext("26.17.5", latest, "neoforge", "1.21.1",
                url, size, sha, mods, current, UpdateInstaller.sha256(current), staging,
                ProcessHandle.current().pid(), "2026-01-01T00:00:00Z", staging.resolve("updater.jar"));
    }

    private Path staging() throws Exception {
        return Files.createDirectories(temp.resolve("update"));
    }

    private HttpServer serve(byte[] bytes) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private Path fakeJar(String version) throws Exception {
        Path jar = Files.createTempFile(temp, "guildquests", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            out.write(("[[mods]]\nmodId=\"guildquests\"\nversion=\"${file.jarVersion}\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
