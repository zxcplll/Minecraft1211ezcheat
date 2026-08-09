package io.github.zxcplll.minecraft1211ezcheat;

import java.nio.file.Path;

public final class NativeOverlayBridgeV6 {
    private static boolean loaded;

    private NativeOverlayBridgeV6() {
    }

    static synchronized void load(Path libraryPath) {
        if (loaded) {
            return;
        }
        System.load(libraryPath.toAbsolutePath().normalize().toString());
        loaded = true;
    }

    static boolean isLoaded() {
        return loaded;
    }

    static native void configure(double[] values);

    static native double[] snapshot();

    static native void render(
            long glfwWindow,
            float[] entityValues,
            String[] entityLabels,
            float oreYaw,
            float orePitch,
            float oreDistance,
            String oreName);

    static native void shutdown();
}
