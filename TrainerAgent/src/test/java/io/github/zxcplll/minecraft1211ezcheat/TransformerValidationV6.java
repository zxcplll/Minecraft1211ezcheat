package io.github.zxcplll.minecraft1211ezcheat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class TransformerValidationV6 {
    private static final String HOOKS = "io/github/zxcplll/minecraft1211ezcheat/AgentHooksV6";

    private TransformerValidationV6() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected the mapped Minecraft jar path");
        RuntimeTransformerV7 transformer = new RuntimeTransformerV7();
        try (JarFile jar = new JarFile(arguments[0])) {
            validate(transformer, jar, "net/minecraft/world/entity/player/Player.class",
                    Map.of("afterNoPhysicsReset", 1, "afterPlayerTick", 1,
                            "scaleFlyingSpeed", 2, "preventFallDamage", 1));
            validate(transformer, jar, "net/minecraft/client/player/LocalPlayer.class",
                    Map.of("onLocalFlightStateChanged", 3));
            validate(transformer, jar, "net/minecraft/world/entity/Entity.class",
                    Map.of("shouldIgnoreCollision", 1));
            validate(transformer, jar, "com/mojang/blaze3d/systems/RenderSystem.class",
                    Map.of("beforeSwap", 1));
            validate(transformer, jar, "net/minecraft/client/renderer/LevelRenderer.class",
                    Map.of("captureWorld", 1));
        }
    }

    private static void validate(
            RuntimeTransformerV7 transformer,
            JarFile jar,
            String entryName,
            Map<String, Integer> expectedCalls) throws Exception {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) throw new IllegalStateException("Missing class: " + entryName);
        byte[] original = jar.getInputStream(entry).readAllBytes();
        String internalName = entryName.substring(0, entryName.length() - ".class".length());
        byte[] transformed = transformer.transform(null, internalName, null, null, original);
        byte[] secondPass = transformer.transform(null, internalName, null, null, transformed);
        if (!Arrays.equals(transformed, secondPass)) {
            throw new IllegalStateException("Transformer is not idempotent for " + internalName);
        }

        Map<String, Integer> actualCalls = new HashMap<>();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                        if (owner.equals(HOOKS)) actualCalls.merge(calledName, 1, Integer::sum);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!actualCalls.equals(expectedCalls)) {
            throw new IllegalStateException(
                    "Unexpected hooks for " + internalName + ": expected " + expectedCalls + ", got " + actualCalls);
        }
        System.out.printf("%s ORIGINAL=%d TRANSFORMED=%d IDEMPOTENT=true HOOKS=%s%n",
                internalName, original.length, transformed.length, actualCalls);
    }
}
