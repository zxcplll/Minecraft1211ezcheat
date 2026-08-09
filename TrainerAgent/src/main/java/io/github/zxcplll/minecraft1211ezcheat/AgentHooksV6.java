package io.github.zxcplll.minecraft1211ezcheat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;

public final class AgentHooksV6 {
    private static final double EPSILON = 0.0001D;
    private static final int ENTITY_STRIDE = 19;
    private static final int MAX_ENTITIES = 256;
    private static final int MAX_CHESTS = 1024;
    private static final int ORE_READ_BUDGET = 3000;
    private static final long ORE_TIME_BUDGET_NANOS = 2_500_000L;
    private static final Map<ClassLoader, MovementBindings> MOVEMENT_BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClassLoader, ClientBindings> CLIENT_BINDINGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, PlayerState> PLAYER_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Method> UUID_METHODS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile String targetUuid;
    private static volatile Settings settings = Settings.disabled();
    private static volatile boolean sessionActive;
    private static volatile boolean ctrlDown;
    private static volatile boolean menuVisible;
    private static volatile boolean previousMenuVisible;
    private static volatile ClassLoader gameLoader;
    private static volatile Object targetPlayer;
    private static volatile Object aimTarget;
    private static volatile FrameData frame = FrameData.empty();
    private static volatile String error = "";
    private static volatile String movementError = "";

    private AgentHooksV6() {
    }

    public static void loadNative(Path libraryPath) {
        NativeOverlayBridgeV6.load(libraryPath);
    }

    public static void configure(
            String uuid,
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
            boolean active) {
        if (targetUuid == null ? uuid != null : !targetUuid.equals(uuid)) {
            targetPlayer = null;
            aimTarget = null;
        }
        targetUuid = uuid;
        sessionActive = active;
        Settings incoming = new Settings(
                Math.max(0L, revision),
                clamp(speedMultiplier, 0.5D, 10.0D),
                reachEnabled,
                clamp(interactionDistance, 3.0D, 32.0D),
                flightEnabled || noClipEnabled,
                noClipEnabled,
                fallProtectionEnabled,
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
                treasureTrackingEnabled);
        Settings current = settings;
        if (!active || incoming.revision() >= current.revision()) {
            settings = incoming;
            if (NativeOverlayBridgeV6.isLoaded()) {
                NativeOverlayBridgeV6.configure(incoming.toNativeArray());
            }
        }
    }

    public static double[] configSnapshot() {
        return settings.toNativeArray();
    }

    public static String lastError() {
        return movementError.isEmpty() ? error : movementError;
    }

