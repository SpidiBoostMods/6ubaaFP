package net.spidiboost.sixubaafp.mixin;
import net.minecraft.client.MinecraftClient;
import net.spidiboost.sixubaafp.FpClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftClient.class)
public abstract class MinecraftMixin {
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/MinecraftClient;runTasks()V",shift=At.Shift.AFTER))
    private void fp$afterPackets(boolean tick,CallbackInfo ci){if(FpClient.INSTANCE!=null)FpClient.INSTANCE.pump();}
}
