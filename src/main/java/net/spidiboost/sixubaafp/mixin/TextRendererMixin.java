package net.spidiboost.sixubaafp.mixin;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.spidiboost.sixubaafp.FpTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @ModifyVariable(method="drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private OrderedText fp$gradient(OrderedText value){return FpTheme.animate(value);}
}