    public static boolean shouldIgnoreCollision(Object entity) {
        if (!sessionActive || !settings.noClipEnabled() || entity == null) return false;
        Object clientPlayer = targetPlayer;
        if (entity == clientPlayer) return true;

        // An integrated server owns a separate ServerPlayer instance for the
        // same user. Match it by UUID so its movement validation cannot pull
        // the client back out of blocks in singleplayer.
        String expected = targetUuid;
        if (expected == null || expected.isBlank()) return false;
        try {
            Method uuidMethod = UUID_METHODS.get(entity.getClass());
            if (uuidMethod == null) {
                uuidMethod = entity.getClass().getMethod("getUUID");
                UUID_METHODS.put(entity.getClass(), uuidMethod);
            }
            Object uuid = uuidMethod.invoke(entity);
            return uuid instanceof UUID && expected.equals(uuid.toString());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static void onLocalFlightStateChanged(Object player) {
        try {
            if (!isTarget(player)) return;
            PlayerState state = PLAYER_STATES.computeIfAbsent(player, ignored -> new PlayerState());
            MovementBindings bindings = movementBindings(player);
            Object abilities = bindings.getAbilities.invoke(player);
            state.flightPreference = bindings.flying.getBoolean(abilities);
            state.flightPreferenceKnown = true;
        } catch (Throwable throwable) {
            movementError = "Flight toggle hook: " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
        }
    }

    public static void afterPlayerTick(Object player) {
        try {
            if (!isTarget(player)) return;
            gameLoader = player.getClass().getClassLoader();
            targetPlayer = player;
            MovementBindings bindings = movementBindings(player);
            PlayerState state = PLAYER_STATES.computeIfAbsent(player, ignored -> new PlayerState());
            Settings current = sessionActive ? settings : Settings.disabled();

            bindings.ensureModifier(player, bindings.movementSpeed, bindings.speedId,
                    current.speedMultiplier() - 1.0D, bindings.multiplyTotal);
            bindings.ensureTargetValue(player, bindings.blockReach, bindings.blockReachId,
                    current.reachEnabled(), current.interactionDistance());
            bindings.ensureTargetValue(player, bindings.entityReach, bindings.entityReachId,
                    current.reachEnabled(), current.interactionDistance());
            bindings.ensureModifier(player, bindings.creativeFlight, bindings.flightId,
                    current.flightEnabled() ? 1.0D : 0.0D, bindings.addValue);
            bindings.ensureTargetValue(player, bindings.fallDamageMultiplier, bindings.fallProtectionId,
                    current.fallProtectionEnabled(), 0.0D);
            if (current.fallProtectionEnabled()) {
                // Keep the accumulated distance clear while descending. This
                // covers the transition where vanilla turns flying off before
                // the landing callback is reached.
                bindings.resetFallDistance.invoke(player);
            }

            Object abilities = bindings.getAbilities.invoke(player);
            if (current.flightEnabled()) {
                if (!state.mayFlyCaptured) {
                    state.originalMayFly = bindings.mayFlyFlag.getBoolean(abilities);
                    state.mayFlyCaptured = true;
                }
                // This NeoForge LocalPlayer reads Abilities.mayfly directly in its double-space branch.
                // Assert the permission while leaving Abilities.flying to vanilla's toggle.
                bindings.mayFlyFlag.setBoolean(abilities, true);
                if (state.flightPreferenceKnown
                        && bindings.flying.getBoolean(abilities) != state.flightPreference) {
                    bindings.flying.setBoolean(abilities, state.flightPreference);
                }
            } else if (state.mayFlyCaptured) {
                bindings.mayFlyFlag.setBoolean(abilities, state.originalMayFly);
                state.mayFlyCaptured = false;
                state.flightPreferenceKnown = false;
                state.flightPreference = false;
            }
            if (current.noClipEnabled()) {
                if (bindings.isCollisionFree(player)) state.remember(bindings, player);
                bindings.noPhysics.setBoolean(player, true);
                state.wasNoClip = true;
            } else if (state.wasNoClip) {
                state.restoreIfNeeded(bindings, player);
                state.wasNoClip = false;
            }

            if (!current.flightEnabled()
                    && !(boolean) bindings.mayFly.invoke(player)
                    && bindings.flying.getBoolean(abilities)) {
                bindings.flying.setBoolean(abilities, false);
                bindings.syncAbilities(player);
            }
            if (player == targetPlayer
                    && current.aimAssistEnabled()
                    && ctrlDown
                    && !menuVisible
                    && aimTarget != null) {
                applyAimAtTick(player, aimTarget);
            }
            movementError = "";
        } catch (Throwable throwable) {
            movementError = "Player tick: " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
        }
    }

    public static void afterNoPhysicsReset(Object player) {
        try {
            if (!isTarget(player)) return;
            MovementBindings bindings = movementBindings(player);
            PlayerState state = PLAYER_STATES.computeIfAbsent(player, ignored -> new PlayerState());
            if (sessionActive && settings.noClipEnabled()) {
                if (bindings.isCollisionFree(player)) state.remember(bindings, player);
                bindings.noPhysics.setBoolean(player, true);
                state.wasNoClip = true;
            } else if (state.wasNoClip) {
                state.restoreIfNeeded(bindings, player);
                state.wasNoClip = false;
            }
        } catch (Throwable throwable) {
            movementError = "No-clip hook: " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
        }
    }

    public static float scaleFlyingSpeed(float original, Object player) {
        try {
            if (!isTarget(player) || !sessionActive) return original;
            MovementBindings bindings = movementBindings(player);
            Object abilities = bindings.getAbilities.invoke(player);
            return bindings.flying.getBoolean(abilities)
                    ? (float) (original * settings.speedMultiplier())
                    : original;
        } catch (Throwable ignored) {
            return original;
        }
    }

    public static void captureWorld(Object camera, Object modelView, Object projection) {
        if (camera == null || modelView == null || projection == null) {
            aimTarget = null;
            frame = FrameData.empty();
            return;
        }
        try {
            ClassLoader loader = gameLoader != null ? gameLoader : camera.getClass().getClassLoader();
            ClientBindings bindings = clientBindings(loader);
            Object minecraft = bindings.minecraftGetInstance.invoke(null);
            bindings.applyBrightness(minecraft, sessionActive ? settings.brightnessLevel() : 0.0D);
            if (!sessionActive) {
                aimTarget = null;
                frame = FrameData.empty();
                return;
            }
            Object player = bindings.minecraftPlayer.get(minecraft);
            Object level = bindings.minecraftLevel.get(minecraft);
            if (player == null || level == null) {
                aimTarget = null;
                frame = FrameData.empty();
                return;
            }

            if (bindings.minecraftScreen.get(minecraft) != null) {
                aimTarget = null;
                frame = FrameData.empty();
                return;
            }

            Settings current = settings;
            Object cameraPosition = bindings.cameraPosition.invoke(camera);
            double cameraX = bindings.vecX.getDouble(cameraPosition);
            double cameraY = bindings.vecY.getDouble(cameraPosition);
            double cameraZ = bindings.vecZ.getDouble(cameraPosition);
            float[] model = new float[16];
            float[] projectionValues = new float[16];
            bindings.matrixGet.invoke(modelView, (Object) model);
            bindings.matrixGet.invoke(projection, (Object) projectionValues);
            float[] combined = multiply(projectionValues, model);

            Object window = bindings.minecraftGetWindow.invoke(minecraft);
            int width = (int) bindings.windowWidth.invoke(window);
            int height = (int) bindings.windowHeight.invoke(window);
            EntityFrame entities = collectEntities(
                    bindings, level, player, combined, width, height, cameraX, cameraY, cameraZ, current);
            if (current.treasureTrackingEnabled()) {
                entities = appendChestBoxes(
                        bindings, level, player, entities, combined, width, height,
                        cameraX, cameraY, cameraZ);
            }
            aimTarget = entities.aimTarget();
            OrePoint ore = current.oreTrackingEnabled()
                    ? bindings.oreScanner.scan(bindings, level, player, current.oreType(), current.oreScanRadius())
                    : null;

            float oreYaw = 0.0F;
            float orePitch = 0.0F;
            float oreDistance = -1.0F;
            String oreName = "";
            if (ore != null) {
                double dx = ore.x() + 0.5D - cameraX;
                double dy = ore.y() + 0.5D - cameraY;
                double dz = ore.z() + 0.5D - cameraZ;
                double viewX = model[0] * dx + model[4] * dy + model[8] * dz;
                double viewY = model[1] * dx + model[5] * dy + model[9] * dz;
                double viewZ = model[2] * dx + model[6] * dy + model[10] * dz;
                double length = Math.sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ);
                if (length > 0.001D) {
                    oreYaw = (float) Math.atan2(viewX, -viewZ);
                    orePitch = (float) -Math.asin(clamp(viewY / length, -1.0D, 1.0D));
                    oreDistance = (float) length;
                    oreName = ClientBindings.ORE_NAMES[current.oreType()];
                }
            }

            if (current.aimAssistEnabled()
                    && ctrlDown
                    && !menuVisible
                    && entities.aimTarget() != null
                    && bindings.minecraftScreen.get(minecraft) == null) {
                aimAt(bindings, player, entities.aimTarget());
            }
            frame = new FrameData(entities.values(), entities.labels(), oreYaw, orePitch, oreDistance, oreName);
            error = "";
        } catch (Throwable throwable) {
            error = "Overlay frame: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            frame = FrameData.empty();
        }
    }

    public static void beforeSwap(long glfwWindow) {
        if (!NativeOverlayBridgeV6.isLoaded()) return;
        try {
            applyBrightness();
            applyNativeSnapshot(NativeOverlayBridgeV6.snapshot());
            updateMouseCapture();
            FrameData current = sessionActive ? frame : FrameData.empty();
            NativeOverlayBridgeV6.render(
                    glfwWindow,
                    current.entityValues(),
                    current.entityLabels(),
                    current.oreYaw(),
                    current.orePitch(),
                    current.oreDistance(),
                    current.oreName());
            applyNativeSnapshot(NativeOverlayBridgeV6.snapshot());
        } catch (Throwable throwable) {
            error = "Native overlay: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
    }

    private static void applyNativeSnapshot(double[] values) {
        if (values == null || values.length < 22) return;
        ctrlDown = values[20] > 0.5D;
        menuVisible = values[19] > 0.5D;
        Settings current = settings;
        long revision = (long) values[0];
        if (sessionActive && revision > current.revision()) {
            settings = Settings.fromNative(values);
        }
    }

    private static void applyBrightness() throws Exception {
        ClassLoader loader = gameLoader;
        if (loader == null) return;
        ClientBindings bindings = clientBindings(loader);
        Object minecraft = bindings.minecraftGetInstance.invoke(null);
        bindings.applyBrightness(minecraft, sessionActive ? settings.brightnessLevel() : 0.0D);
    }

    private static void updateMouseCapture() throws Exception {
        if (menuVisible == previousMenuVisible) return;
        ClassLoader loader = gameLoader;
        if (loader == null) return;
        ClientBindings bindings = clientBindings(loader);
        Object minecraft = bindings.minecraftGetInstance.invoke(null);
        Object mouseHandler = bindings.minecraftMouseHandler.get(minecraft);
        if (menuVisible) {
            bindings.releaseMouse.invoke(mouseHandler);
        } else if (bindings.minecraftScreen.get(minecraft) == null) {
            bindings.grabMouse.invoke(mouseHandler);
        }
        previousMenuVisible = menuVisible;
    }

    private static EntityFrame collectEntities(
            ClientBindings bindings,
            Object level,
            Object player,
            float[] matrix,
            int width,
            int height,
            double cameraX,
            double cameraY,
            double cameraZ,
            Settings current) throws Exception {
        if (!current.entityEspEnabled() && !current.aimAssistEnabled()) return EntityFrame.empty();
        Iterable<?> iterable = (Iterable<?>) bindings.entitiesForRendering.invoke(level);
        List<float[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Object aimTarget = null;
        double aimDistanceSquared = Double.MAX_VALUE;
        double espLimitSquared = current.entityEspDistance() * (double) current.entityEspDistance();
        double aimLimitSquared = current.aimAssistDistance() * (double) current.aimAssistDistance();

        for (Object entity : iterable) {
            if (entity == null || entity == player || (boolean) bindings.entityRemoved.invoke(entity)) continue;
            double x = ((Number) bindings.entityGetX.invoke(entity)).doubleValue();
            double y = ((Number) bindings.entityGetY.invoke(entity)).doubleValue();
            double z = ((Number) bindings.entityGetZ.invoke(entity)).doubleValue();
            double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            int category = bindings.category(entity);
            boolean categoryEnabled = current.categoryEnabled(category);
            boolean living = bindings.livingEntityClass.isInstance(entity);
            if (living && !(boolean) bindings.entityAlive.invoke(entity)) continue;
            if (current.aimAssistEnabled()
                    && living
                    && categoryEnabled
                    && distanceSquared <= aimLimitSquared
                    && distanceSquared < aimDistanceSquared) {
                aimTarget = entity;
                aimDistanceSquared = distanceSquared;
            }

            if (!current.entityEspEnabled() || boxes.size() >= MAX_ENTITIES
                    || !categoryEnabled || distanceSquared > espLimitSquared) {
                if (!current.aimAssistEnabled() && boxes.size() >= MAX_ENTITIES) break;
                continue;
            }
            Object box = bindings.entityBoundingBox.invoke(entity);
            float[] projected = projectBox(bindings, box, matrix, width, height, cameraX, cameraY, cameraZ);
            if (projected == null) continue;

            projected[16] = category;
            projected[17] = (float) Math.sqrt(distanceSquared);
            projected[18] = 0.0F;
            boxes.add(projected);
            labels.add(bindings.entityLabel(entity, category));
            if (!current.aimAssistEnabled() && boxes.size() >= MAX_ENTITIES) break;
        }

        float[] values = new float[boxes.size() * ENTITY_STRIDE];
        for (int i = 0; i < boxes.size(); ++i) {
            System.arraycopy(boxes.get(i), 0, values, i * ENTITY_STRIDE, ENTITY_STRIDE);
        }
        return new EntityFrame(values, labels.toArray(String[]::new), aimTarget);
    }

    private static EntityFrame appendChestBoxes(
            ClientBindings bindings,
            Object level,
            Object player,
            EntityFrame frame,
            float[] matrix,
            int width,
            int height,
            double cameraX,
            double cameraY,
            double cameraZ) throws Exception {
        List<ChestPoint> chests = bindings.chestScanner.scan(bindings, level, player);
        if (chests.isEmpty()) return frame;

        List<float[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ChestPoint chest : chests) {
            Object box = bindings.aabbConstructor.newInstance(
                    (double) chest.x(), (double) chest.y(), (double) chest.z(),
                    (double) chest.x() + 1.0D, (double) chest.y() + 1.0D, (double) chest.z() + 1.0D);
            float[] projected = projectBox(bindings, box, matrix, width, height, cameraX, cameraY, cameraZ);
            if (projected == null) continue;
            double dx = chest.x() + 0.5D - cameraX;
            double dy = chest.y() + 0.5D - cameraY;
            double dz = chest.z() + 0.5D - cameraZ;
            projected[16] = 4.0F;
            projected[17] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            projected[18] = 1.0F;
            boxes.add(projected);
            labels.add("Chest " + chest.x() + ", " + chest.y() + ", " + chest.z());
            if (boxes.size() >= MAX_CHESTS) break;
        }
        if (boxes.isEmpty()) return frame;

        int existingCount = frame.values().length / ENTITY_STRIDE;
        float[] values = new float[(existingCount + boxes.size()) * ENTITY_STRIDE];
        System.arraycopy(frame.values(), 0, values, 0, frame.values().length);
        String[] allLabels = new String[frame.labels().length + labels.size()];
        System.arraycopy(frame.labels(), 0, allLabels, 0, frame.labels().length);
        for (int index = 0; index < boxes.size(); ++index) {
            System.arraycopy(boxes.get(index), 0, values,
                    (existingCount + index) * ENTITY_STRIDE, ENTITY_STRIDE);
            allLabels[frame.labels().length + index] = labels.get(index);
        }
        return new EntityFrame(values, allLabels, frame.aimTarget());
    }

    private static float[] projectBox(
            ClientBindings bindings,
            Object box,
            float[] matrix,
            int width,
            int height,
            double cameraX,
            double cameraY,
            double cameraZ) throws IllegalAccessException {
        double minX = bindings.boxMinX.getDouble(box), minY = bindings.boxMinY.getDouble(box), minZ = bindings.boxMinZ.getDouble(box);
        double maxX = bindings.boxMaxX.getDouble(box), maxY = bindings.boxMaxY.getDouble(box), maxZ = bindings.boxMaxZ.getDouble(box);
        float[] result = new float[ENTITY_STRIDE];
        float minScreenX = Float.POSITIVE_INFINITY, minScreenY = Float.POSITIVE_INFINITY;
        float maxScreenX = Float.NEGATIVE_INFINITY, maxScreenY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; ++corner) {
            double x = ((corner & 1) == 0 ? minX : maxX) - cameraX;
            double y = ((corner & 2) == 0 ? minY : maxY) - cameraY;
            double z = ((corner & 4) == 0 ? minZ : maxZ) - cameraZ;
            double clipX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12];
            double clipY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13];
            double clipW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
            if (clipW <= 0.05D) return null;
            float screenX = (float) ((clipX / clipW * 0.5D + 0.5D) * width);
            float screenY = (float) ((0.5D - clipY / clipW * 0.5D) * height);
            result[corner * 2] = screenX;
            result[corner * 2 + 1] = screenY;
            minScreenX = Math.min(minScreenX, screenX);
            maxScreenX = Math.max(maxScreenX, screenX);
            minScreenY = Math.min(minScreenY, screenY);
            maxScreenY = Math.max(maxScreenY, screenY);
        }
        if (maxScreenX < 0 || maxScreenY < 0 || minScreenX > width || minScreenY > height) return null;
        return result;
    }

