package dev.guildquests.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UpdateCheckerTest {
    private static final String HASH = "a".repeat(64);

    private static String release(String digest, String flags) {
        return "{\"tag_name\":\"v26.7.2\"," + flags
                + "\"assets\":[{\"name\":\"guildquests_neoforge-1.21.1_26.7.2.jar\",\"size\":123,"
                + (digest == null ? "" : "\"digest\":\"sha256:" + digest + "\",")
                + "\"browser_download_url\":\"https://example.com/mod.jar\"}]}";
    }

    @Test public void parsesDigestSizeAndUrl() {
        UpdateChecker.UpdateInfo info = UpdateChecker.parseRelease(release(HASH, ""), "26.7.1", "neoforge");
        assertEquals("26.7.2", info.latestVersion());
        assertEquals(123, info.size());
        assertEquals(HASH, info.sha256());
        assertEquals("https://example.com/mod.jar", info.downloadUrl());
    }

    @Test public void refusesAssetWithoutDigest() {
        assertNull(UpdateChecker.parseRelease(release(null, ""), "26.7.1", "neoforge"));
    }

    @Test public void refusesMalformedDigest() {
        assertThrows(IllegalArgumentException.class,
                () -> UpdateChecker.parseRelease(release("abc", ""), "26.7.1", "neoforge"));
    }

    @Test public void ignoresCurrentDraftPrereleaseAndOtherLoader() {
        assertNull(UpdateChecker.parseRelease(release(HASH, ""), "26.7.2", "neoforge"));
        assertNull(UpdateChecker.parseRelease(release(HASH, "\"draft\":true,"), "26.7.1", "neoforge"));
        assertNull(UpdateChecker.parseRelease(release(HASH, "\"prerelease\":true,"), "26.7.1", "neoforge"));
        assertNull(UpdateChecker.parseRelease(release(HASH, ""), "26.7.1", "forge"));
    }

    @Test public void rejectsNonHttpsDownloadUrl() {
        String json = "{\"tag_name\":\"v26.7.2\",\"assets\":[{\"name\":\"guildquests_neoforge-1.21.1_26.7.2.jar\","
                + "\"size\":123,\"digest\":\"sha256:" + HASH + "\","
                + "\"browser_download_url\":\"http://example.com/mod.jar\"}]}";
        assertThrows(IllegalArgumentException.class,
                () -> UpdateChecker.parseRelease(json, "26.7.1", "neoforge"));
    }
}
