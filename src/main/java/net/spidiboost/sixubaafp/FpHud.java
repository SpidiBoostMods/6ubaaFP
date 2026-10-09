package net.spidiboost.sixubaafp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.nio.file.Path;

/** Compact, local-only scan telemetry. No full-screen overlays during ordinary retries. */
public final class FpHud {
    private FpHud(){}
    private static HudPlacement placement;
    public static void configure(Path path){placement=new HudPlacement(path);}
    public static HudPlacement.Bounds bounds(int sw,int sh){int width=Math.min(248,Math.min(sw-24,Math.max(170,sw/2-24)));return placement.bounds(sw,sh,width,83);}
    public static boolean press(double x,double y,int button){var c=MinecraftClient.getInstance();return button==0&&FpClient.INSTANCE!=null&&FpClient.INSTANCE.hudVisible()&&placement!=null&&placement.press(x,y,bounds(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight()));}
    public static boolean drag(double x,double y,int button){var c=MinecraftClient.getInstance();if(button!=0||placement==null)return false;var b=bounds(c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight());return placement.drag(x,y,c.getWindow().getScaledWidth(),c.getWindow().getScaledHeight(),b.width(),b.height());}
    public static boolean release(){if(placement==null)return false;try{return placement.release();}catch(java.io.IOException e){MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(FpTheme.error("Не удалось сохранить положение панели."));return true;}}
    public record View(String mode,String target,String detail,int done,int total,int matches,int skipped,int grief,boolean active){}
    public static void draw(DrawContext c,View v){
        var client=MinecraftClient.getInstance();if(client.options.hudHidden)return;
        int width=Math.min(248,Math.min(c.getScaledWindowWidth()-24,Math.max(170,c.getScaledWindowWidth()/2-24))),height=83;if(width<145)return;
        // Keep the panel out of bottom-left chat, right-side scoreboard and vanilla toasts.
        // GUI scale changes logical dimensions, so width adapts instead of covering half the screen.
        if(placement==null)configure(client.runDirectory.toPath().resolve("config/6ubaafp-hud.properties"));
        var bounds=bounds(c.getScaledWindowWidth(),c.getScaledWindowHeight());int x=bounds.x(),y=bounds.y();
        panel(c,x,y,width,height,0xe8152024);c.fill(x+10,y+11,x+12,y+26,0xffffbc82);
        c.drawTextWithShadow(client.textRenderer,FpTheme.title("6ubaaFP"),x+20,y+11,0xffffff);
        String label=v.grief()>0?"ГРИФ "+v.grief()+" / 56":v.mode();
        c.drawTextWithShadow(client.textRenderer,Text.literal(label).withColor(0x91b3a5),x+width-client.textRenderer.getWidth(label)-10,y+12,0xffffff);
        String target=v.target().isBlank()?"Подготовка маршрута":v.target();
        c.drawTextWithShadow(client.textRenderer,FpTheme.small(fit(target,width-20)),x+10,y+31,0xffffff);
        c.drawTextWithShadow(client.textRenderer,Text.literal(fit(v.detail(),width-20)).withColor(0x8eaaa0),x+10,y+44,0xffffff);
        int bar=width-20;c.fill(x+10,y+59,x+10+bar,y+62,0xff30433e);
        int filled=v.total()==0?0:Math.clamp((int)((long)v.done()*bar/v.total()),0,bar);
        for(int i=0;i<filled;i++)c.fill(x+10+i,y+59,x+11+i,y+62,0xff000000|FpTheme.mix(0xffb878,0x8dddb6,(double)i/Math.max(1,bar-1)));
        String stats=v.done()+"/"+v.total()+" · найдено "+v.matches()+" · пропуск "+v.skipped();
        c.drawTextWithShadow(client.textRenderer,Text.literal(fit(stats,width-20)).withColor(0xcfddcb),x+10,y+68,0xffffff);
    }
    private static String fit(String value,int width){return MinecraftClient.getInstance().textRenderer.trimToWidth(value,width);}
    private static void panel(DrawContext c,int x,int y,int w,int h,int color){c.fill(x+4,y,x+w-4,y+h,color);c.fill(x+2,y+2,x+w-2,y+h-2,color);c.fill(x,y+4,x+w,y+h-4,color);c.fill(x+5,y,x+w-5,y+1,0x666fb497);}
}
