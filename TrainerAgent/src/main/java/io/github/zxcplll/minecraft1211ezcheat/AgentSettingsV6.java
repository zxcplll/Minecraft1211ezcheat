package io.github.zxcplll.minecraft1211ezcheat;

import com.google.gson.Gson;

import java.time.Duration;
import java.time.Instant;

record AgentSettingsV6(
        int schemaVersion,
        long revision,
        double speedMultiplier,
        boolean reachEnabled,
        double interactionDistance,
        boolean flightEnabled,
        boolean noClipEnabled,
        boolean fallProtectionEnabled,
        boolean oreTrackingEnabled,
        int oreType,
        int oreScanRadius,
        boolean entityEspEnabled,
        boolean espPlayers,
        boolean espHostile,
        boolean espPassive,
        boolean espOther,
        int entityEspDistance,
        boolean aimAssistEnabled,
        int aimAssistDistance,
        double brightnessLevel,
        boolean treasureTrackingEnabled,
        boolean sessionActive,
        String updatedAtUtc) {
    private static final Gson GSON = new Gson();

    static AgentSettingsV6 defaults() {
        return new AgentSettingsV6(
                4, 0L, 1.0D, false, 8.0D, false, false, true,
                false, 0, 32,
                false, true, true, true, false, 64,
                 false, 48, 0.0D, false, false, "");
    }

    static AgentSettingsV6 parse(String json) {
        AgentSettingsV6 value = GSON.fromJson(json, AgentSettingsV6.class);
        return value == null ? defaults() : value.sanitized();
    }

    AgentSettingsV6 effective(Duration lease) {
        if (!sessionActive || !hasFreshLease(lease)) {
            AgentSettingsV6 defaults = defaults();
            return defaults.withSession(revision, false, updatedAtUtc);
        }
        return sanitized();
    }

    AgentSettingsV6 withSession(long newRevision, boolean active, String updatedAt) {
        return new AgentSettingsV6(
                4, newRevision, speedMultiplier, reachEnabled, interactionDistance,
                flightEnabled, noClipEnabled, fallProtectionEnabled,
                oreTrackingEnabled, oreType, oreScanRadius,
                entityEspEnabled, espPlayers, espHostile, espPassive, espOther,
                entityEspDistance, aimAssistEnabled, aimAssistDistance, brightnessLevel,
                treasureTrackingEnabled,
                active, updatedAt == null ? "" : updatedAt).sanitized();
    }

    static AgentSettingsV6 fromHookSnapshot(double[] values, boolean active) {
        AgentSettingsV6 defaults = defaults();
        if (values == null || values.length < 19) {
            return defaults;
        }
        return new AgentSettingsV6(
                4,
                Math.max(0L, (long) values[0]),
                values[1],
                values[2] > 0.5D,
                values[3],
                values[4] > 0.5D,
                values[5] > 0.5D,
                values[6] > 0.5D,
                values[7] > 0.5D,
                (int) values[8],
                (int) values[9],
                values[10] > 0.5D,
                values[11] > 0.5D,
                values[12] > 0.5D,
                values[13] > 0.5D,
                values[14] > 0.5D,
                (int) values[15],
                values[16] > 0.5D,
                (int) values[17],
                values[18],
                values.length > 21 && values[21] > 0.5D,
                active,
                Instant.now().toString()).sanitized();
    }

    boolean hasFreshLease(Duration lease) {
        try {
            Instant updated = Instant.parse(updatedAtUtc);
            Duration age = Duration.between(updated, Instant.now());
            return !age.isNegative() && age.compareTo(lease) <= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private AgentSettingsV6 sanitized() {
        double speed = Math.round(clamp(speedMultiplier, 0.5D, 10.0D) * 100.0D) / 100.0D;
        double reach = Math.round(clamp(interactionDistance, 3.0D, 32.0D) * 10.0D) / 10.0D;
        boolean migratedFallProtection = schemaVersion < 3 ? flightEnabled : fallProtectionEnabled;
        return new AgentSettingsV6(
                4,
                Math.max(0L, revision),
                speed,
                reachEnabled,
                reach,
                flightEnabled || noClipEnabled,
                noClipEnabled,
                migratedFallProtection,
                oreTrackingEnabled,
                clamp(oreType, 0, 9),
                clamp(oreScanRadius, 8, 96),
                entityEspEnabled,
                espPlayers,
                espHostile,
                espPassive,
                espOther,
                clamp(entityEspDistance, 8, 512),
                aimAssistEnabled,
                clamp(aimAssistDistance, 4, 256),
                clamp(brightnessLevel, 0.0D, 1.0D),
                treasureTrackingEnabled,
                sessionActive,
                updatedAtUtc == null ? "" : updatedAtUtc);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
