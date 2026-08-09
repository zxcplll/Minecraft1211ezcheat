package io.github.zxcplll.minecraft1211ezcheat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeControllerV6 {
    private static final String RUNTIME_VERSION = "2.0.0";
    private static final Duration LEASE = Duration.ofSeconds(4);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile Instrumentation instrumentation;
    private static volatile Path settingsPath;
    private static volatile Path statusPath;
    private static volatile AgentSettingsV6 settings = AgentSettingsV6.defaults();
    private static volatile long settingsStamp = Long.MIN_VALUE;
    private static volatile String error = "";
    private static Class<?> minecraftClass;
    private static Method getInstance;
    private static Method hasSingleplayerServer;
    private static Field playerField;

    private RuntimeControllerV6() {
    }

    public static void configure(Instrumentation value, Path newSettingsPath, Path newStatusPath) {
        instrumentation = value;
        settingsPath = newSettingsPath;
        statusPath = newStatusPath;
        settingsStamp = Long.MIN_VALUE;
        if (STARTED.compareAndSet(false, true)) {
            Thread worker = new Thread(RuntimeControllerV6::run, "Minecraft1211ezcheat-Agent");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void run() {
        long lastStatusWrite = 0L;
        while (true) {
            try {
                refreshSettings();
                AgentSettingsV6 source = settings;
                boolean active = source.sessionActive() && source.hasFreshLease(LEASE);
                AgentSettingsV6 effective = source.effective(LEASE);
                PlayerSnapshot player = findPlayer();
                configureHooks(player.uuid(), effective, active);

                if (active) {
                    double[] hookValues = AgentHooksV6.configSnapshot();
                    long hookRevision = hookValues.length == 0 ? 0L : (long) hookValues[0];
                    if (hookRevision > source.revision()) {
                        AgentSettingsV6 updated = AgentSettingsV6.fromHookSnapshot(hookValues, true);
                        writeSettings(updated);
                        settings = updated;
                        source = updated;
                        effective = updated.effective(LEASE);
                    }
                }
                error = AgentHooksV6.lastError();

                long now = System.nanoTime();
                if (now - lastStatusWrite >= 1_000_000_000L) {
                    writeStatus(player, effective);
                    lastStatusWrite = now;
                }
            } catch (Throwable throwable) {
                error = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
                writeStatus(PlayerSnapshot.empty(), settings.effective(LEASE));
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void configureHooks(String uuid, AgentSettingsV6 value, boolean active) {
        AgentHooksV6.configure(
                uuid,
                value.revision(),
                value.speedMultiplier(),
                value.reachEnabled(),
                value.interactionDistance(),
                value.flightEnabled(),
                value.noClipEnabled(),
                value.fallProtectionEnabled(),
                value.oreTrackingEnabled(),
                value.oreType(),
                value.oreScanRadius(),
                value.entityEspEnabled(),
                value.espPlayers(),
                value.espHostile(),
                value.espPassive(),
                value.espOther(),
                value.entityEspDistance(),
                value.aimAssistEnabled(),
                value.aimAssistDistance(),
                value.brightnessLevel(),
                value.treasureTrackingEnabled(),
                active);
    }

    private static void refreshSettings() throws IOException {
        Path path = settingsPath;
        if (path == null || !Files.exists(path)) {
            settings = AgentSettingsV6.defaults();
            settingsStamp = Long.MIN_VALUE;
            return;
        }
        long stamp = Files.getLastModifiedTime(path).toMillis();
        if (stamp != settingsStamp) {
            settings = AgentSettingsV6.parse(Files.readString(path, StandardCharsets.UTF_8));
            settingsStamp = stamp;
        }
    }

    private static PlayerSnapshot findPlayer() throws Exception {
        ensureMinecraftBindings();
        if (minecraftClass == null) {
            return PlayerSnapshot.empty();
        }
        Object minecraft = getInstance.invoke(null);
        Object player = playerField.get(minecraft);
        boolean singleplayer = (boolean) hasSingleplayerServer.invoke(minecraft);
        if (player == null) {
            return new PlayerSnapshot(null, null, singleplayer);
        }
        String uuid = String.valueOf(player.getClass().getMethod("getUUID").invoke(player));
        Object nameComponent = player.getClass().getMethod("getName").invoke(player);
        String name = String.valueOf(nameComponent.getClass().getMethod("getString").invoke(nameComponent));
        return new PlayerSnapshot(uuid, name, singleplayer);
    }

    private static void ensureMinecraftBindings() throws Exception {
        if (minecraftClass != null) {
            return;
        }
        Instrumentation value = instrumentation;
        if (value == null) {
            return;
        }
        for (Class<?> loadedClass : value.getAllLoadedClasses()) {
            if (loadedClass.getName().equals("net.minecraft.client.Minecraft")) {
                minecraftClass = loadedClass;
                getInstance = loadedClass.getMethod("getInstance");
                hasSingleplayerServer = loadedClass.getMethod("hasSingleplayerServer");
                playerField = loadedClass.getField("player");
                return;
            }
        }
    }

    private static void writeSettings(AgentSettingsV6 value) throws IOException {
        Path target = settingsPath;
        if (target == null) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "settings.agent.", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
            moveAtomically(temporary, target);
            settingsStamp = Files.getLastModifiedTime(target).toMillis();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeStatus(PlayerSnapshot player, AgentSettingsV6 effective) {
        Path target = statusPath;
        if (target == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("active", true);
        root.addProperty("playerReady", player.uuid() != null);
        root.addProperty("singleplayer", player.singleplayer());
        root.addProperty("processId", ProcessHandle.current().pid());
        root.addProperty("settingsRevision", effective.revision());
        root.add("playerNames", GSON.toJsonTree(player.name() == null ? List.of() : List.of(player.name())));
        root.addProperty("heartbeatUtc", Instant.now().toString());
        root.addProperty("version", RUNTIME_VERSION);
        root.addProperty("error", error);

        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "status.", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ignored) {
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record PlayerSnapshot(String uuid, String name, boolean singleplayer) {
        static PlayerSnapshot empty() {
            return new PlayerSnapshot(null, null, false);
        }
    }
}
