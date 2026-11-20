package com.quickskin.mod.mixin.immersive;

//? if <1.21 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.2 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <1.21.6 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else if <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if <26.1 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.WorldLook;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if <1.21.2 {
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
//?} else {
import net.minecraft.client.renderer.entity.layers.WingsLayer;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * On the cape menu's Elytra tab and in the cape editor's Elytra view, the player in the immersive
 * view shows the texture on elytra wings, as the screen's own model would, whether they wear an
 * elytra or not ({@code WorldLook.wings}). The layer is handed an elytra to draw in place of the
 * chest item it found, and the texture in place of the one it chose; a worn elytra keeps its own
 * item, and its glint. While those screens show a cape instead, the layer is handed no chest item,
 * so a worn elytra does not cover that cape ({@code WorldLook.hidesWings}). To 1.21.1 vanilla, Forge
 * and NeoForge read the chest item into the layer's first local and pass the texture to
 * {@code RenderType.armorCutoutNoCull}, which the hooks replace; either hook missing leaves the
 * wings as the player wears them.
 *
 * <p>From 1.21.2 the layer is {@code WingsLayer}, which reads the chest item from the render state
 * and draws through {@code EquipmentLayerRenderer.renderLayers} with a texture override. To 1.21.5
 * the state itself is handed the elytra (or no chest item) at the start of the layer's
 * {@code render} and given its own item back at the end, and the texture is the answer of
 * {@code getPlayerElytraTexture}. From 1.21.6 the layer's own chest-item local is replaced again
 * (its first {@code ItemStack} store, in {@code render} to 1.21.8 and {@code submit} from 1.21.9),
 * and the texture is the one handed to {@code renderLayers}.
 */
//? if <1.21.2 {
@Mixin(ElytraLayer.class)
//?} else {
@Mixin(WingsLayer.class)
//?}
public abstract class ImmersiveLookElytraMixin {
//? if <1.21 {
    /** The wings this draw shows in place of the worn ones, from the chest item to the texture. */
    @Unique
    private ResourceLocation quickskin$lookWings;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$noWingsYet(CallbackInfo callback) {
        // A draw another mod cut short between the two hooks below must not pass its wings on
        quickskin$lookWings = null;
    }

    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wingsOfTheLook(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                              int packedLight, LivingEntity entity) {
        quickskin$lookWings = WorldLook.wings(entity);
        if (quickskin$lookWings == null) {
            return WorldLook.hidesWings(entity) ? ItemStack.EMPTY : worn;
        }
        if (worn.is(Items.ELYTRA)) {
            return worn;
        }
        return new ItemStack(Items.ELYTRA);
    }

    @ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;armorCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0, expect = 1, allow = 1)
    private ResourceLocation quickskin$textureOfTheLook(ResourceLocation chosen) {
        ResourceLocation wings = quickskin$lookWings;
        quickskin$lookWings = null;
        return wings != null ? wings : chosen;
    }
//?} else if <1.21.2 {
    /** The wings this draw shows in place of the worn ones, from the chest item to the texture. */
    @Unique
    private ResourceLocation quickskin$lookWings;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$noWingsYet(CallbackInfo callback) {
        // A draw another mod cut short between the two hooks below must not pass its wings on
        quickskin$lookWings = null;
    }

    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wingsOfTheLook(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                              int packedLight, LivingEntity entity) {
        quickskin$lookWings = WorldLook.wings(entity);
        if (quickskin$lookWings == null) {
            return WorldLook.hidesWings(entity) ? ItemStack.EMPTY : worn;
        }
        if (worn.is(Items.ELYTRA)) {
            return worn;
        }
        return new ItemStack(Items.ELYTRA);
    }

    @ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;armorCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0, expect = 1, allow = 1)
    private ResourceLocation quickskin$textureOfTheLook(ResourceLocation chosen) {
        ResourceLocation wings = quickskin$lookWings;
        quickskin$lookWings = null;
        return wings != null ? wings : chosen;
    }
