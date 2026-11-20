package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <26.1 {
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else {
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.gen.Accessor;
    //? if <26.2 {
import net.minecraft.client.renderer.fog.FogRenderer;
    //?}
//?}
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * What the immersive view's own GUI pass needs of the game renderer from 1.21.6: its
 * {@code guiRenderer}, the fog it hands that renderer (before 26.2), the frame's
 * {@code guiRenderState} (before 26.1) and, in 26.x, {@code useUiLightmap}, which the game sets
 * only round its own GUI pass.
 *
 * <p>Declared from 1.21.6; empty before, where the GUI draws at once. An accessor names a field,
 * and one that is missing would stop the game, so a version declares it only once it is checked
 * against that version's class.
 */
@Mixin(GameRenderer.class)
public interface ImmersiveGameRendererAccessor {
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
    @Accessor("guiRenderer")
    GuiRenderer quickskin$guiRenderer();

    @Accessor("fogRenderer")
    FogRenderer quickskin$fogRenderer();

    @Accessor("guiRenderState")
    GuiRenderState quickskin$guiRenderState();
//?} else if <26.1 {
    @Accessor("guiRenderer")
    GuiRenderer quickskin$guiRenderer();

    @Accessor("fogRenderer")
    FogRenderer quickskin$fogRenderer();

    @Accessor("guiRenderState")
    GuiRenderState quickskin$guiRenderState();
//?} else {
    @Accessor("guiRenderer")
    GuiRenderer quickskin$guiRenderer();

    @Accessor("useUiLightmap")
    boolean quickskin$useUiLightmap();

    @Accessor("useUiLightmap")
    void quickskin$setUseUiLightmap(boolean use);
    //? if <26.2 {

    /** The fog the game hands its GUI pass, which takes it as an argument to 26.1.2. */
    @Accessor("fogRenderer")
    FogRenderer quickskin$fogRenderer();
    //?}
//?}
}
