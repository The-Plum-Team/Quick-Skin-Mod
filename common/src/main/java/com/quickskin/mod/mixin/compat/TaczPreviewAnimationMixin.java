package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets TaCZ animate the local player's reload, recoil, melee and gun switch on the third-person
 * model while the HUD preview shows that model in first person.
 *
 * <p>Timeless and Classics Zero keeps two kinds of third-person animation on its Player Animator
 * layers. The stances (hold, aim, crouch, crawl) are chosen in every draw of the player, so the HUD
 * preview has them in any camera. The actions are started once, from TaCZ's own gun events, in the
 * four handlers named below, and each handler returns early for the local player when the camera is
 * first person: nothing draws that model then, so TaCZ saves the work. With the HUD preview on,
 * something does draw it, and the preview stood still through a reload that F5 showed. The same
 * early return keeps a gun switch from resetting the stance, which left the previous gun's stance
 * on the preview.
 *
 * <p>This is the only hook into TaCZ. It redirects that one camera question, and answers "not
 * first person" only while the HUD preview is showing the local player with the gun
 * ({@link PlayerModelRenderer#previewShowsHeldGun}). TaCZ's handlers then run unchanged and choose
 * the animation themselves, exactly as they do in third person. In every other case - preview off,
 * HUD hidden, a menu preview, a render-state target - the answer is TaCZ's own.
 *
 * <p>Nothing of TaCZ or Player Animator is compiled against or called: the target and its
 * handlers are named by string, the redirected call and the handler use vanilla types only. It
 * fails open. Without TaCZ the optional config's plugin does not even prepare it (the target's
 * class file is looked up as a resource); without Player Animator TaCZ never loads the class; a
 * handler that was renamed, changed its event type or lost the check is left alone
 * ({@code require = 0}); a handler that became static no longer fits this instance method, Mixin
 * then rejects the mixin when TaCZ's class loads and the optional config turns that into one
 * warning. In each case behaviour is TaCZ's own. The first call in each handler is the only one
 * taken, so the count can never exceed the four handlers.
 *
 * <p>The animations are Player Animator's default first-person mode, so they do not reach the
 * first-person arms or TaCZ's first-person gun. They do play on the real local player, so anything
 * else that draws that player's model in first person shows them as well.
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.compat.playeranimator.animation.AnimationManager")
public abstract class TaczPreviewAnimationMixin {

    @Redirect(
            method = {
                    "onFire(Lcom/tacz/guns/api/event/common/GunShootEvent;)V",
                    "onReload(Lcom/tacz/guns/api/event/common/GunReloadEvent;)V",
                    "onMelee(Lcom/tacz/guns/api/event/common/GunMeleeEvent;)V",
                    "onDraw(Lcom/tacz/guns/api/event/common/GunDrawEvent;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z",
                    ordinal = 0,
                    remap = true
            ),
            require = 0,
            expect = 4,
            allow = 4,
            remap = false
    )
    private boolean quickskin$firstPersonUnlessPreviewed(CameraType camera) {
        return camera.isFirstPerson()
                && !PlayerModelRenderer.previewShowsHeldGun(Minecraft.getInstance().player);
    }
}
