package com.quickskin.mod.neoforge.mixin.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturyCompatMixinPluginTest {
    @Test
    void rewritesThePinnedArchitecturyHandlerShapeAndNothingElse() throws IOException {
        ClassNode target = loadTarget(ClassReader.EXPAND_FRAMES);
        FrameNode frame = incompatibleFrame(target);

        ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(target);
        assertEquals(ArchitecturyCompatMixinPlugin.COMPATIBLE_TYPE, frame.local.get(0));
        assertTrue(frame.stack.isEmpty());

        ClassWriter writer = new ClassWriter(0);
        target.accept(writer);
        ClassNode roundTrip = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(roundTrip, 0);
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(roundTrip),
                "a second application must fail rather than silently double-patch"
        );
    }

    @Test
    void definesNoNestedHelpersInsideTheMixinOwnedPackage() {
        assertEquals(0, ArchitecturyCompatMixinPlugin.class.getDeclaredClasses().length);
    }

    @Test
    void rejectsCompressedFramesThatHideTheRuntimeLocalShape() throws IOException {
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(loadTarget(0))
        );
    }

    @Test
    void rejectsEveryUnexpectedExpandedFrameShape() throws IOException {
        assertRejectedFrameMutation(frame -> frame.type = Opcodes.F_FULL);
        assertRejectedFrameMutation(frame -> frame.stack.add(
                ArchitecturyCompatMixinPlugin.INCOMPATIBLE_TYPE
        ));
        assertRejectedFrameMutation(frame -> frame.local.add("java/lang/Object"));
        assertRejectedFrameMutation(frame -> frame.local.set(
                0, "[L" + ArchitecturyCompatMixinPlugin.INCOMPATIBLE_TYPE + ";"
        ));
    }

    @Test
    void rejectsASecondCompatibleLookingFrame() throws IOException {
        ClassNode target = loadTarget(ClassReader.EXPAND_FRAMES);
        MethodNode event = targetEvent(target);
        event.instructions.insert(
                incompatibleFrame(target),
                exactIncompatibleFrame()
        );

        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(target)
        );
    }

    @Test
    void rejectsTheIncompatibleFrameOutsideTheExactEventMethod() throws IOException {
        ClassNode target = loadTarget(ClassReader.EXPAND_FRAMES);
        MethodNode event = targetEvent(target);
        MethodNode other = target.methods.stream()
                .filter(method -> method != event)
                .findFirst()
                .orElseThrow();
        other.instructions.insert(exactIncompatibleFrame());

        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(target)
        );
    }

    @Test
    void appliesOnlyToTheOldNeoForgeEventShape() {
        assertTrue(ArchitecturyCompatMixinPlugin.shouldPatch(true, false, true));
        assertFalse(ArchitecturyCompatMixinPlugin.shouldPatch(true, true, true));
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.shouldPatch(false, false, true)
        );
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.shouldPatch(true, false, false)
        );
    }

    private void assertRejectedFrameMutation(java.util.function.Consumer<FrameNode> mutation)
            throws IOException {
        ClassNode target = loadTarget(ClassReader.EXPAND_FRAMES);
        mutation.accept(incompatibleFrame(target));
        assertThrows(
                IllegalStateException.class,
                () -> ArchitecturyCompatMixinPlugin.applyCompatibilityTransform(target)
        );
    }

    private ClassNode loadTarget(int parsingOptions) throws IOException {
        String resource = ArchitecturyCompatMixinPlugin.TARGET_CLASS.replace('.', '/') + ".class";
        ClassNode target = new ClassNode();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing Architectury fixture " + resource);
            new ClassReader(input).accept(target, parsingOptions);
        }
        return target;
    }

    private MethodNode targetEvent(ClassNode target) {
        return target.methods.stream()
                .filter(method -> "event".equals(method.name)
                        && method.desc.contains(ArchitecturyCompatMixinPlugin.INCOMPATIBLE_TYPE))
                .findFirst()
                .orElseThrow();
    }

    private FrameNode incompatibleFrame(ClassNode target) {
        for (AbstractInsnNode instruction : targetEvent(target).instructions) {
            if (instruction instanceof FrameNode frame
                    && frame.local != null
                    && frame.local.contains(ArchitecturyCompatMixinPlugin.INCOMPATIBLE_TYPE)) {
                return frame;
            }
        }
        throw new AssertionError("Missing expanded Architectury BreakBlockEvent frame");
    }

    private FrameNode exactIncompatibleFrame() {
        return new FrameNode(
                Opcodes.F_NEW,
                1,
                new Object[]{ArchitecturyCompatMixinPlugin.INCOMPATIBLE_TYPE},
                0,
                new Object[0]
        );
    }
}