    private static void aimAt(ClientBindings bindings, Object player, Object target) throws Exception {
        double sourceX = ((Number) bindings.entityGetX.invoke(player)).doubleValue();
        double sourceY = ((Number) bindings.entityEyeY.invoke(player)).doubleValue();
        double sourceZ = ((Number) bindings.entityGetZ.invoke(player)).doubleValue();
        double targetX = ((Number) bindings.entityGetX.invoke(target)).doubleValue();
        double targetY = ((Number) bindings.entityEyeY.invoke(target)).doubleValue();
        double targetZ = ((Number) bindings.entityGetZ.invoke(target)).doubleValue();
        double dx = targetX - sourceX;
        double dy = targetY - sourceY;
        double dz = targetZ - sourceZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float currentYaw = ((Number) bindings.entityGetYRot.invoke(player)).floatValue();
        float currentPitch = ((Number) bindings.entityGetXRot.invoke(player)).floatValue();
        float targetPitch = Math.max(-90.0F, Math.min(90.0F, pitch));
        float yawDelta = wrapDegrees(yaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        // Set absolute angles and interpolation baselines. Relative turn() is
        // easy for the client's input tick or a server look packet to overwrite
        // before the next frame is rendered.
        bindings.entitySetYRot.invoke(player, yaw);
        bindings.entitySetXRot.invoke(player, targetPitch);
        bindings.entityYRotO.setFloat(player, yaw);
        bindings.entityXRotO.setFloat(player, targetPitch);
        bindings.entitySetYHeadRot.invoke(player, yaw);
        bindings.entitySetYBodyRot.invoke(player, yaw);
    }

    private static void applyAimAtTick(Object player, Object target) throws Exception {
        ClientBindings bindings = clientBindings(player.getClass().getClassLoader());
        Object minecraft = bindings.minecraftGetInstance.invoke(null);
        if (bindings.minecraftScreen.get(minecraft) != null) return;
        aimAt(bindings, player, target);
    }

    public static boolean preventFallDamage(Object player) {
        if (!sessionActive || !settings.fallProtectionEnabled() || player == null) return false;
        try {
            if (!isTarget(player)) return false;
            MovementBindings bindings = movementBindings(player);
            bindings.resetFallDistance.invoke(player);
            return true;
        } catch (Throwable throwable) {
            movementError = "Fall protection hook: " + throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
            return false;
        }
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; ++column) {
            for (int row = 0; row < 4; ++row) {
                float value = 0.0F;
                for (int index = 0; index < 4; ++index) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                result[column * 4 + row] = value;
            }
        }
        return result;
    }

