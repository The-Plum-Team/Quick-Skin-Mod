package com.quickskin.mod.neoforge.mixin.compat;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Repairs one upstream Architectury 20.0.x descriptor before NeoForge reflects over its handlers.
 *
 * <p>Architectury's 26.1 artifacts were compiled against the BreakBlockEvent type introduced by
 * NeoForge 26.1.2, while their metadata also advertises 26.1 and 26.1.1. Those older runtimes expose
 * the equivalent BlockEvent.BreakEvent type. This transformer is deliberately structural and
 * version-pinned: an upstream bytecode change aborts startup instead of applying a partial patch.
 */
public final class ArchitecturyCompatMixinPlugin implements IMixinConfigPlugin {
    static final String TARGET_CLASS = "dev.architectury.event.forge.EventHandlerImplCommon";
    static final String MIXIN_SUFFIX = ".ArchitecturyEventHandlerCompatMixin";
    static final String INCOMPATIBLE_TYPE =
            "net/neoforged/neoforge/event/level/block/BreakBlockEvent";
    static final String COMPATIBLE_TYPE =
            "net/neoforged/neoforge/event/level/BlockEvent$BreakEvent";
    static final String PATCH_LOG_MARKER =
            "Quick Skin applied Architectury NeoForge 26.1 BreakEvent compatibility patch";