//?} else if <1.21.6 {
    // WingsLayer reads the chest item from the render state and takes its texture from
    // getPlayerElytraTexture: the state is handed an elytra (or nothing) for the length of the
    // layer's own draw, and the texture is the look's. The armour layer, drawn before, keeps the
    // chest item the state holds.

    /** The state whose chest item this draw changed, and the item to give it back. */
    @Unique
    @Nullable
    private HumanoidRenderState quickskin$changedState;
    @Unique
    private ItemStack quickskin$wornChest = ItemStack.EMPTY;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$wingsOfTheLook(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                          HumanoidRenderState state, float yRot, float xRot, CallbackInfo callback) {
        // A draw another mod cut short leaves its item behind: never handed back to a state the
        // game has since extracted again, perhaps for another player (one state serves every
        // player of a model type), and the next extraction replaces it anyway
        quickskin$changedState = null;
        quickskin$wornChest = ItemStack.EMPTY;
        ItemStack worn = quickskin$chest(state);
        if (WorldLook.wings(state) != null) {
            if (!worn.is(Items.ELYTRA)) {
                quickskin$changeChest(state, worn, new ItemStack(Items.ELYTRA));
            }
        } else if (WorldLook.hidesWings(state) && !worn.isEmpty()) {
            quickskin$changeChest(state, worn, ItemStack.EMPTY);
        }
    }

    @Inject(method = "getPlayerElytraTexture(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN"), cancellable = true, require = 0, expect = 1)
    private static void quickskin$textureOfTheLook(HumanoidRenderState state,
                                                   CallbackInfoReturnable<ResourceLocation> callback) {
        ResourceLocation wings = WorldLook.wings(state);
        if (wings != null) {
            callback.setReturnValue(wings);
        }
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("RETURN"), require = 0, expect = 1)
    private void quickskin$wingsDrawn(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                      HumanoidRenderState state, float yRot, float xRot, CallbackInfo callback) {
        quickskin$giveChestBack();
    }

    @Unique
    private void quickskin$changeChest(HumanoidRenderState state, ItemStack worn, ItemStack shown) {
        quickskin$changedState = state;
        quickskin$wornChest = worn;
        quickskin$setChest(state, shown);
    }

    @Unique
    private void quickskin$giveChestBack() {
        if (quickskin$changedState != null) {
            quickskin$setChest(quickskin$changedState, quickskin$wornChest);
            quickskin$changedState = null;
            quickskin$wornChest = ItemStack.EMPTY;
        }
    }

    @Unique
    private static ItemStack quickskin$chest(HumanoidRenderState state) {
        //? if <1.21.4 {
        return state.chestItem;
        //?} else {
        return state.chestEquipment;
        //?}
    }

    @Unique
    private static void quickskin$setChest(HumanoidRenderState state, ItemStack item) {
        //? if <1.21.4 {
        state.chestItem = item;
        //?} else {
        state.chestEquipment = item;
        //?}
    }
//?} else if <1.21.9 {
    // WingsLayer reads the chest item off the render state into its first local and hands the
    // texture to EquipmentLayerRenderer.renderLayers as the player's own (null: the asset's);
    // a hidden chest piece is already missing from the state, which the look's wings ignore
    /** The wings this draw shows in place of the worn ones, from the chest item to the texture. */
    @Unique
    private ResourceLocation quickskin$lookWings;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$noWingsYet(CallbackInfo callback) {
        // A draw another mod cut short between the two hooks below must not pass its wings on
        quickskin$lookWings = null;
    }

    @ModifyVariable(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wingsOfTheLook(ItemStack worn, PoseStack poseStack, MultiBufferSource buffers,
                                              int packedLight, HumanoidRenderState state) {
        quickskin$lookWings = WorldLook.wings(state);
        if (quickskin$lookWings == null) {
            return WorldLook.hidesWings(state) ? ItemStack.EMPTY : worn;
        }
        if (worn.is(Items.ELYTRA)) {
            return worn;
        }
        return new ItemStack(Items.ELYTRA);
    }

    @ModifyArg(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/resources/ResourceLocation;)V"),
            index = 7, require = 0, expect = 1, allow = 1)
    private ResourceLocation quickskin$textureOfTheLook(ResourceLocation chosen) {
        ResourceLocation wings = quickskin$lookWings;
        quickskin$lookWings = null;
        return wings != null ? wings : chosen;
    }
