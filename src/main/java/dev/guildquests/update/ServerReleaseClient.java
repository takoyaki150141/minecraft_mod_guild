package dev.guildquests.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Loader-neutral dedicated-server release lookup and validated downloads. */
public final class ServerReleaseClient {
    public static final String API_URL =
            "https://api.github.com/repos/takoyaki150141/minecraft_mod_guild/releases/latest";
    public static final long MAX_JAR_BYTES = 100L * 1024 * 1024;

    private ServerReleaseClient() { }

    @FunctionalInterface
    public interface MetadataValidator {
        void validate(ZipFile jar, String version, boolean dedicatedServer) throws IOException;
    }

    public record UpdateInfo(String latestVersion, String downloadUrl, long size, String sha256) {
        public UpdateInfo(String latestVersion, String downloadUrl) {
            this(latestVersion, downloadUrl, -1, null);
        }
    }

    public static UpdateInfo check(String currentVersion, String loader, String apiUrl,
                                   int connectTimeout, int requestTimeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(requireHttpUri(apiUrl))
                .timeout(Duration.ofSeconds(requestTimeout))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GuildQuests-Mod/" + currentVersion).build();
        HttpResponse<String> response = httpClient(connectTimeout).send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("Release API returned HTTP " + response.statusCode());
        try {
            return parseRelease(response.body(), currentVersion, loader);
        } catch (RuntimeException e) {
            throw new IOException("Invalid release response", e);
        }
    }

    static UpdateInfo parseRelease(String json, String currentVersion, String loader) {
        JsonObject release = JsonParser.parseString(json).getAsJsonObject();
        if (isTrue(release, "draft") || isTrue(release, "prerelease")) return null;
        String latest = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
        requireVersion(latest);
        if (compareVersions(currentVersion, latest) >= 0) return null;
        String expected = jarName(loader, latest);
        if (!release.has("assets") || !release.get("assets").isJsonArray()) return null;
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (!expected.equals(asset.get("name").getAsString())) continue;
            String url = requireHttpUri(asset.get("browser_download_url").getAsString()).toString();
            long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
            if (size == 0 || size > MAX_JAR_BYTES || size < -1) throw new IllegalArgumentException("Invalid asset size");
            String digest = null;
            if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
                digest = asset.get("digest").getAsString();
                if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid SHA-256 digest");
                digest = digest.substring(7).toLowerCase(java.util.Locale.ROOT);
            }
            if (digest == null) {
                throw new IllegalArgumentException("Release asset " + expected + " is missing a SHA-256 digest");
            }
            return new UpdateInfo(latest, url, size, digest);
        }
        return null;
    }

    /** Stage outside mods so a failed/partial download can never be loaded by a Mod loader. */
    public static Path downloadToStage(UpdateInfo info, Path updateDir, String loader,
                                       int connectTimeout, int requestTimeout, boolean dedicatedServer, MetadataValidator metadataValidator)
            throws IOException, InterruptedException {
        if (info.sha256() == null || !info.sha256().matches("[0-9a-f]{64}")) {
            throw new IOException("Update asset is missing a SHA-256 digest");
        }
        Files.createDirectories(updateDir);
        Path part = Files.createTempFile(updateDir, "download-", ".part");
        Path staged = updateDir.resolve(jarName(loader, info.latestVersion()) + ".download");
        try {
            HttpRequest request = HttpRequest.newBuilder(requireHttpUri(info.downloadUrl()))
                    .timeout(Duration.ofSeconds(requestTimeout)).build();
            HttpResponse<Path> response = httpClient(connectTimeout).send(request, HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) throw new IOException("Download returned HTTP " + response.statusCode());
            long size = Files.size(part);
            if (size == 0 || size > MAX_JAR_BYTES || (info.size() >= 0 && size != info.size())) {
                throw new IOException("Downloaded JAR size does not match the release asset");
            }
            if (info.sha256() == null || !info.sha256().equalsIgnoreCase(UpdateInstaller.sha256(part))) {
                throw new IOException("Downloaded JAR SHA-256 does not match the release asset");
            }
            validateJar(part, info.latestVersion(), dedicatedServer, metadataValidator);
            Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
            return staged;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static void validateJar(Path path, String version, boolean dedicatedServer, MetadataValidator metadataValidator) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            metadataValidator.validate(zip, version, dedicatedServer);
            if (zip.getEntry("dev/guildquests/GuildQuestsMod.class") == null) throw new IOException("Missing Mod entrypoint");
            var entries = zip.entries();
            long total = 0;
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                CRC32 crc = new CRC32();
                try (InputStream in = zip.getInputStream(entry)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_JAR_BYTES * 4) throw new IOException("Expanded JAR is too large");
                        crc.update(buffer, 0, count);
                    }
                }
                if (entry.getCrc() != crc.getValue()) throw new IOException("Corrupt JAR entry: " + entry.getName());
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid Mod JAR", e);
        }
    }

    static int compareVersions(String a, String b) {
        requireVersion(a);
        requireVersion(b);
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            BigInteger x = i < left.length ? new BigInteger(left[i]) : BigInteger.ZERO;
            BigInteger y = i < right.length ? new BigInteger(right[i]) : BigInteger.ZERO;
            int comparison = x.compareTo(y);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    static String jarName(String loader, String version) {
        requireVersion(version);
        if (!"fabric".equals(loader) && !"forge".equals(loader) && !"neoforge".equals(loader)) throw new IllegalArgumentException("Unsupported loader");
        return "guildquests_" + loader + "-1.21.1_" + version + ".jar";
    }

    private static void requireVersion(String version) {
        if (version == null || version.length() > 80 || !version.matches("[0-9]+(?:\\.[0-9]+)*")) {
            throw new IllegalArgumentException("Expected a stable numeric version: " + version);
        }
    }

    public static URI requireHttpUri(String value) {
        URI uri = URI.create(value);
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null ||
                !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException("Update URLs must use HTTPS (or loopback HTTP)");
        }
        return uri;
    }

    private static HttpClient httpClient(int timeout) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static boolean isTrue(JsonObject object, String key) {
        return object.has(key) && object.get(key).getAsBoolean();
    }
}
