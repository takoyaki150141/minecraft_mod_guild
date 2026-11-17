package dev.guildquests.update;

/** A repeated check or a server restart must not reset the first detection time. */
final class UpdateSchedule {
    private ServerReleaseClient.UpdateInfo update;
    private long detectedAt;

    UpdateSchedule(ServerReleaseClient.UpdateInfo update, long detectedAt) {
        this.update = update;
        this.detectedAt = detectedAt;
    }

    void observe(ServerReleaseClient.UpdateInfo candidate, long now) {
        if (update == null) {
            update = candidate;
            detectedAt = now;
        } else {
            // The API may withdraw a release; always use its latest compatible candidate.
            // parseRelease has already rejected versions older than the running Mod.
            update = candidate;
        }
    }

    ServerReleaseClient.UpdateInfo update() { return update; }
    long detectedAt() { return detectedAt; }
    long dueAt(int delayMinutes) { return Math.addExact(detectedAt, delayMinutes * 60_000L); }
}
