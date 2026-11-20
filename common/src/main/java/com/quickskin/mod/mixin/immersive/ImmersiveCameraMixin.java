package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.9 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.entity.Entity;
//? if <1.21.11 {
import net.minecraft.world.level.BlockGetter;
//?} else {
import net.minecraft.world.level.Level;
//?}
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.quickskin.mod.client.immersive.ImmersiveMode;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Puts the game's camera where the immersive view wants it, after the game has placed it at the
 * player's eyes, and has the player drawn once the camera has left them. The camera type option
 * is never touched. Without this hook the screen floats before the first-person view.
 *
 * <p>The hook is the end of {@code Camera.setup} to 1.21.11 (handed a {@code Level} in place of a
 * {@code BlockGetter} from 1.21.11) and, from 26.1, where the camera updates itself once a frame,
 * the end of {@code Camera.alignWithEntity}, with the immersive field of view at the end of
 * {@code Camera.calculateFov} (earlier versions set it in {@code ImmersiveWorldViewMixin}).
 */
@Mixin(Camera.class)
public abstract class ImmersiveCameraMixin {
//? if <1.21 {
    @Shadow
    private boolean detached;

    @Shadow
    private net.minecraft.world.phys.Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        // The view leaves from, and returns to, wherever the game itself has just put the camera
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }
//?} else if <1.21.2 {
    @Shadow
    private boolean detached;

    @Shadow
    private net.minecraft.world.phys.Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        // The view leaves from, and returns to, wherever the game itself has just put the camera
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }
//?} else if <1.21.6 {
    @Shadow
    private boolean detached;

    @Shadow
    private net.minecraft.world.phys.Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        // The view leaves from, and returns to, wherever the game itself has just put the camera
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }
//?} else if <1.21.9 {
    @Shadow
    private boolean detached;

    @Shadow
    private net.minecraft.world.phys.Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        // The view leaves from, and returns to, wherever the game itself has just put the camera
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }
//?} else if <26.1 {
    @Shadow
    private boolean detached;

    @Shadow
    private net.minecraft.world.phys.Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    // Camera.setup is handed the level from 1.21.11, any block getter before
    //? if <1.21.11 {
    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        quickskin$place();
    }
    //?} else {
    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"), require = 0, expect = 1, allow = 1)
    private void quickskin$placeImmersiveCamera(Level level, Entity entity, boolean detached,
                                                boolean mirrored, float partialTick, CallbackInfo callback) {
        quickskin$place();
    }
    //?}

    /** The view leaves from, and returns to, wherever the game itself has just put the camera. */
    @Unique
    private void quickskin$place() {
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }
//?} else {
    @Shadow
    private boolean detached;

    @Shadow
    private Vec3 position;

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    // From 26.1 the camera updates itself once a frame (Camera.update): it lines up with the
    // entity, then works out its field of view and its frustum, so both are the camera's hooks.
    // NeoForge fires its camera-angle event inside alignWithEntity, before this
    @Inject(method = "alignWithEntity(F)V", at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$placeImmersiveCamera(float partialTicks, CallbackInfo callback) {
        // The view leaves from, and returns to, wherever the game itself has just put the camera
        ImmersiveMode.Camera placed = ImmersiveMode.place(this.position.x, this.position.y, this.position.z,
                this.yRot, this.xRot, this.detached);
        if (placed == null) {
            return;
        }
        this.detached = placed.showsPlayer();
        this.setRotation(placed.yaw(), placed.pitch());
        this.setPosition(placed.x(), placed.y(), placed.z());
    }

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private void quickskin$immersiveFov(float partialTicks, CallbackInfoReturnable<Float> callback) {
        float vanilla = callback.getReturnValueF();
        double fov = ImmersiveMode.fov(vanilla);
        if (fov != vanilla) {
            callback.setReturnValue((float) fov);
        }
    }
//?}
}
