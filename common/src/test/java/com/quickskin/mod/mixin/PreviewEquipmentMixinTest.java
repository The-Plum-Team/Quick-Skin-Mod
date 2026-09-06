package com.quickskin.mod.mixin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreviewEquipmentMixinTest {
    @Test
    void activeEquipmentHookTargetsOneDeclaredConcreteVanillaMethod() throws IOException {
        try (InputStream input = resource("quickskin.mixins.json")) {
            boolean active = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("PreviewEquipmentMixin"));
            if (!active) {
                return;
            }
        }

        ClassNode mixin = readClass("com/quickskin/mod/mixin/PreviewEquipmentMixin");
        AnnotationNode target = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        List<?> owners = (List<?>) value(target, "value");
        assertEquals(1, owners.size());
        ClassNode owner = readClass(((Type) owners.get(0)).getInternalName());
        MethodNode handler = mixin.methods.stream()
                .filter(method -> method.name.equals("quickskin$suppressPreviewEquipment"))
                .findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, handler.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        List<?> selectors = (List<?>) value(inject, "method");
        assertEquals(1, selectors.size());
        String selector = (String) selectors.get(0);
        List<MethodNode> targets = owner.methods.stream()
                .filter(method -> (method.name + method.desc).equals(selector)).toList();
        assertEquals(1, targets.size(), owner.name + " must declare the equipment injection target");
        assertEquals(0, targets.get(0).access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC),
                "equipment suppression requires a concrete instance method");
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible,
                                             String descriptor) {
        return Stream.concat(visible == null ? Stream.empty() : visible.stream(),
                        invisible == null ? Stream.empty() : invisible.stream())
                .filter(annotation -> annotation.desc.equals(descriptor)).findFirst().orElseThrow();
    }

    private static Object value(AnnotationNode annotation, String name) {
        assertNotNull(annotation.values);
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError("missing annotation field " + name);
    }

    private static ClassNode readClass(String name) throws IOException {
        try (InputStream input = resource(name + ".class")) {
            ClassNode result = new ClassNode();
            new ClassReader(input).accept(result, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            assertEquals(name, result.name);
            return result;
        }
    }

    private static InputStream resource(String name) {
        InputStream input = PreviewEquipmentMixinTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, "missing packaged dependency " + name);
        return input;
    }
}