//?} else if <26.1 {
    // WingsLayer.submit reads the chest item from the render state, which already lacks an elytra
    // the player hides (ADR 0017), and hands EquipmentLayerRenderer.renderLayers the texture that
    // the elytra asset's player-texture layer takes. The look's wings need neither the worn elytra
    // nor the chest slot: the first hook hands the layer an elytra, the second the look's texture.
    /** The wings this submit shows in place of the worn ones, from the chest item to the texture. */
    @Unique
//? if <1.21.11 {
    private ResourceLocation quickskin$lookWings;
//?} else {
    private Identifier quickskin$lookWings;
//?}

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$noWingsYet(CallbackInfo callback) {
        // A submit another mod cut short between the two hooks below must not pass its wings on
        quickskin$lookWings = null;
    }

    @ModifyVariable(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wingsOfTheLook(ItemStack worn, PoseStack poseStack, SubmitNodeCollector collector,
                                              int packedLight, HumanoidRenderState state) {
        quickskin$lookWings = WorldLook.wings(state);
        if (quickskin$lookWings == null) {
            return WorldLook.hidesWings(state) ? ItemStack.EMPTY : worn;
        }
        if (worn.is(Items.ELYTRA)) {
            return worn;
        }
        return new ItemStack(Items.ELYTRA);
    }

    @ModifyArg(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE",
//? if <1.21.11 {
                    target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/ResourceLocation;II)V"),
//?} else {
                    target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V"),
//?}
            index = 8, require = 0, expect = 1, allow = 1)
//? if <1.21.11 {
    private ResourceLocation quickskin$textureOfTheLook(ResourceLocation chosen) {
        ResourceLocation wings = quickskin$lookWings;
//?} else {
    private Identifier quickskin$textureOfTheLook(Identifier chosen) {
        Identifier wings = quickskin$lookWings;
//?}
        quickskin$lookWings = null;
        return wings != null ? wings : chosen;
    }
//?} else {
    // WingsLayer.submit reads the chest item from the render state, which already lacks a piece
    // the player hides (ADR 0017), and hands EquipmentLayerRenderer.renderLayers the texture the
    // elytra asset's player-texture layer takes. The look's wings need neither the worn elytra
    // nor the chest slot: the first hook hands the layer an elytra, the second the look's texture.
    /** The wings this submit shows in place of the worn ones, from the chest item to the texture. */
    @Unique
    private Identifier quickskin$lookWings;

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$noWingsYet(CallbackInfo callback) {
        // A submit another mod cut short between the two hooks below must not pass its wings on
        quickskin$lookWings = null;
    }

    @ModifyVariable(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("STORE"), ordinal = 0, require = 0, expect = 1, allow = 1)
    private ItemStack quickskin$wingsOfTheLook(ItemStack worn, PoseStack poseStack, SubmitNodeCollector collector,
                                              int packedLight, HumanoidRenderState state) {
        quickskin$lookWings = WorldLook.wings(state);
        if (quickskin$lookWings == null) {
            return WorldLook.hidesWings(state) ? ItemStack.EMPTY : worn;
        }
        if (worn.is(Items.ELYTRA)) {
            return worn;
        }
        return new ItemStack(Items.ELYTRA);
    }

    @ModifyArg(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer;renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V"),
            index = 8, require = 0, expect = 1, allow = 1)
    private Identifier quickskin$textureOfTheLook(Identifier chosen) {
        Identifier wings = quickskin$lookWings;
        quickskin$lookWings = null;
        return wings != null ? wings : chosen;
    }
//?}
}
