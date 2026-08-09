package io.github.zxcplll.minecraft1211ezcheat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

final class RuntimeTransformerV6 implements ClassFileTransformer {
    static final String PLAYER_CLASS = "net.minecraft.world.entity.player.Player";
    static final String LOCAL_PLAYER_CLASS = "net.minecraft.client.player.LocalPlayer";
    static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    static final String RENDER_SYSTEM_CLASS = "com.mojang.blaze3d.systems.RenderSystem";
    static final String LEVEL_RENDERER_CLASS = "net.minecraft.client.renderer.LevelRenderer";

    private static final String PLAYER_INTERNAL = "net/minecraft/world/entity/player/Player";
    private static final String LOCAL_PLAYER_INTERNAL = "net/minecraft/client/player/LocalPlayer";
    private static final String ABILITIES_INTERNAL = "net/minecraft/world/entity/player/Abilities";
    private static final String ENTITY_INTERNAL = "net/minecraft/world/entity/Entity";
    private static final String FALL_DAMAGE_DESCRIPTOR =
            "(FFLnet/minecraft/world/damagesource/DamageSource;)Z";
    private static final String RENDER_SYSTEM_INTERNAL = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String LEVEL_RENDERER_INTERNAL = "net/minecraft/client/renderer/LevelRenderer";
    private static final String HOOKS = "io/github/zxcplll/minecraft1211ezcheat/AgentHooksV6";
    private static final String LEVEL_RENDER_DESCRIPTOR =
            "(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;"
                    + "Lnet/minecraft/client/renderer/GameRenderer;"
                    + "Lnet/minecraft/client/renderer/LightTexture;"
                    + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";
    private static final Set<String> TARGETS = Set.of(
            PLAYER_CLASS, LOCAL_PLAYER_CLASS, ENTITY_CLASS, RENDER_SYSTEM_CLASS, LEVEL_RENDERER_CLASS);

    static boolean isTarget(String binaryName) {
        return TARGETS.contains(binaryName);
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classFileBuffer) {
        return switch (className) {
            case PLAYER_INTERNAL -> transformPlayer(classFileBuffer);
            case LOCAL_PLAYER_INTERNAL -> transformLocalPlayer(classFileBuffer);
            case ENTITY_INTERNAL -> transformEntity(classFileBuffer);
            case RENDER_SYSTEM_INTERNAL -> transformRenderSystem(classFileBuffer);
            case LEVEL_RENDERER_INTERNAL -> transformLevelRenderer(classFileBuffer);
            default -> null;
        };
    }

