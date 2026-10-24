//? if <1.21 {
package com.quickskin.mod.mixin;

import com.quickskin.mod.networking.MissingModNotice;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the language a 1.20.1 client reports, which vanilla discards, so the message for players
 * without Quick Skin is in their own language. Later versions keep it in {@code ClientInformation}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerLanguageMixin {

    // Without it the message is in English, nothing else.
    @Inject(method = "updateOptions", at = @At("HEAD"), require = 0, expect = 1, allow = 1)
    private void quickskin$rememberLanguage(ServerboundClientInformationPacket packet, CallbackInfo ci) {
        MissingModNotice.languageReported((ServerPlayer) (Object) this, packet.language());
    }
}
//?}
