package com.quickskin.mod.client.rendering;

//? if >=1.21.6 {
import com.mojang.blaze3d.platform.Lighting;
import com.quickskin.mod.platform.QuickSkinInfo;
//?} else {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector3f;

//? if >=1.21.6 {
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
//?}

/**
 * The light Quick Skin's previews of the player are drawn in.
 *
 * <p>Vanilla lights an entity in a GUI from above and behind the viewer's shoulder, which leaves a
 * model's front at 82% of its colours and its sides at half: a dark skin reads as a black shape.
 * A preview is there to show the skin, so it has a light of its own, two lamps in front of the
 * model, one to each side and a little above. Whatever faces the screen keeps all its colour, also
 * on a model turned up to about 45 degrees, as the title screen and the pause menu show it; a
 * face seen edge-on and the top keep most of theirs, and only what faces the floor stays dark, so
 * the model still reads as solid. The lamps stay with the screen while the model turns.
 *
 * <p>The preview's own model sets the light where it draws. A preview of the live player is drawn
 * by vanilla's renderer, which sets the inventory's light first, so the light is set again while
 * that draw poses the player's model ({@code PreviewSkinSwapPoseMixin}), and only for a draw
 * recognised as a preview: the inline draw before 1.21.6, or a render state or model a preview
 * handed to the GUI from 1.21.6. Every other draw keeps vanilla's light.
 */
@Environment(EnvType.CLIENT)
public final class PreviewLighting {

    /**
     * Where each lamp stands from the model, in the screen's axes (x to the right, y down, z out
     * of the screen), as a unit vector: {@code (±SIDE, -ABOVE, FRONT)}.
     */
    static final float SIDE = 0.592f;
    static final float ABOVE = 0.4f;
    static final float FRONT = 0.7f;

    /** The entity shader's shares: what a face keeps unlit, and what both lamps can add. */
    private static final float AMBIENT = 0.4f;
    private static final float DIFFUSE = 0.6f;

    private static final int BOUND_SUBJECTS = 16;

    private static boolean inline;
    private static final Object[] boundSubjects = new Object[BOUND_SUBJECTS];
    private static int nextBinding;

//? if <1.21 {
    // Before 1.21 a GUI model's normals keep pointing into the screen when its pose flips z
    private static final Vector3f LAMP_0 = new Vector3f(SIDE, -ABOVE, -FRONT);
    private static final Vector3f LAMP_1 = new Vector3f(-SIDE, -ABOVE, -FRONT);
//?} else {
    private static final Vector3f LAMP_0 = new Vector3f(SIDE, -ABOVE, FRONT);
    private static final Vector3f LAMP_1 = new Vector3f(-SIDE, -ABOVE, FRONT);
//?}
//? if >=1.21.6 {
    private static Lighting lamps;
    private static boolean unavailable;
//?}

    private PreviewLighting() {
    }

    /**
     * How much of its colour a face keeps under the preview's light, by the entity shader's rule.
     * The normal is in the screen's axes and need not be of unit length.
     */
    static float shade(float normalX, float normalY, float normalZ) {
        float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        float lit = 0.0f;
        if (length > 0.0f) {
            float common = (-ABOVE * normalY + FRONT * normalZ) / length;
            float sideways = SIDE * normalX / length;
            lit = Math.max(0.0f, common + sideways) + Math.max(0.0f, common - sideways);
        }
        return Math.min(1.0f, AMBIENT + DIFFUSE * lit);
    }

    /** Opens the inline draw of the live player for a preview, which vanilla's renderer lights. */
    static void beginInline() {
        inline = true;
    }

    static void endInline() {
        inline = false;
    }

    /**
     * Marks what a preview handed to the GUI, a render state or a model, which the GUI draws after
     * this frame is extracted. The last few are kept, since each frame hands over fresh ones, so
     * nothing has to release them.
     */
    static void bind(Object subject) {
        if (subject == null || isBound(subject)) {
            return;
        }
        boundSubjects[nextBinding] = subject;
        nextBinding = (nextBinding + 1) % BOUND_SUBJECTS;
    }

    private static boolean isBound(Object subject) {
        for (int i = 0; i < BOUND_SUBJECTS; i++) {
            if (boundSubjects[i] == subject) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the preview's light when the draw of {@code subject} (the entity, its render state or
     * the model handed to the GUI) is a preview's, after vanilla set its own and before the draw
     * reaches the screen.
     */
    public static void lightFor(Object subject) {
        if (inline || (subject != null && isBound(subject))) {
            apply();
        }
    }

    /** Sets the preview's light for what is drawn next. */
    static void apply() {
//? if <1.21.6 {
        RenderSystem.setShaderLights(LAMP_0, LAMP_1);
//?} else {
        Lighting own = lamps();
        if (own != null) {
            own.setupFor(Lighting.Entry.ENTITY_IN_UI);
        }
//?}
    }

//? if >=1.21.6 {
    /**
     * A set of lights of the preview's own, so the game's are left as they are. The game writes a
     * set's lamps through one private method, found by its shape since its name differs by
     * loader; without it the preview keeps vanilla's light.
     */
    private static Lighting lamps() {
        if (lamps != null || unavailable) {
            return lamps;
        }
        try {
            Lighting own = new Lighting();
            Method write = null;
            for (Method method : Lighting.class.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) && parameters.length == 3
                        && parameters[0] == Lighting.Entry.class
                        && parameters[1].isAssignableFrom(Vector3f.class)
                        && parameters[2].isAssignableFrom(Vector3f.class)) {
                    write = method;
                    break;
                }
            }
            if (write == null) {
                own.close();
                throw new NoSuchMethodException("Lighting has no method that writes an entry's lamps");
            }
            write.setAccessible(true);
            write.invoke(own, Lighting.Entry.ENTITY_IN_UI, LAMP_0, LAMP_1);
            lamps = own;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable = true;
            QuickSkinInfo.LOGGER.warn("Quick Skin's previews keep Minecraft's own light", error);
        }
        return lamps;
    }
//?}
}