    private static boolean isTarget(Object player) throws Exception {
        String expected = targetUuid;
        if (expected == null || player == null) return false;
        return expected.equals(String.valueOf(player.getClass().getMethod("getUUID").invoke(player)));
    }

    private static MovementBindings movementBindings(Object player) throws Exception {
        ClassLoader loader = player.getClass().getClassLoader();
        MovementBindings existing = MOVEMENT_BINDINGS.get(loader);
        if (existing != null) return existing;
        MovementBindings created = new MovementBindings(loader);
        MOVEMENT_BINDINGS.put(loader, created);
        return created;
    }

    private static ClientBindings clientBindings(ClassLoader loader) throws Exception {
        ClientBindings existing = CLIENT_BINDINGS.get(loader);
        if (existing != null) return existing;
        ClientBindings created = new ClientBindings(loader);
        CLIENT_BINDINGS.put(loader, created);
        return created;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Settings(
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
            boolean treasureTrackingEnabled) {
        static Settings disabled() {
            return new Settings(
                    0L, 1.0D, false, 8.0D, false, false, false,
                    false, 0, 32, false, true, true, true, false, 64, false, 48, 0.0D, false);
        }

        static Settings fromNative(double[] value) {
            return new Settings(
                    Math.max(0L, (long) value[0]),
                    clamp(value[1], 0.5D, 10.0D),
                    value[2] > 0.5D,
                    clamp(value[3], 3.0D, 32.0D),
                    value[4] > 0.5D || value[5] > 0.5D,
                    value[5] > 0.5D,
                    value[6] > 0.5D,
                    value[7] > 0.5D,
                    clamp((int) value[8], 0, 9),
                    clamp((int) value[9], 8, 96),
                    value[10] > 0.5D,
                    value[11] > 0.5D,
                    value[12] > 0.5D,
                    value[13] > 0.5D,
                    value[14] > 0.5D,
                    clamp((int) value[15], 8, 512),
                    value[16] > 0.5D,
                    clamp((int) value[17], 4, 256),
                    clamp(value[18], 0.0D, 1.0D),
                    value.length > 21 && value[21] > 0.5D);
        }

        double[] toNativeArray() {
            return new double[]{
                    revision, speedMultiplier, reachEnabled ? 1 : 0, interactionDistance,
                    flightEnabled ? 1 : 0, noClipEnabled ? 1 : 0, fallProtectionEnabled ? 1 : 0,
                    oreTrackingEnabled ? 1 : 0, oreType, oreScanRadius,
                    entityEspEnabled ? 1 : 0, espPlayers ? 1 : 0, espHostile ? 1 : 0,
                    espPassive ? 1 : 0, espOther ? 1 : 0, entityEspDistance,
                    aimAssistEnabled ? 1 : 0, aimAssistDistance, brightnessLevel,
                    menuVisible ? 1 : 0, ctrlDown ? 1 : 0, treasureTrackingEnabled ? 1 : 0};
        }

        boolean categoryEnabled(int category) {
            return switch (category) {
                case 0 -> espPlayers;
                case 1 -> espHostile;
                case 2 -> espPassive;
                default -> espOther;
            };
        }
    }

    private record FrameData(
            float[] entityValues,
            String[] entityLabels,
            float oreYaw,
            float orePitch,
            float oreDistance,
            String oreName) {
        static FrameData empty() {
            return new FrameData(new float[0], new String[0], 0, 0, -1, "");
        }
    }

    private record EntityFrame(float[] values, String[] labels, Object aimTarget) {
        static EntityFrame empty() {
            return new EntityFrame(new float[0], new String[0], null);
        }
    }

    private record OrePoint(int x, int y, int z, double distanceSquared) {
    }

    private record ChestPoint(int x, int y, int z) {
    }

    private static final class ChestScanner {
        private static final long CACHE_NANOS = 250_000_000L;
        private Object cachedLevel;
        private long cachedAt;
        private List<ChestPoint> cachedPoints = List.of();

        List<ChestPoint> scan(ClientBindings bindings, Object level, Object player) throws Exception {
            long now = System.nanoTime();
            if (level == cachedLevel && now - cachedAt < CACHE_NANOS) return cachedPoints;

            Object chunkSource = bindings.levelGetChunkSource.invoke(level);
            int loaded = Math.max(1, ((Number) bindings.chunkSourceLoadedCount.invoke(chunkSource)).intValue());
            int radius = Math.min(32, Math.max(8, (int) Math.ceil(Math.sqrt(loaded)) + 2));
            int centerX = Math.floorDiv((int) Math.floor(
                    ((Number) bindings.entityGetX.invoke(player)).doubleValue()), 16);
            int centerZ = Math.floorDiv((int) Math.floor(
                    ((Number) bindings.entityGetZ.invoke(player)).doubleValue()), 16);
            List<ChestPoint> points = new ArrayList<>();
            for (int chunkX = centerX - radius; chunkX <= centerX + radius; ++chunkX) {
                for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; ++chunkZ) {
                    if (!(boolean) bindings.chunkSourceHasChunk.invoke(chunkSource, chunkX, chunkZ)) continue;
                    Object chunk = bindings.chunkSourceGetChunkNow.invoke(chunkSource, chunkX, chunkZ);
                    if (chunk == null) continue;
                    Object blockEntities = bindings.chunkGetBlockEntities.invoke(chunk);
                    if (!(blockEntities instanceof Map<?, ?> map)) continue;
                    for (Object value : map.values()) {
                        if (value == null) continue;
                        Object type = bindings.blockEntityGetType.invoke(value);
                        if (type != bindings.chestType && type != bindings.trappedChestType) continue;
                        Object position = bindings.blockEntityGetBlockPos.invoke(value);
                        points.add(new ChestPoint(
                                ((Number) bindings.blockPosGetX.invoke(position)).intValue(),
                                ((Number) bindings.blockPosGetY.invoke(position)).intValue(),
                                ((Number) bindings.blockPosGetZ.invoke(position)).intValue()));
                        if (points.size() >= MAX_CHESTS) break;
                    }
                    if (points.size() >= MAX_CHESTS) break;
                }
                if (points.size() >= MAX_CHESTS) break;
            }
            cachedLevel = level;
            cachedAt = now;
            cachedPoints = List.copyOf(points);
            return cachedPoints;
        }
    }

