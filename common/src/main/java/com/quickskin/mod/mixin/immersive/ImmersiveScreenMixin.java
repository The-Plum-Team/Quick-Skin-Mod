package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.quickskin.mod.client.immersive.ImmersiveMode;
import com.quickskin.mod.client.immersive.ImmersiveSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
    //? if <26.2 {
import net.minecraft.client.renderer.fog.FogRenderer;
    //?}
//?}
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * In the immersive view the open screen, with its tooltip, is drawn into a picture of its own
 * instead of over the world; the picture is then floated in the world. Forge draws the screen
 * through its own hook, so the screen's own drawing method is where both loaders meet.
 *
 * <p>The method is {@code renderWithTooltip} up to 1.21.8, {@code renderWithTooltipAndSubtitles}
 * from 1.21.9 and {@code extractRenderStateWithTooltipAndSubtitles} from 26.1. From 1.21.6 the
 * screen only extracts what it draws, so the picture is drawn by a GUI pass of its own right after
 * the screen's own call ({@code ImmersiveSurface.drawScreenPass}), and the mesh then goes into the
 * frame's GUI, with the tooltip and what the screen held back moved to the real mouse after it.
 * Every version hooks it: its start, right after the screen's own {@code render} (from 26.1 its
 * {@code extractRenderState}), and its end.
 */
@Mixin(Screen.class)
public abstract class ImmersiveScreenMixin {
//? if <1.21 {
    @Inject(method = "renderWithTooltip", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                   CallbackInfo callback) {
        if (ImmersiveMode.captures((Screen) (Object) this)) {
            ImmersiveSurface.begin(graphics);
        }
    }

    @Inject(method = "renderWithTooltip",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawAboveFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                    CallbackInfo callback) {
        if (ImmersiveSurface.capturing()) {
            // The tooltip, and whatever the screen held back to draw with it, floats beside the mouse
            ImmersiveSurface.beginAbove(graphics);
            ImmersiveMode.drawAbove();
        }
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                CallbackInfo callback) {
        ImmersiveSurface.end(graphics);
    }
//?} else if <1.21.2 {
    @Inject(method = "renderWithTooltip", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                   CallbackInfo callback) {
        if (ImmersiveMode.captures((Screen) (Object) this)) {
            ImmersiveSurface.begin(graphics);
        }
    }

    @Inject(method = "renderWithTooltip",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawAboveFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                    CallbackInfo callback) {
        if (ImmersiveSurface.capturing()) {
            // The tooltip, and whatever the screen held back to draw with it, floats beside the mouse
            ImmersiveSurface.beginAbove(graphics);
            ImmersiveMode.drawAbove();
        }
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                CallbackInfo callback) {
        ImmersiveSurface.end(graphics);
    }
//?} else if <1.21.6 {
    @Inject(method = "renderWithTooltip", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                   CallbackInfo callback) {
        if (ImmersiveMode.captures((Screen) (Object) this)) {
            ImmersiveSurface.begin(graphics);
        }
    }

