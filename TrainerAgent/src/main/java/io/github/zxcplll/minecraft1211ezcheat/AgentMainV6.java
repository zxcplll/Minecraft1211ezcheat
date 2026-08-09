package io.github.zxcplll.minecraft1211ezcheat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public final class AgentMainV6 {
    private static final String STATUS_FILE_NAME = "status-v2.json";
    private static final Set<String> INCOMPATIBLE_AGENT_CLASSES = Set.of(
            "AgentMainV3", "AgentMainV4", "AgentMainV5", "AgentMainV6",
            "NativeOverlayBridgeV4", "NativeOverlayBridgeV5", "NativeOverlayBridgeV6");
    private static boolean installed;
    private static JarFile bootstrapJar;

    private AgentMainV6() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        start(arguments, instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        start(arguments, instrumentation);
    }

    private static void start(String arguments, Instrumentation instrumentation) throws Exception {
        try {
            install(arguments, instrumentation);
        } catch (Throwable throwable) {
            writeInitializationError(arguments, throwable);
            if (throwable instanceof Exception exception) throw exception;
            if (throwable instanceof Error error) throw error;
            throw new RuntimeException(throwable);
        }
    }

    private static synchronized void install(String arguments, Instrumentation instrumentation) throws Exception {
        AgentOptions options = AgentOptions.decode(arguments);
        if (!installed) {
            ensureCompatibleRuntime(instrumentation);

            bootstrapJar = new JarFile(options.hooksPath().toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);

            RuntimeTransformerV6 transformer = new RuntimeTransformerV6();
            instrumentation.addTransformer(transformer, true);
            try {
                List<Class<?>> targets = new ArrayList<>();
                for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                    if (RuntimeTransformerV6.isTarget(loadedClass.getName())
                            && instrumentation.isModifiableClass(loadedClass)) {
                        targets.add(loadedClass);
                    }
                }
            if (targets.size() < 4) {
                throw new IllegalStateException("Expected at least four loaded render/player targets, found " + targets.size());
                }
                instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
            } catch (Throwable throwable) {
                instrumentation.removeTransformer(transformer);
                throw throwable;
            }
            AgentHooksV6.loadNative(options.nativePath());
            installed = true;
        }

        RuntimeControllerV6.configure(instrumentation, options.settingsPath(), options.statusPath());
    }

    private static void ensureCompatibleRuntime(Instrumentation instrumentation) {
        String ownClassName = AgentMainV6.class.getName();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            String className = loadedClass.getName();
            // Some mixin classes can have unresolved enclosing-class metadata. Calling
            // Class.getSimpleName() on those classes forces resolution and can abort attach.
            int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
            String simpleName = className.substring(separator + 1);
            if (!className.equals(ownClassName)
                    && INCOMPATIBLE_AGENT_CLASSES.contains(simpleName)) {
                throw new IllegalStateException("Minecraft restart required before loading this trainer version");
            }
        }
    }

    private static void writeInitializationError(String arguments, Throwable throwable) {
        try {
            AgentOptions options = AgentOptions.decode(arguments);
            StringWriter text = new StringWriter();
            throwable.printStackTrace(new PrintWriter(text));
            Files.writeString(Path.of(options.statusPath() + ".init-error.txt"), text.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private record AgentOptions(Path hooksPath, Path settingsPath, Path statusPath, Path nativePath) {
        static AgentOptions decode(String encoded) throws IOException {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                String[] lines = decoded.split("\n", -1);
                if (lines.length != 4) throw new IOException("Invalid agent options");
                Path requestedStatus = Path.of(lines[2]).toAbsolutePath().normalize();
                Path directory = requestedStatus.getParent();
                if (directory == null) throw new IOException("Status path has no parent directory");
                return new AgentOptions(
                        Path.of(lines[0]), Path.of(lines[1]), directory.resolve(STATUS_FILE_NAME), Path.of(lines[3]));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid agent option encoding", exception);
            }
        }
    }
}