    private static final class PlayerState {
        boolean wasNoClip;
        boolean mayFlyCaptured;
        boolean originalMayFly;
        boolean flightPreferenceKnown;
        boolean flightPreference;
        Object safeLevel;
        double safeX;
        double safeY;
        double safeZ;

        void remember(MovementBindings bindings, Object player) throws Exception {
            safeLevel = bindings.level.invoke(player);
            safeX = ((Number) bindings.getX.invoke(player)).doubleValue();
            safeY = ((Number) bindings.getY.invoke(player)).doubleValue();
            safeZ = ((Number) bindings.getZ.invoke(player)).doubleValue();
        }

        void restoreIfNeeded(MovementBindings bindings, Object player) throws Exception {
            if (safeLevel == null || safeLevel != bindings.level.invoke(player) || bindings.isCollisionFree(player)) return;
            bindings.setPos.invoke(player, safeX, safeY, safeZ);
            bindings.resetFallDistance.invoke(player);
        }
    }

    private static final class MovementBindings {
        final Object movementSpeed;
        final Object blockReach;
        final Object entityReach;
        final Object creativeFlight;
        final Object fallDamageMultiplier;
        final Object speedId;
        final Object blockReachId;
        final Object entityReachId;
        final Object flightId;
        final Object fallProtectionId;
        final Object addValue;
        final Object multiplyTotal;
        final Method getAttribute;
        final Method getModifier;
        final Method removeModifier;
        final Method addTransientModifier;
        final Method getAttributeValue;
        final Method modifierAmount;
        final Method modifierOperation;
        final Constructor<?> modifierConstructor;
        final Method getAbilities;
        final Method mayFly;
        final Field mayFlyFlag;
        final Field flying;
        final Field noPhysics;
        final Method level;
        final Method getBoundingBox;
        final Method noCollision;
        final Method getX;
        final Method getY;
        final Method getZ;
        final Method setPos;
        final Method resetFallDistance;