    private static byte[] transformLocalPlayer(byte[] bytes) {
        boolean existing = containsHook(bytes, "onLocalFlightStateChanged", "(Ljava/lang/Object;)V");
        int[] flightWrites = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("aiStep") || !descriptor.equals("()V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                        if (opcode == Opcodes.PUTFIELD
                                && owner.equals(ABILITIES_INTERNAL)
                                && fieldName.equals("flying")
                                && fieldDescriptor.equals("Z")) {
                            flightWrites[0]++;
                            if (!existing) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS,
                                        "onLocalFlightStateChanged",
                                        "(Ljava/lang/Object;)V",
                                        false);
                            }
                        }
                    }
                };
            }
        }, 0);
        if (!existing && flightWrites[0] < 1) {
            throw new IllegalStateException("Expected a LocalPlayer.aiStep flying toggle write, found " + flightWrites[0]);
        }
        return writer.toByteArray();
    }

    private static byte[] transformEntity(byte[] bytes) {
        boolean existing = containsHook(bytes, "shouldIgnoreCollision", "(Ljava/lang/Object;)Z");
        int[] moves = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("move")
                        || !descriptor.equals("(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V")) {
                    return delegate;
                }
                moves[0]++;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (!existing) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitInsn(Opcodes.DUP);
                            super.visitFieldInsn(Opcodes.GETFIELD, ENTITY_INTERNAL, "noPhysics", "Z");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS,
                                    "shouldIgnoreCollision", "(Ljava/lang/Object;)Z", false);
                            super.visitInsn(Opcodes.IOR);
                            super.visitFieldInsn(Opcodes.PUTFIELD, ENTITY_INTERNAL, "noPhysics", "Z");
                        }
                    }
                };
            }
        }, 0);
        if (!existing && moves[0] != 1) {
            throw new IllegalStateException("Expected one Entity.move method, found " + moves[0]);
        }
        return writer.toByteArray();
    }

    private static byte[] transformPlayer(byte[] bytes) {
        boolean[] existing = findPlayerHooks(bytes);
        int[] resetWrites = new int[1];
        int[] fallDamageMethods = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("causeFallDamage") && descriptor.equals(FALL_DAMAGE_DESCRIPTOR)) {
                    fallDamageMethods[0]++;
                    if (existing[3]) return delegate;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        private final org.objectweb.asm.Label continueOriginal = new org.objectweb.asm.Label();

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HOOKS,
                                    "preventFallDamage",
                                    "(Ljava/lang/Object;)Z",
                                    false);
                            super.visitJumpInsn(Opcodes.IFEQ, continueOriginal);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(continueOriginal);
                        }
                    };
                }
                if (name.equals("tick") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                            if (opcode == Opcodes.PUTFIELD
                                    && (owner.equals(PLAYER_INTERNAL) || owner.equals(ENTITY_INTERNAL))
                                    && fieldName.equals("noPhysics")
                                    && fieldDescriptor.equals("Z")) {
                                resetWrites[0]++;
                                if (!existing[0]) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS,
                                            "afterNoPhysicsReset", "(Ljava/lang/Object;)V", false);
                                }
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN && !existing[1]) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS,
                                        "afterPlayerTick", "(Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (name.equals("getFlyingSpeed") && descriptor.equals("()F")) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.FRETURN && !existing[2]) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS,
                                        "scaleFlyingSpeed", "(FLjava/lang/Object;)F", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return delegate;
            }
        }, 0);
        if (!existing[0] && resetWrites[0] != 1) {
            throw new IllegalStateException("Expected one Player.tick noPhysics reset, found " + resetWrites[0]);
        }
        if (fallDamageMethods[0] != 1) {
            throw new IllegalStateException("Expected one Player.causeFallDamage method, found " + fallDamageMethods[0]);
        }
        return writer.toByteArray();
    }

    private static byte[] transformRenderSystem(byte[] bytes) {
        boolean existing = containsHook(bytes, "beforeSwap", "(J)V");
        int[] swaps = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("flipFrame") || !descriptor.equals("(J)V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals("org/lwjgl/glfw/GLFW")
                                && methodName.equals("glfwSwapBuffers")
                                && methodDescriptor.equals("(J)V")) {
                            swaps[0]++;
                            if (!existing) {
                                super.visitInsn(Opcodes.DUP2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforeSwap", "(J)V", false);
                            }
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (!existing && swaps[0] != 1) {
            throw new IllegalStateException("Expected one GLFW swap call, found " + swaps[0]);
        }
        return writer.toByteArray();
    }

    private static byte[] transformLevelRenderer(byte[] bytes) {
        boolean existing = containsHook(
                bytes, "captureWorld", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
        int[] returns = new int[1];
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("renderLevel") || !descriptor.equals(LEVEL_RENDER_DESCRIPTOR)) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            returns[0]++;
                            if (!existing) {
                                super.visitVarInsn(Opcodes.ALOAD, 3);
                                super.visitVarInsn(Opcodes.ALOAD, 6);
                                super.visitVarInsn(Opcodes.ALOAD, 7);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        HOOKS,
                                        "captureWorld",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                        false);
                            }
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (!existing && returns[0] != 1) {
            throw new IllegalStateException("Expected one LevelRenderer.renderLevel return, found " + returns[0]);
        }
        return writer.toByteArray();
    }

    private static boolean[] findPlayerHooks(byte[] bytes) {
        boolean[] result = new boolean[4];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (!owner.equals(HOOKS)) return;
                        if (methodName.equals("afterNoPhysicsReset")) result[0] = true;
                        else if (methodName.equals("afterPlayerTick")) result[1] = true;
                        else if (methodName.equals("scaleFlyingSpeed")) result[2] = true;
                        else if (methodName.equals("preventFallDamage")) result[3] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static boolean containsHook(byte[] bytes, String method, String descriptor) {
        boolean[] result = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String methodDescriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode, String owner, String calledName, String calledDescriptor, boolean isInterface) {
                        if (owner.equals(HOOKS) && calledName.equals(method) && calledDescriptor.equals(descriptor)) {
                            result[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }
}
