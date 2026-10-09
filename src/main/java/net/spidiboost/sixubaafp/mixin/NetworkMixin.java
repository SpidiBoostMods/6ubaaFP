package net.spidiboost.sixubaafp.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.*;
import net.spidiboost.sixubaafp.FpClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class NetworkMixin {
    @Inject(method="onGameMessage",at=@At("HEAD"))
    private void fp$message(GameMessageS2CPacket p,CallbackInfo ci){if(MinecraftClient.getInstance().isOnThread()&&FpClient.INSTANCE!=null)FpClient.INSTANCE.message(p.content(),p.overlay());}
    @Inject(method="onOpenScreen",at=@At("TAIL")) private void fp$open(OpenScreenS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.opened(p.getSyncId(),p.getName().getString());}
    @Inject(method="onInventory",at=@At("TAIL")) private void fp$items(InventoryS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.contents();}
    @Inject(method="onScreenHandlerSlotUpdate",at=@At("TAIL")) private void fp$slot(ScreenHandlerSlotUpdateS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.contents();}
    @Inject(method="onCloseScreen",at=@At("TAIL")) private void fp$close(CloseScreenS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.closed(p.getSyncId());}
    @Inject(method="onGameJoin",at=@At("TAIL")) private void fp$join(GameJoinS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.worldChanged();}
    @Inject(method="onPlayerRespawn",at=@At("TAIL")) private void fp$respawn(PlayerRespawnS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.worldChanged();}
    @Inject(method="onEnterReconfiguration",at=@At("TAIL")) private void fp$config(EnterReconfigurationS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.worldChanged();}
    @Inject(method="onPlayerPositionLook",at=@At("TAIL")) private void fp$pos(PlayerPositionLookS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.positionChanged();}
    @Inject(method="onPlayerList",at=@At("TAIL")) private void fp$tab(PlayerListS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.tabChanged();}
    @Inject(method="onPlayerRemove",at=@At("TAIL")) private void fp$remove(PlayerRemoveS2CPacket p,CallbackInfo ci){FpClient.INSTANCE.tabChanged();}
}
