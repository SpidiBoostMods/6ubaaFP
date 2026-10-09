package net.spidiboost.sixubaafp.mixin;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.spidiboost.sixubaafp.FpHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    protected ChatScreenMixin(Text title){super(title);}
    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true)
    private void fp$press(double x,double y,int button,CallbackInfoReturnable<Boolean> ci){if(FpHud.press(x,y,button))ci.setReturnValue(true);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){return FpHud.drag(x,y,button)||super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){return button==0&&FpHud.release()||super.mouseReleased(x,y,button);}
    @Inject(method="removed",at=@At("HEAD"))
    private void fp$save(CallbackInfo ci){FpHud.release();}
}
