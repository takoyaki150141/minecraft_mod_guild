package dev.guildquests.update;

import dev.guildquests.config.ServerUpdateConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UpdateScheduleTest {
    @Test public void waitsFromFirstDetectionEvenWhenChecksRepeatOrNewerVersionAppears() {
        UpdateSchedule schedule = new UpdateSchedule(null, 0);
        schedule.observe(new ServerReleaseClient.UpdateInfo("26.7.2", "https://example.com/v2.jar"), 1_000_000);
        schedule.observe(new ServerReleaseClient.UpdateInfo("26.7.2", "https://example.com/v2.jar"), 1_100_000);
        schedule.observe(new ServerReleaseClient.UpdateInfo("26.7.3", "https://example.com/v3.jar"), 1_200_000);
        assertEquals(1_000_000, schedule.detectedAt());
        assertEquals(2_800_000, schedule.dueAt(30));
        assertEquals("26.7.3", schedule.update().latestVersion());
        UpdateSchedule restored = new UpdateSchedule(schedule.update(), schedule.detectedAt());
        assertEquals(schedule.dueAt(30), restored.dueAt(30));
        restored.observe(new ServerReleaseClient.UpdateInfo("26.7.2", "https://example.com/v2.jar"), 1_300_000);
        assertEquals("26.7.2", restored.update().latestVersion());
        assertEquals(1_000_000, restored.detectedAt());
    }

    @Test public void configReloadCanChangeDelayAndZeroMeansImmediate() {
        UpdateSchedule schedule = new UpdateSchedule(new ServerReleaseClient.UpdateInfo("26.7.2", "https://example.com/mod.jar"), 5_000);
        assertEquals(5_000, schedule.dueAt(0));
        assertEquals(65_000, schedule.dueAt(1));
        assertEquals(3_605_000, schedule.dueAt(60));
    }

    @Test public void sanitizesTimesAndAllowsDisablingCountdown() {
        ServerUpdateConfig config = new ServerUpdateConfig();
        config.updateDelayMinutes = -1;
        config.checkIntervalMinutes = 0;
        config.retryIntervalMinutes = Integer.MAX_VALUE;
        config.countdownSeconds = new int[]{60, -1, 60, 5};
        config.sanitize();
        assertEquals(0, config.updateDelayMinutes);
        assertEquals(1, config.checkIntervalMinutes);
        assertEquals(1440, config.retryIntervalMinutes);
        assertArrayEquals(new int[]{5, 60}, config.countdownSeconds);
        config.countdownSeconds = null;
        assertEquals(0, config.sanitize().countdownSeconds.length);
    }
}