        MovementBindings(ClassLoader loader) throws Exception {
            Class<?> holder = Class.forName("net.minecraft.core.Holder", false, loader);
            Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation", false, loader);
            Class<?> attributeModifier = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier", false, loader);
            Class<?> operation = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier$Operation", false, loader);
            Class<?> attributeInstance = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeInstance", false, loader);
            Class<?> livingEntity = Class.forName("net.minecraft.world.entity.LivingEntity", false, loader);
            Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity", false, loader);
            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> boxClass = Class.forName("net.minecraft.world.phys.AABB", false, loader);
            Class<?> abilitiesClass = Class.forName("net.minecraft.world.entity.player.Abilities", false, loader);
            Class<?> attributes = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes", false, loader);
            Class<?> neoForgeMod = Class.forName("net.neoforged.neoforge.common.NeoForgeMod", false, loader);

            movementSpeed = attributes.getField("MOVEMENT_SPEED").get(null);
            blockReach = attributes.getField("BLOCK_INTERACTION_RANGE").get(null);
            entityReach = attributes.getField("ENTITY_INTERACTION_RANGE").get(null);
            fallDamageMultiplier = attributes.getField("FALL_DAMAGE_MULTIPLIER").get(null);
            creativeFlight = neoForgeMod.getField("CREATIVE_FLIGHT").get(null);
            Method createId = resourceLocation.getMethod("fromNamespaceAndPath", String.class, String.class);
            speedId = createId.invoke(null, "minecraft1211ezcheat", "movement_speed");
            blockReachId = createId.invoke(null, "minecraft1211ezcheat", "block_reach");
            entityReachId = createId.invoke(null, "minecraft1211ezcheat", "entity_reach");
            flightId = createId.invoke(null, "minecraft1211ezcheat", "creative_flight");
            fallProtectionId = createId.invoke(null, "minecraft1211ezcheat", "fall_protection");

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object add = Enum.valueOf((Class<? extends Enum>) operation, "ADD_VALUE");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object multiply = Enum.valueOf((Class<? extends Enum>) operation, "ADD_MULTIPLIED_TOTAL");
            addValue = add;
            multiplyTotal = multiply;

            getAttribute = livingEntity.getMethod("getAttribute", holder);
            getModifier = attributeInstance.getMethod("getModifier", resourceLocation);
            removeModifier = attributeInstance.getMethod("removeModifier", resourceLocation);
            addTransientModifier = attributeInstance.getMethod("addTransientModifier", attributeModifier);
            getAttributeValue = attributeInstance.getMethod("getValue");
            modifierAmount = attributeModifier.getMethod("amount");
            modifierOperation = attributeModifier.getMethod("operation");
            modifierConstructor = attributeModifier.getConstructor(resourceLocation, double.class, operation);
            getAbilities = playerClass.getMethod("getAbilities");
            mayFly = playerClass.getMethod("mayFly");
            mayFlyFlag = abilitiesClass.getField("mayfly");
            flying = abilitiesClass.getField("flying");
            noPhysics = entityClass.getField("noPhysics");
            level = entityClass.getMethod("level");
            getBoundingBox = entityClass.getMethod("getBoundingBox");
            noCollision = levelClass.getMethod("noCollision", entityClass, boxClass);
            getX = entityClass.getMethod("getX");
            getY = entityClass.getMethod("getY");
            getZ = entityClass.getMethod("getZ");
            setPos = entityClass.getMethod("setPos", double.class, double.class, double.class);
            resetFallDistance = entityClass.getMethod("resetFallDistance");
        }

        void ensureTargetValue(Object player, Object attribute, Object id, boolean enabled, double target) throws Exception {
            Object instance = getAttribute.invoke(player, attribute);
            if (instance == null) return;
            Object existing = getModifier.invoke(instance, id);
            if (!enabled) {
                if (existing != null) removeModifier.invoke(instance, id);
                return;
            }
            double value = ((Number) getAttributeValue.invoke(instance)).doubleValue();
            if (Math.abs(value - target) <= EPSILON) return;
            if (existing != null) removeModifier.invoke(instance, id);
            double difference = target - ((Number) getAttributeValue.invoke(instance)).doubleValue();
            if (Math.abs(difference) > EPSILON) {
                addTransientModifier.invoke(instance, modifierConstructor.newInstance(id, difference, addValue));
            }
        }

        void ensureModifier(Object player, Object attribute, Object id, double amount, Object operation) throws Exception {
            Object instance = getAttribute.invoke(player, attribute);
            if (instance == null) return;
            Object existing = getModifier.invoke(instance, id);
            if (Math.abs(amount) <= EPSILON) {
                if (existing != null) removeModifier.invoke(instance, id);
                return;
            }
            if (existing != null
                    && Math.abs(((Number) modifierAmount.invoke(existing)).doubleValue() - amount) <= EPSILON
                    && modifierOperation.invoke(existing) == operation) return;
            if (existing != null) removeModifier.invoke(instance, id);
            addTransientModifier.invoke(instance, modifierConstructor.newInstance(id, amount, operation));
        }

        boolean isCollisionFree(Object player) throws Exception {
            return (boolean) noCollision.invoke(level.invoke(player), player, getBoundingBox.invoke(player));
        }

