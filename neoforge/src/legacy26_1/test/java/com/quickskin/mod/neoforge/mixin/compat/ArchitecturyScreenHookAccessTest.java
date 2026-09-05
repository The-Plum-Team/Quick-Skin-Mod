package com.quickskin.mod.neoforge.mixin.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitecturyScreenHookAccessTest {
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String SCREEN_HOOKS =
            "dev/architectury/hooks/client/screen/forge/ScreenHooksImpl";
    private static final Set<String> EXPECTED_METHODS = Set.of(
            "addRenderableWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                    + "Lnet/minecraft/client/gui/components/events/GuiEventListener;",
            "addRenderableOnly(Lnet/minecraft/client/gui/components/Renderable;)"
                    + "Lnet/minecraft/client/gui/components/Renderable;",
            "addWidget(Lnet/minecraft/client/gui/components/events/GuiEventListener;)"
                    + "Lnet/minecraft/client/gui/components/events/GuiEventListener;"
    );

    @Test
    void accessTransformerCoversTheExactPinnedArchitecturyScreenCalls() throws IOException {
        Set<String> transformedMethods = loadAccessTransformerMethods();
        assertEquals(EXPECTED_METHODS, transformedMethods);

        ClassNode screen = loadClass(SCREEN);
        Set<String> availableMethods = new HashSet<>();
        for (MethodNode method : screen.methods) {
            String signature = method.name + method.desc;
            if (EXPECTED_METHODS.contains(signature)) {
                assertTrue(availableMethods.add(signature), "duplicate Screen method " + signature);
            }
        }
        assertEquals(EXPECTED_METHODS, availableMethods);

        ClassNode hooks = loadClass(SCREEN_HOOKS);
        Set<String> invokedMethods = new HashSet<>();
        int invocationCount = 0;
        for (MethodNode method : hooks.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode invocation
                        && SCREEN.equals(invocation.owner)) {
                    invocationCount++;
                    assertTrue(
                            invokedMethods.add(invocation.name + invocation.desc),
                            "duplicate Architectury Screen invocation"
                    );
                }
            }
        }
        assertEquals(EXPECTED_METHODS, invokedMethods);
        assertEquals(EXPECTED_METHODS.size(), invocationCount);
    }

    private Set<String> loadAccessTransformerMethods() throws IOException {
        InputStream input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/accesstransformer.cfg"
        );
        assertNotNull(input, "missing packaged NeoForge access transformer");
        Set<String> methods = new HashSet<>();
        try (input;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8)
             )) {
            for (String rawLine; (rawLine = reader.readLine()) != null; ) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                assertEquals(3, parts.length, "invalid access-transformer rule: " + line);
                assertEquals("public", parts[0]);
                assertEquals(SCREEN.replace('/', '.'), parts[1]);
                assertTrue(methods.add(parts[2]), "duplicate access-transformer rule: " + line);
            }
        }
        return methods;
    }

    private ClassNode loadClass(String internalName) throws IOException {
        String resource = internalName + ".class";
        ClassNode target = new ClassNode();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("Missing bytecode fixture " + resource);
            new ClassReader(input).accept(target, 0);
        }
        return target;
    }
}
