package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.platform.QuickSkinInfo;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    // In MC 1.21.11+, CapeLayer has its own cape model (PlayerCapeModel) separate from PlayerModel
    @Shadow @Final private HumanoidModel<?> model;

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void quickskin$renderCustomCape(PoseStack poseStack, SubmitNodeCollector buffer, int packedLight,
                                            AvatarRenderState renderState, float yRot, float xRot,
                                            CallbackInfo ci) {
        // The GUI preview renders the real player entity, so without this the cape below would be
        // the one the player is wearing rather than the one the editor has selected. The preview
        // binds its cape to this draw only; an unbound draw keeps resolving the applied cape.
        PreviewCapeBindings.Resolution<Identifier> quickskin$preview =
                PlayerModelRenderer.consumePreviewCape(renderState);
        if (quickskin$preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel(); // The editor has no cape selected: show none, do not fall back to the worn one.
            return;
        }
        boolean quickskin$previewing =
                quickskin$preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

        // Don't render cape when elytra is equipped. Read that off the render state rather than the
        // live entity: in the world the state carries exactly what the player has on, so the rule is
        // unchanged, while in a preview the renderer has already blanked the state's equipment, so
        // the cape being previewed is never hidden by gear the preview is not drawing either.
        if (!quickskin$previewing && renderState.chestEquipment.is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
            }
        }

        if (playerUUID == null && !quickskin$previewing) {
            return; // Can't identify player, let vanilla logic run
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Check service-based cape
        boolean hasServiceCape = !quickskin$previewing && service.hasActiveCape(playerUUID);

        // Check config-based cape for local player (works both title screen and in-world)
        boolean hasConfigCape = false;
        boolean isLocalPlayer = mc.player != null && mc.player.getUUID().equals(playerUUID);
        if (!quickskin$previewing && !hasServiceCape && isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            hasConfigCape = !config.activeCapeHash.isEmpty();
        }

        if (!quickskin$previewing && !hasServiceCape && !hasConfigCape) {
            return; // No cape from either source, let vanilla handle
        }

        String capeId = quickskin$previewing ? null : service.getCapeId(playerUUID);
        // Visibility must be recorded before the getter can return null while a bounded network
        // first-frame texture is being prepared; this also drives bounded activation retry.
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

        // A bound preview replaces the worn cape outright.
        Identifier capeTexture = quickskin$previewing ? quickskin$preview.texture() : null;
        if (!quickskin$previewing) {
            if (hasServiceCape) {
                capeTexture = service.getCapeLocation(playerUUID);
            }
            if (capeTexture == null && isLocalPlayer) {
                ClientConfig config = ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
            if (capeTexture == null) {
                // Fall back to render state's skin cape
                if (renderState.skin != null && renderState.skin.cape() != null) {
                    capeTexture = renderState.skin.cape().texturePath();
                }
            }
        }

        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        Identifier finalTexture = CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
        if (finalTexture == null) {
            // A network animation deliberately renders nothing until its bounded first-frame
            // texture is ready; never expose the stacked atlas as a cape.
            ci.cancel();
            return;
        }

        RenderType renderType;

        if (finalTexture.getNamespace().equals(QuickSkinInfo.MOD_ID)) {
            renderType = RenderTypes.entityTranslucent(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = RenderTypes.entityTranslucent(finalTexture);
            } else {
                renderType = RenderTypes.entitySolid(finalTexture);
            }
        }

        // Replicate the vanilla cape rendering logic with our custom render type
        // In MC 1.21.11, CapeLayer uses SubmitNodeCollector.submitModel() instead of renderToBuffer()
        @SuppressWarnings("unchecked")
        HumanoidModel<AvatarRenderState> capeModel = (HumanoidModel<AvatarRenderState>) (HumanoidModel<?>) this.model;
        capeModel.setupAnim(renderState);

        // Submit the cape model with our custom render type
        buffer.submitModel(capeModel, renderState, poseStack, renderType, packedLight,
                OverlayTexture.NO_OVERLAY, renderState.outlineColor, null);

        ci.cancel();
    }
}