        void syncAbilities(Object player) {
            try {
                player.getClass().getMethod("onUpdateAbilities").invoke(player);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static final class ClientBindings {
        static final String[] ORE_NAMES = {
                "钻石矿", "金矿", "铁矿", "绿宝石矿", "红石矿",
                "青金石矿", "煤矿", "铜矿", "下界石英矿", "远古残骸"
        };
        private static final String[][] ORE_FIELDS = {
                {"DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"},
                {"GOLD_ORE", "DEEPSLATE_GOLD_ORE", "NETHER_GOLD_ORE"},
                {"IRON_ORE", "DEEPSLATE_IRON_ORE"},
                {"EMERALD_ORE", "DEEPSLATE_EMERALD_ORE"},
                {"REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"},
                {"LAPIS_ORE", "DEEPSLATE_LAPIS_ORE"},
                {"COAL_ORE", "DEEPSLATE_COAL_ORE"},
                {"COPPER_ORE", "DEEPSLATE_COPPER_ORE"},
                {"NETHER_QUARTZ_ORE"},
                {"ANCIENT_DEBRIS"}
        };

        final Method minecraftGetInstance;
        final Field minecraftPlayer;
        final Field minecraftLevel;
        final Field minecraftMouseHandler;
        final Field minecraftScreen;
        final Field minecraftOptions;
        final Method minecraftGetWindow;
        final Method windowWidth;
        final Method windowHeight;
        final Method releaseMouse;
        final Method grabMouse;
        final Method optionsGamma;
        final Field optionValue;
        final Method cameraPosition;
        final Field vecX;
        final Field vecY;
        final Field vecZ;
        final Method matrixGet;
        final Method entitiesForRendering;
        final Class<?> playerClass;
        final Class<?> livingEntityClass;
        final Class<?> enemyClass;
        final Method entityRemoved;
        final Method entityAlive;
        final Method entityGetX;
        final Method entityGetY;
        final Method entityGetZ;
        final Method entityEyeY;
        final Method entityBoundingBox;
        final Method entityGetName;
        final Method entityGetType;
        final Method entityGetUuid;
        final Method entityGetYRot;
        final Method entityGetXRot;
        final Method entitySetYRot;
        final Method entitySetXRot;
        final Field entityYRotO;
        final Field entityXRotO;
        final Method entitySetYHeadRot;
        final Method entitySetYBodyRot;
        final Method componentGetString;
        final Method entityTypeDescription;
        final Field boxMinX;
        final Field boxMinY;
        final Field boxMinZ;
        final Field boxMaxX;
        final Field boxMaxY;
        final Field boxMaxZ;
        final Constructor<?> aabbConstructor;
        final Constructor<?> mutablePositionConstructor;
        final Method mutablePositionSet;
        final Method levelIsLoaded;
        final Method levelGetBlockState;
        final Method levelMinHeight;
        final Method levelMaxHeight;
        final Method blockStateGetBlock;
        final Object[][] oreBlocks;
        final OreScanner oreScanner = new OreScanner();
        final ChestScanner chestScanner = new ChestScanner();
        final Method levelGetChunkSource;
        final Method chunkSourceHasChunk;
        final Method chunkSourceGetChunkNow;
        final Method chunkSourceLoadedCount;
        final Method chunkGetBlockEntities;
        final Method blockEntityGetBlockPos;
        final Method blockEntityGetType;
        final Method blockPosGetX;
        final Method blockPosGetY;
        final Method blockPosGetZ;
        final Object chestType;
        final Object trappedChestType;
        double originalGamma;
        boolean brightnessOverridden;

        ClientBindings(ClassLoader loader) throws Exception {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Class<?> window = Class.forName("com.mojang.blaze3d.platform.Window", false, loader);
            Class<?> mouseHandler = Class.forName("net.minecraft.client.MouseHandler", false, loader);
            Class<?> options = Class.forName("net.minecraft.client.Options", false, loader);
            Class<?> optionInstance = Class.forName("net.minecraft.client.OptionInstance", false, loader);
            Class<?> camera = Class.forName("net.minecraft.client.Camera", false, loader);
            Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3", false, loader);
            Class<?> matrix4f = Class.forName("org.joml.Matrix4f", false, loader);
            Class<?> clientLevel = Class.forName("net.minecraft.client.multiplayer.ClientLevel", false, loader);
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", false, loader);
            playerClass = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
            livingEntityClass = Class.forName("net.minecraft.world.entity.LivingEntity", false, loader);
            enemyClass = Class.forName("net.minecraft.world.entity.monster.Enemy", false, loader);
            Class<?> component = Class.forName("net.minecraft.network.chat.Component", false, loader);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType", false, loader);
            Class<?> box = Class.forName("net.minecraft.world.phys.AABB", false, loader);
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", false, loader);
            Class<?> mutableBlockPos = Class.forName("net.minecraft.core.BlockPos$MutableBlockPos", false, loader);
            Class<?> level = Class.forName("net.minecraft.world.level.Level", false, loader);
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
            Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks", false, loader);
            Class<?> chunkSource = Class.forName("net.minecraft.world.level.chunk.ChunkSource", false, loader);
            Class<?> levelChunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk", false, loader);
            Class<?> blockEntity = Class.forName("net.minecraft.world.level.block.entity.BlockEntity", false, loader);
            Class<?> blockEntityType = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType", false, loader);

            minecraftGetInstance = minecraft.getMethod("getInstance");
            minecraftPlayer = minecraft.getField("player");
            minecraftLevel = minecraft.getField("level");
            minecraftMouseHandler = minecraft.getField("mouseHandler");
            minecraftScreen = minecraft.getField("screen");
            minecraftOptions = minecraft.getField("options");
            minecraftGetWindow = minecraft.getMethod("getWindow");
            windowWidth = window.getMethod("getWidth");
            windowHeight = window.getMethod("getHeight");
            releaseMouse = mouseHandler.getMethod("releaseMouse");
            grabMouse = mouseHandler.getMethod("grabMouse");
            optionsGamma = options.getMethod("gamma");
            optionValue = optionInstance.getDeclaredField("value");
            optionValue.setAccessible(true);
            cameraPosition = camera.getMethod("getPosition");
            vecX = vec3.getField("x");
            vecY = vec3.getField("y");
            vecZ = vec3.getField("z");
            matrixGet = matrix4f.getMethod("get", float[].class);
            entitiesForRendering = clientLevel.getMethod("entitiesForRendering");
            entityRemoved = entity.getMethod("isRemoved");
            entityAlive = entity.getMethod("isAlive");
            entityGetX = entity.getMethod("getX");
            entityGetY = entity.getMethod("getY");
            entityGetZ = entity.getMethod("getZ");
            entityEyeY = entity.getMethod("getEyeY");
            entityBoundingBox = entity.getMethod("getBoundingBox");
            entityGetName = entity.getMethod("getName");
            entityGetType = entity.getMethod("getType");
            entityGetUuid = entity.getMethod("getUUID");
            entityGetYRot = entity.getMethod("getYRot");
            entityGetXRot = entity.getMethod("getXRot");
            entitySetYRot = entity.getMethod("setYRot", float.class);
            entitySetXRot = entity.getMethod("setXRot", float.class);
            entityYRotO = entity.getField("yRotO");
            entityXRotO = entity.getField("xRotO");
            entitySetYHeadRot = livingEntityClass.getMethod("setYHeadRot", float.class);
            entitySetYBodyRot = livingEntityClass.getMethod("setYBodyRot", float.class);
            componentGetString = component.getMethod("getString");
            entityTypeDescription = entityType.getMethod("getDescription");
            boxMinX = box.getField("minX");
            boxMinY = box.getField("minY");
            boxMinZ = box.getField("minZ");
            boxMaxX = box.getField("maxX");
            boxMaxY = box.getField("maxY");
            boxMaxZ = box.getField("maxZ");
            aabbConstructor = box.getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class);
            mutablePositionConstructor = mutableBlockPos.getConstructor();
            mutablePositionSet = mutableBlockPos.getMethod("set", int.class, int.class, int.class);
            levelIsLoaded = level.getMethod("isLoaded", blockPos);
            levelGetBlockState = level.getMethod("getBlockState", blockPos);
            levelMinHeight = level.getMethod("getMinBuildHeight");
            levelMaxHeight = level.getMethod("getMaxBuildHeight");
            blockStateGetBlock = blockState.getMethod("getBlock");
            levelGetChunkSource = clientLevel.getMethod("getChunkSource");
            chunkSourceHasChunk = chunkSource.getMethod("hasChunk", int.class, int.class);
            chunkSourceGetChunkNow = chunkSource.getMethod("getChunkNow", int.class, int.class);
            chunkSourceLoadedCount = chunkSource.getMethod("getLoadedChunksCount");
            chunkGetBlockEntities = levelChunk.getMethod("getBlockEntities");
            blockEntityGetBlockPos = blockEntity.getMethod("getBlockPos");
            blockEntityGetType = blockEntity.getMethod("getType");
            blockPosGetX = blockPos.getMethod("getX");
            blockPosGetY = blockPos.getMethod("getY");
            blockPosGetZ = blockPos.getMethod("getZ");
            chestType = blockEntityType.getField("CHEST").get(null);
            trappedChestType = blockEntityType.getField("TRAPPED_CHEST").get(null);
            oreBlocks = new Object[ORE_FIELDS.length][];
            for (int type = 0; type < ORE_FIELDS.length; ++type) {
                oreBlocks[type] = new Object[ORE_FIELDS[type].length];
                for (int index = 0; index < ORE_FIELDS[type].length; ++index) {
                    oreBlocks[type][index] = blocks.getField(ORE_FIELDS[type][index]).get(null);
                }
            }
        }

        int category(Object entity) {
            if (playerClass.isInstance(entity)) return 0;
            if (enemyClass.isInstance(entity)) return 1;
            if (livingEntityClass.isInstance(entity)) return 2;
            return 3;
        }

        String entityLabel(Object entity, int category) throws Exception {
            String name = String.valueOf(componentGetString.invoke(entityGetName.invoke(entity)));
            if (category == 0) {
                return "[玩家] " + name + " | ID " + entityGetUuid.invoke(entity);
            }
            Object type = entityGetType.invoke(entity);
            String typeName = String.valueOf(componentGetString.invoke(entityTypeDescription.invoke(type)));
            return typeName.equals(name) ? typeName : typeName + " | " + name;
        }

        boolean isSelectedOre(Object block, int type) {
            for (Object candidate : oreBlocks[type]) {
                if (block == candidate) return true;
            }
            return false;
        }

        void applyBrightness(Object minecraft, double level) throws Exception {
            Object gamma = optionsGamma.invoke(minecraftOptions.get(minecraft));
            if (level > EPSILON) {
                if (!brightnessOverridden) {
                    originalGamma = ((Number) optionValue.get(gamma)).doubleValue();
                    brightnessOverridden = true;
                }
                double target = originalGamma + (16.0D - originalGamma) * clamp(level, 0.0D, 1.0D);
                optionValue.set(gamma, target);
            } else if (brightnessOverridden) {
                optionValue.set(gamma, originalGamma);
                brightnessOverridden = false;
            }
        }
    }

    private static final class OreScanner {
        Object level;
        Object mutablePosition;
        int type = -1;
        int radius;
        int centerX;
        int centerY;
        int centerZ;
        int cursor;
        int total;
        int diameter;
        int verticalRadius;
        OrePoint best;
        OrePoint displayed;

        OrePoint scan(ClientBindings bindings, Object currentLevel, Object player, int selectedType, int selectedRadius)
                throws Exception {
            double playerX = ((Number) bindings.entityGetX.invoke(player)).doubleValue();
            double playerY = ((Number) bindings.entityGetY.invoke(player)).doubleValue();
            double playerZ = ((Number) bindings.entityGetZ.invoke(player)).doubleValue();
            int px = (int) Math.floor(playerX);
            int py = (int) Math.floor(playerY);
            int pz = (int) Math.floor(playerZ);
            if (currentLevel != level
                    || selectedType != type
                    || selectedRadius != radius
                    || Math.abs(px - centerX) > 8
                    || Math.abs(py - centerY) > 8
                    || Math.abs(pz - centerZ) > 8) {
                reset(bindings, currentLevel, selectedType, selectedRadius, px, py, pz);
            }

            int minHeight = (int) bindings.levelMinHeight.invoke(currentLevel);
            int maxHeight = (int) bindings.levelMaxHeight.invoke(currentLevel);
            long deadline = System.nanoTime() + ORE_TIME_BUDGET_NANOS;
            int reads = 0;
            while (cursor < total && reads < ORE_READ_BUDGET && System.nanoTime() < deadline) {
                int value = cursor++;
                int offsetX = value % diameter;
                value /= diameter;
                int offsetZ = value % diameter;
                int offsetY = value / diameter;
                int x = centerX + offsetX - radius;
                int y = centerY + offsetY - verticalRadius;
                int z = centerZ + offsetZ - radius;
                if (y < minHeight || y >= maxHeight) continue;
                double dx = x + 0.5D - playerX;
                double dy = y + 0.5D - playerY;
                double dz = z + 0.5D - playerZ;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared > radius * (double) radius) continue;
                bindings.mutablePositionSet.invoke(mutablePosition, x, y, z);
                if (!(boolean) bindings.levelIsLoaded.invoke(currentLevel, mutablePosition)) continue;
                Object state = bindings.levelGetBlockState.invoke(currentLevel, mutablePosition);
                reads++;
                Object block = bindings.blockStateGetBlock.invoke(state);
                if (bindings.isSelectedOre(block, type)
                        && (best == null || distanceSquared < best.distanceSquared())) {
                    best = new OrePoint(x, y, z, distanceSquared);
                    if (displayed == null || distanceSquared < displayed.distanceSquared()) displayed = best;
                }
            }
            if (cursor >= total) {
                displayed = best;
                best = null;
                cursor = 0;
                centerX = px;
                centerY = py;
                centerZ = pz;
            }
            return displayed;
        }

        private void reset(
                ClientBindings bindings, Object currentLevel, int selectedType, int selectedRadius,
                int px, int py, int pz) throws Exception {
            level = currentLevel;
            type = selectedType;
            radius = selectedRadius;
            centerX = px;
            centerY = py;
            centerZ = pz;
            diameter = radius * 2 + 1;
            verticalRadius = Math.min(radius, 32);
            total = diameter * diameter * (verticalRadius * 2 + 1);
            cursor = 0;
            best = null;
            displayed = null;
            mutablePosition = bindings.mutablePositionConstructor.newInstance();
        }
    }
}
