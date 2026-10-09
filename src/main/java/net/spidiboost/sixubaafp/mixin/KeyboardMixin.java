package net.spidiboost.sixubaafp.mixin;

import net.minecraft.client.Keyboard;
import net.spidiboost.sixubaafp.FpClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method="onKey",at=@At("HEAD"))
    private void fp$escape(long window,int key,int scanCode,int action,int modifiers,CallbackInfo ci){
        if(key==GLFW.GLFW_KEY_ESCAPE&&action==GLFW.GLFW_PRESS&&window==net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle()&&FpClient.INSTANCE!=null)FpClient.INSTANCE.escape();
    }
}
