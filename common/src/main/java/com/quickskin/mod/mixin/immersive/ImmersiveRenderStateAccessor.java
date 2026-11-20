package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <26.1 {
//?} else {
//?}
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The render state an entity renderer fills from an entity and hands every draw of its type
 * ({@code EntityRenderer.reusedState}, 1.21.2 to 1.21.8). The immersive view draws the ghost of an
 * armour piece on the player from a state of its own while the world still draws the player from
 * that one, so it hands the renderer another for that call and puts the world's back
 * ({@code ImmersiveArmorRendererMixin}). From 1.21.9 every call makes a new state and nothing here
 * is needed.
 *
 * <p>Only 1.21.6 to 1.21.8 declare the accessors. 1.21.2 to 1.21.5 share that one state too, but
 * keep its fields round the ghost's draw and write them back instead ({@code WorldArmor.layersDrawn});
 * before 1.21.2 there is no render state. Empty elsewhere: an accessor names a field, and one that
 * is missing would stop the game. The setter is {@code @Mutable}, since the field is final.
 */
@Mixin(EntityRenderer.class)
public interface ImmersiveRenderStateAccessor {
//? if <1.21 {
//?} else if <1.21.2 {
//?} else if <1.21.6 {
//?} else if <1.21.9 {
    @Accessor("reusedState")
    EntityRenderState quickskin$reusedState();

    @Mutable
    @Accessor("reusedState")
    void quickskin$setReusedState(EntityRenderState state);
//?} else if <26.1 {
//?} else {
//?}
}
