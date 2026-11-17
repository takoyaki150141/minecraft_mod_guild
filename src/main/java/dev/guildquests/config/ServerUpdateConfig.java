package dev.guildquests.config;

import dev.guildquests.update.ServerReleaseClient;

import java.util.Arrays;

/** Stored in config/guildquests/server-update.json; times use real wall-clock time. */
public final class ServerUpdateConfig {
    public boolean enabled = true;
    public boolean checkOnStartup = true;
    public int checkIntervalMinutes = 30;
    public int updateDelayMinutes = 30;
    public int retryIntervalMinutes = 5;
    public boolean shutdownAfterDownload = true;
    public boolean notifyPlayers = true;
    public int[] countdownSeconds = {300, 60, 30, 10, 5, 4, 3, 2, 1};
    public int connectTimeoutSeconds = 10;
    public int requestTimeoutSeconds = 120;
    public String releaseApiUrl = ServerReleaseClient.API_URL;

    public ServerUpdateConfig sanitize() {
        checkIntervalMinutes = Math.max(1, Math.min(10080, checkIntervalMinutes));
        updateDelayMinutes = Math.max(0, Math.min(525600, updateDelayMinutes));
        retryIntervalMinutes = Math.max(1, Math.min(1440, retryIntervalMinutes));
        connectTimeoutSeconds = Math.max(1, Math.min(120, connectTimeoutSeconds));
        requestTimeoutSeconds = Math.max(1, Math.min(600, requestTimeoutSeconds));
        countdownSeconds = countdownSeconds == null ? new int[0] : Arrays.stream(countdownSeconds)
                .filter(n -> n > 0 && n <= 31536000).distinct().sorted().toArray();
        if (releaseApiUrl == null || releaseApiUrl.isBlank()) releaseApiUrl = ServerReleaseClient.API_URL;
        releaseApiUrl = ServerReleaseClient.requireHttpUri(releaseApiUrl.trim()).toString();
        return this;
    }
}