    private static final Logger LOGGER = LoggerFactory.getLogger("QuickSkin-ArchitecturyCompat");
    private static final String TARGET_RESOURCE = TARGET_CLASS.replace('.', '/') + ".class";
    private static final String INCOMPATIBLE_RESOURCE = INCOMPATIBLE_TYPE + ".class";
    private static final String COMPATIBLE_RESOURCE = COMPATIBLE_TYPE + ".class";
    private static final String INCOMPATIBLE_DESCRIPTOR = "L" + INCOMPATIBLE_TYPE + ";";
    private static final String COMPATIBLE_DESCRIPTOR = "L" + COMPATIBLE_TYPE + ";";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!TARGET_CLASS.equals(targetClassName) || !mixinClassName.endsWith(MIXIN_SUFFIX)) {
            throw new IllegalStateException(
                    "Unexpected mixin routed through the Architectury compatibility config: "
                            + mixinClassName + " -> " + targetClassName
            );
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return shouldPatch(
                resourceExists(loader, TARGET_RESOURCE),
                resourceExists(loader, INCOMPATIBLE_RESOURCE),
                resourceExists(loader, COMPATIBLE_RESOURCE)
        );
    }

    static boolean shouldPatch(boolean targetExists, boolean incompatibleTypeExists,
                               boolean compatibleTypeExists) {
        if (targetExists && !incompatibleTypeExists && compatibleTypeExists) {
            return true;
        }
        if (targetExists && incompatibleTypeExists) {
            return false;
        }
        throw new IllegalStateException(
                "Unsupported Architectury/NeoForge compatibility shape: target=" + targetExists
                        + ", BreakBlockEvent=" + incompatibleTypeExists
                        + ", BlockEvent$BreakEvent=" + compatibleTypeExists
        );
    }

    private static boolean resourceExists(ClassLoader loader, String path) {
        return loader != null && loader.getResource(path) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        if (!TARGET_CLASS.equals(targetClassName) || !mixinClassName.endsWith(MIXIN_SUFFIX)) {
            throw new IllegalStateException(
                    "Architectury compatibility callback received an unexpected target: "
                            + mixinClassName + " -> " + targetClassName
            );
        }
        applyCompatibilityTransform(targetClass);
        LOGGER.info("{} (method descriptors=1, invocation owners=7, local descriptors=1, frame types=1)",
                PATCH_LOG_MARKER);
    }

    static void applyCompatibilityTransform(ClassNode targetClass) {
        if (!TARGET_CLASS.replace('.', '/').equals(targetClass.name)) {
            throw new IllegalStateException("Wrong Architectury compatibility target: " + targetClass.name);
        }

        List<MethodNode> methodDescriptors = new ArrayList<>();
        List<MethodInsnNode> invocationOwners = new ArrayList<>();
        List<LocalVariableNode> localDescriptors = new ArrayList<>();
        List<FrameNode> frameTypes = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            boolean targetEventMethod = "event".equals(method.name)
                    && ("(" + INCOMPATIBLE_DESCRIPTOR + ")V").equals(method.desc);
            if (method.desc.contains(INCOMPATIBLE_DESCRIPTOR)) {
                if (!targetEventMethod) {
                    throw unexpected("method descriptor", method.name + method.desc);
                }
                methodDescriptors.add(method);
            }
            rejectText("method signature", method.signature);
            for (LocalVariableNode local : method.localVariables == null
                    ? List.<LocalVariableNode>of() : method.localVariables) {
                if (INCOMPATIBLE_DESCRIPTOR.equals(local.desc)) {
                    if (!targetEventMethod) {
                        throw unexpected("local descriptor", local.desc);
                    }
                    localDescriptors.add(local);
                } else {
                    rejectText("local descriptor", local.desc);
                }
                rejectText("local signature", local.signature);
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (INCOMPATIBLE_TYPE.equals(call.owner)) {
                        if (!targetEventMethod) {
                            throw unexpected("invocation owner", call.owner);
                        }
                        validateCompatibleInvocation(call);
                        invocationOwners.add(call);
                    } else {
                        rejectText("invocation owner", call.owner);
                    }
                    rejectText("invocation descriptor", call.desc);
                } else if (instruction instanceof FrameNode frame
                        && targetEventMethod
                        && frame.type == Opcodes.F_NEW
                        && frame.local != null
                        && frame.local.size() == 1
                        && INCOMPATIBLE_TYPE.equals(frame.local.get(0))
                        && frame.stack != null
                        && frame.stack.isEmpty()) {
                    frameTypes.add(frame);
                } else {
                    rejectUnexpectedInstructionReference(instruction);
                }
            }
        }

        if (methodDescriptors.size() != 1
                || invocationOwners.size() != 7
                || localDescriptors.size() != 1
                || frameTypes.size() != 1) {
            throw new IllegalStateException(
                    "Architectury compatibility target changed; expected 1/7/1/1 replacements, got "
                            + methodDescriptors.size() + "/" + invocationOwners.size() + "/"
                            + localDescriptors.size() + "/" + frameTypes.size()
            );
        }

        methodDescriptors.get(0).desc = "(" + COMPATIBLE_DESCRIPTOR + ")V";
        for (MethodInsnNode call : invocationOwners) call.owner = COMPATIBLE_TYPE;
        localDescriptors.get(0).desc = COMPATIBLE_DESCRIPTOR;
        frameTypes.get(0).local.set(0, COMPATIBLE_TYPE);
        assertNoIncompatibleReferences(targetClass);
    }

    private static void validateCompatibleInvocation(MethodInsnNode call) {
        String expectedDescriptor = switch (call.name) {
            case "getPlayer" -> "()Lnet/minecraft/world/entity/player/Player;";
            case "getLevel" -> "()Lnet/minecraft/world/level/LevelAccessor;";
            case "getPos" -> "()Lnet/minecraft/core/BlockPos;";
            case "getState" -> "()Lnet/minecraft/world/level/block/state/BlockState;";
            case "setCanceled" -> "(Z)V";
            default -> throw unexpected("invocation", call.name + call.desc);
        };
        if (!expectedDescriptor.equals(call.desc) || call.itf) {
            throw unexpected("invocation", call.name + call.desc);
        }
    }

    private static void rejectUnexpectedInstructionReference(AbstractInsnNode instruction) {
        if (instruction instanceof FieldInsnNode field) {
            rejectText("field owner", field.owner);
            rejectText("field descriptor", field.desc);
        } else if (instruction instanceof TypeInsnNode type) {
            rejectText("type instruction", type.desc);
        } else if (instruction instanceof MultiANewArrayInsnNode array) {
            rejectText("array descriptor", array.desc);
        } else if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
            rejectText("type constant", type.getDescriptor());
        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            rejectText("invokedynamic descriptor", dynamic.desc);
            rejectHandle(dynamic.bsm);
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Type type) rejectText("bootstrap type", type.getDescriptor());
                if (argument instanceof Handle handle) rejectHandle(handle);
            }
        } else if (instruction instanceof FrameNode frame) {
            rejectFrameValues(frame.local);
            rejectFrameValues(frame.stack);
        }
    }

    private static void rejectHandle(Handle handle) {
        rejectText("handle owner", handle.getOwner());
        rejectText("handle descriptor", handle.getDesc());
    }

    private static void rejectFrameValues(List<Object> values) {
        if (values == null) return;
        for (Object value : values) {
            if (value instanceof String text) rejectText("frame type", text);
        }
    }

    private static void assertNoIncompatibleReferences(ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            rejectText("remaining method descriptor", method.desc);
            rejectText("remaining method signature", method.signature);
            for (LocalVariableNode local : method.localVariables == null
                    ? List.<LocalVariableNode>of() : method.localVariables) {
                rejectText("remaining local descriptor", local.desc);
                rejectText("remaining local signature", local.signature);
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    rejectText("remaining invocation owner", call.owner);
                    rejectText("remaining invocation descriptor", call.desc);
                } else if (instruction instanceof FrameNode frame) {
                    rejectFrameValues(frame.local);
                    rejectFrameValues(frame.stack);
                } else {
                    rejectUnexpectedInstructionReference(instruction);
                }
            }
        }
    }

    private static void rejectText(String location, String value) {
        if (value != null && (value.contains(INCOMPATIBLE_TYPE)
                || value.contains(INCOMPATIBLE_DESCRIPTOR))) {
            throw unexpected(location, value);
        }
    }

    private static IllegalStateException unexpected(String location, String value) {
        return new IllegalStateException(
                "Unexpected Architectury BreakBlockEvent reference in " + location + ": " + value
        );
    }

}
