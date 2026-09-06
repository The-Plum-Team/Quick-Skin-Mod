package com.quickskin.mod.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkinManagerReturnCountTest {
    @Test
    void asyncSkinHookCountsTheActualVanillaReturns() throws IOException {
        String mixinName = "com/quickskin/mod/mixin/SkinManagerMixin.class";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(mixinName)) {
            // Minecraft 1.20.1 uses a separate texture-registration overlay.
            if (input == null) return;
            ClassNode mixin = new ClassNode();
            new ClassReader(input).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            MethodNode handler = mixin.methods.stream()
                    .filter(method -> method.name.equals("quickskin$modifyGet")
                            || method.name.equals("quickskin$modifyGetOrLoad"))
                    .findFirst().orElseThrow();
            AnnotationNode inject = Stream.concat(
                            handler.visibleAnnotations == null ? Stream.empty() : handler.visibleAnnotations.stream(),
                            handler.invisibleAnnotations == null ? Stream.empty() : handler.invisibleAnnotations.stream())
                    .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                    .findFirst().orElseThrow();
            List<?> selectors = (List<?>) value(inject, "method");
            assertEquals(1, selectors.size());
            String selector = (String) selectors.get(0);
            try (InputStream vanilla = getClass().getClassLoader()
                    .getResourceAsStream("net/minecraft/client/resources/SkinManager.class")) {
                assertNotNull(vanilla);
                ClassNode owner = new ClassNode();
                new ClassReader(vanilla).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                List<MethodNode> targets = owner.methods.stream()
                        .filter(method -> method.name.equals(selector)).toList();
                assertEquals(1, targets.size(), "the asynchronous GameProfile lookup must be unambiguous");
                int returns = 0;
                for (var instruction : targets.get(0).instructions) {
                    if (instruction.getOpcode() == Opcodes.ARETURN) returns++;
                }
                assertEquals(returns, value(inject, "expect"), "debug validation must match vanilla bytecode");
                assertEquals(returns, value(inject, "allow"), "the upper bound must match vanilla bytecode");
            }
        }
    }

    private static Object value(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        }
        throw new AssertionError("missing injection field " + name);
    }
}