    @Inject(method = "renderWithTooltip",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawAboveFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                    CallbackInfo callback) {
        if (ImmersiveSurface.capturing()) {
            // The tooltip, and whatever the screen held back to draw with it, floats beside the mouse
            ImmersiveSurface.beginAbove(graphics);
            ImmersiveMode.drawAbove();
        }
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                CallbackInfo callback) {
        ImmersiveSurface.end(graphics);
    }
//?} else if <1.21.9 {
    /**
     * Whether this screen is the one drawn into the floating picture this frame: a screen drawn
     * inside another one's call (a dialog drawing its parent) leaves the picture to the outer one.
     */
    @Unique
    private boolean quickskin$drawsPicture;
    /** Whether the tooltip's move to the real mouse was pushed onto the GUI's pose, to be popped at the end. */
    @Unique
    private boolean quickskin$aboveMoved;

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                   CallbackInfo callback) {
        quickskin$drawsPicture = ImmersiveMode.captures((Screen) (Object) this);
        if (quickskin$drawsPicture) {
            ImmersiveSurface.begin(() -> quickskin$drawGuiIntoPicture());
        }
    }

    /**
     * The screen has extracted itself, its tooltip not yet: the game's own GUI pass draws what was
     * extracted (the screen alone, the HUD being hidden) into the floating picture, which empties
     * the frame's GUI state; the mesh goes into it, and the tooltip after it, above the mesh and
     * moved from where the mouse points to where the mouse is.
     */
    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                               CallbackInfo callback) {
        if (!quickskin$drawsPicture || !ImmersiveSurface.capturing()) {
            return;
        }
        ImmersiveSurface.drawScreenPass(() -> quickskin$drawGuiIntoPicture());
        ImmersiveMode.drawOverlay();
        graphics.pose().pushMatrix();
        graphics.pose().translate(ImmersiveMode.aboveX(), ImmersiveMode.aboveY());
        quickskin$aboveMoved = true;
        ImmersiveMode.drawAbove();
    }

    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"),
            require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                CallbackInfo callback) {
        if (quickskin$aboveMoved) {
            quickskin$aboveMoved = false;
            graphics.pose().popMatrix();
        }
        if (quickskin$drawsPicture) {
            quickskin$drawsPicture = false;
            ImmersiveSurface.end();
        }
    }

    /**
     * Runs the game's GUI pass with the floating picture as the target it draws into: what was
     * extracted so far, the screen alone with the HUD hidden, or a dialog's floating parent alone
     * ({@code ImmersiveSurface.parentDrawn}).
     */
    @Unique
    private static void quickskin$drawGuiIntoPicture() {
        Minecraft minecraft = Minecraft.getInstance();
        ImmersiveGameRendererAccessor renderer = (ImmersiveGameRendererAccessor) minecraft.gameRenderer;
        ImmersiveTargetAccessor targets = (ImmersiveTargetAccessor) minecraft;
        RenderTarget main = minecraft.getMainRenderTarget();
        targets.quickskin$setMainRenderTarget(ImmersiveSurface.picture());
        try {
            renderer.quickskin$guiRenderer().render(renderer.quickskin$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        } finally {
            targets.quickskin$setMainRenderTarget(main);
        }
    }
//?} else if <26.1 {
    /** Whether the GUI's pose was moved for what the screen draws above itself, to be put back at the end. */
    @Unique
    private boolean quickskin$aboveMoved;

    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                   CallbackInfo callback) {
        if (ImmersiveMode.captures((Screen) (Object) this)) {
            ImmersiveSurface.begin(() -> quickskin$drawGuiIntoPicture());
        }
    }

    @Inject(method = "renderWithTooltipAndSubtitles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawFloatingPicture(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                               CallbackInfo callback) {
        if (!ImmersiveSurface.capturing()) {
            return;
        }
        // Everything extracted so far, the screen alone with the HUD hidden, is drawn into the
        // picture by the game's own GUI pass, which empties the frame's GUI for the rest
        ImmersiveSurface.drawScreenPass(() -> quickskin$drawGuiIntoPicture());
        ImmersiveSurface.end();
        // The picture floats in the emptied GUI; the tooltip and whatever the screen held back
        // follow above it, moved from where the mouse points to where the mouse is
        ImmersiveMode.drawOverlay();
        graphics.pose().pushMatrix();
        graphics.pose().translate(ImmersiveMode.aboveX(), ImmersiveMode.aboveY());
        quickskin$aboveMoved = true;
        ImmersiveMode.drawAbove();
    }

    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                CallbackInfo callback) {
        if (quickskin$aboveMoved) {
            quickskin$aboveMoved = false;
            graphics.pose().popMatrix();
        }
        ImmersiveSurface.end();
    }

    /**
     * Runs the game's GUI pass with the floating picture as the target it draws into: what was
     * extracted so far, the screen alone with the HUD hidden, or a dialog's floating parent alone
     * ({@code ImmersiveSurface.parentDrawn}).
     */
    @Unique
    private static void quickskin$drawGuiIntoPicture() {
        Minecraft minecraft = Minecraft.getInstance();
        ImmersiveGameRendererAccessor renderer = (ImmersiveGameRendererAccessor) minecraft.gameRenderer;
        RenderTarget main = minecraft.getMainRenderTarget();
        ((ImmersiveTargetAccessor) minecraft).quickskin$setMainRenderTarget(ImmersiveSurface.picture());
        try {
            renderer.quickskin$guiRenderer().render(renderer.quickskin$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        } finally {
            ((ImmersiveTargetAccessor) minecraft).quickskin$setMainRenderTarget(main);
        }
    }
//?} else {
    /** Whether this screen's extraction moved what it draws above itself to the real mouse. */
    @Unique
    private boolean quickskin$aboveMoved;

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$drawIntoFloatingPicture(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a,
                                                   CallbackInfo callback) {
        if (ImmersiveMode.captures((Screen) (Object) this)) {
            ImmersiveSurface.begin(() -> quickskin$drawGuiIntoPicture());
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
                    shift = At.Shift.AFTER),
            require = 0, expect = 1, allow = 1)
    private void quickskin$drawAboveFloatingPicture(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a,
                                                    CallbackInfo callback) {
        if (!ImmersiveSurface.capturing()) {
            return;
        }
        // Everything extracted so far is the screen (the HUD is hidden): the game's own GUI pass
        // draws it into the floating picture and leaves the frame's GUI empty for the mesh
        ImmersiveSurface.drawScreenPass(() -> quickskin$drawGuiIntoPicture());
        ImmersiveMode.drawOverlay();
        // The tooltip, and whatever the screen held back to draw with it, is drawn flat over the
        // mesh, moved from where the mouse points to where the mouse is
        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(ImmersiveMode.aboveX(), ImmersiveMode.aboveY());
        this.quickskin$aboveMoved = true;
        ImmersiveMode.drawAbove();
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$floatingPictureDrawn(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a,
                                                CallbackInfo callback) {
        if (this.quickskin$aboveMoved) {
            this.quickskin$aboveMoved = false;
            graphics.pose().popMatrix();
        }
        ImmersiveSurface.end();
    }

    /**
     * Runs the game's GUI pass with the floating picture as the target it draws into. The game
     * sets the GUI's lightmap and item lighting only round its own pass, which comes later in the
     * frame, so they are set round this one too.
     */
    @Unique
    private static void quickskin$drawGuiIntoPicture() {
        RenderTarget picture = ImmersiveSurface.picture();
        if (picture == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ImmersiveGameRendererAccessor renderer = (ImmersiveGameRendererAccessor) minecraft.gameRenderer;
        //? if <26.2 {
        ImmersiveTargetAccessor targets = (ImmersiveTargetAccessor) minecraft;
        RenderTarget main = minecraft.getMainRenderTarget();
        //?} else {
        ImmersiveTargetAccessor targets = (ImmersiveTargetAccessor) minecraft.gameRenderer;
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        //?}
        boolean uiLightmap = renderer.quickskin$useUiLightmap();
        targets.quickskin$setMainRenderTarget(picture);
        renderer.quickskin$setUseUiLightmap(true);
        try {
        //? if <26.2 {
            minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
            renderer.quickskin$guiRenderer().render(renderer.quickskin$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        //?} else {
            minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
            renderer.quickskin$guiRenderer().render();
        //?}
        } finally {
            targets.quickskin$setMainRenderTarget(main);
            renderer.quickskin$setUseUiLightmap(uiLightmap);
        }
    }
//?}
}
