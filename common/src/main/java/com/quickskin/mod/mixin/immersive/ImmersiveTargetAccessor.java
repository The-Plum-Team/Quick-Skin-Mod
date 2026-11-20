package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <26.1 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else {
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}
//? if <26.2 {
import net.minecraft.client.Minecraft;
//?} else {
import net.minecraft.client.renderer.GameRenderer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * The render target the game's GUI pass draws into. From 1.21.6 the GUI is drawn once a frame from
 * what the screens extracted, into {@code Minecraft.mainRenderTarget} (before 26.2) or
 * {@code GameRenderer.mainRenderTarget} (from 26.2): the immersive view swaps the floating
 * screen's own picture in for its own pass and back ({@code ImmersiveSurface.drawScreenPass}).
 *
 * <p>Declared from 1.21.6 (a {@code @Mutable} setter, since the field is final); empty before, where
 * the screen draws at once and its picture is bound while it does ({@code ImmersiveSurface}). An
 * accessor names a field, and one that is missing would stop the game, so a version declares it
 * only once it is checked against that version's class.
 */
//? if <26.2 {
@Mixin(Minecraft.class)
//?} else {
@Mixin(GameRenderer.class)
//?}
public interface ImmersiveTargetAccessor {
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
    @Mutable
    @Accessor("mainRenderTarget")
    void quickskin$setMainRenderTarget(RenderTarget target);
//?} else if <26.1 {
    @Mutable
    @Accessor("mainRenderTarget")
    void quickskin$setMainRenderTarget(RenderTarget target);
//?} else {
    /** Swaps the target the game's GUI pass draws into: the floating screen's picture, and back. */
    @Mutable
    @Accessor("mainRenderTarget")
    void quickskin$setMainRenderTarget(RenderTarget target);
//?}
}
