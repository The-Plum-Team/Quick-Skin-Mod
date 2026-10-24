package com.quickskin.mod.mixin.nickname;

import com.quickskin.mod.runtime.NicknameDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player with a nickname is listed by it in the tab list, in the team's colours; the packet an
 * operator gets also shows the account name (ADR 0015). A tab-list name another mod set wins.
 * Clients show this name as it arrives, so players without Quick Skin see it too.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabNameMixin {
    @Inject(
            method = "getTabListDisplayName",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            expect = 1,
            allow = 1)
    private void quickskin$tabListNickname(CallbackInfoReturnable<Component> callback) {
        Component current = callback.getReturnValue();
        Component name = NicknameDisplay.tabListName((ServerPlayer) (Object) this, current);
        if (name != current) callback.setReturnValue(name);
    }
}
