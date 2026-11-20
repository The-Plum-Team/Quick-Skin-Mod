package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <1.21.2 {
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <1.21.6 {
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <1.21.9 {
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else if <26.1 {
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.gen.Accessor;
//?} else {
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Sets where the game believes the mouse is, which the immersive view does once per frame, and
 * reads the button the game counts as held (-1 for none), which is kept with a screen open too.
 *
 * <p>The held button is an {@code int} before 1.21.9 and a {@code MouseButtonInfo} (null for none)
 * from then; callers hand {@code ImmersiveMode} a boolean either way. Every version family
 * declares its own: an accessor names a field, and one that is missing would stop the game, so a
 * version declares them only once it is checked against that version's {@code MouseHandler}.
 */
@Mixin(MouseHandler.class)
public interface ImmersiveMouseAccessor {
//? if <1.21 {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    @Accessor("activeButton")
    int quickskin$activeButton();
//?} else if <1.21.2 {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    @Accessor("activeButton")
    int quickskin$activeButton();
//?} else if <1.21.6 {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    @Accessor("activeButton")
    int quickskin$activeButton();
//?} else if <1.21.9 {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    @Accessor("activeButton")
    int quickskin$activeButton();
//?} else if <26.1 {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    @Accessor("activeButton")
    MouseButtonInfo quickskin$activeButton();
//?} else {
    @Accessor("xpos")
    void quickskin$setXpos(double x);

    @Accessor("ypos")
    void quickskin$setYpos(double y);

    /** The button the game counts as held, or null. */
    @Accessor("activeButton")
    MouseButtonInfo quickskin$activeButton();
//?}
}
