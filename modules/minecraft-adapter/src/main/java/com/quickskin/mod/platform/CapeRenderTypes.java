package com.quickskin.mod.platform;

//? if <1.21.11 {
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?}

/** Native materials for the closed cape mesh, shared by world and GUI rendering. */
public final class CapeRenderTypes {
    private CapeRenderTypes() {
    }

    /**
     * Blend the visible face while culling the inside of the opposite face. A cape atlas may
     * contain an opaque back and a translucent front; a double-sided material draws that opaque
     * back through the front and hides the player's skin. Minecraft removed the old entity
     * factory in 1.21.2; its remaining culled translucent material also supports Fabulous's
     * item/entity target (and the GUI pipeline's explicit output target).
     */
    //? if <1.21.11 {
    public static RenderType translucent(ResourceLocation texture) {
    //?} else {
    public static RenderType translucent(Identifier texture) {
    //?}
        //? if <1.21.2 {
        return RenderType.entityTranslucentCull(texture);
        //?} else if <1.21.11 {
        return RenderType.itemEntityTranslucentCull(texture);
        //?} else if <26.1 {
        return RenderTypes.itemEntityTranslucentCull(texture);
        //?} else {
        return RenderTypes.entityTranslucentCullItemTarget(texture);
        //?}
    }
}
