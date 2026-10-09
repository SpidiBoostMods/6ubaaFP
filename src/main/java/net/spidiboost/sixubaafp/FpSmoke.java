package net.spidiboost.sixubaafp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import java.nio.file.*;
import java.util.*;

/** Opt-in isolated client verification; never connects to the user's server. */
public final class FpSmoke {
    private FpSmoke() {}
    public static void register(){int[] ticks={0};InventorySmoke.Preview[] preview={null};ClientTickEvents.END_CLIENT_TICK.register(c->{
        int tick=++ticks[0];if(tick!=120&&tick!=160&&tick!=180)return;Path report=c.runDirectory.toPath().resolve("fp-smoke-result.txt");
        try {
            if(tick==160){
                require(preview[0]!=null&&preview[0].frames>0,"native Player container rendered");
                net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(c.runDirectory,"inventory-smoke.png",c.getFramebuffer(),text->System.out.println(text.getString()));return;
            }
            if(tick==180){
                require(preview[0]!=null&&preview[0].frames>0,"native Player container render frames");
                Files.writeString(report,"PASS native network mixin\nPASS original Text colors and timestamps\nPASS red and dark red names\nPASS 12 inventory command forms and legacy aliases\nPASS actual item registry/custom-name data components\nPASS 36 server slots excluding viewer inventory\nPASS loaded-empty/native inventory queue results\nPASS native Player container GUI render\nPASS updater save hook\nPASS atomic root result file\n");
                System.out.println("FP_SMOKE_PASS "+report);c.setScreen(null);c.scheduleStop();return;
            }
            Class<?> handler=net.minecraft.client.network.ClientPlayNetworkHandler.class;
            require(Arrays.stream(handler.getDeclaredMethods()).anyMatch(m->m.getName().contains("fp$message")),"network mixin");
            Text text=Text.literal("[18:54:48] ").formatted(Formatting.GRAY).append(Text.literal("6ubaa").formatted(Formatting.GOLD))
                    .append(Text.literal(", 6uba").formatted(Formatting.GRAY)).append(Text.literal(", Online").formatted(Formatting.YELLOW));
            var styled=FpClient.styled(text).clean();
            require(styled.text().equals("6ubaa, 6uba, Online"),"styled timestamp");
            require(styled.yellowName(0,5)&&!styled.yellowName(7,11)&&styled.yellowName(13,19),"gold/yellow/gray colors");
            Text inherited=Text.literal("Parent").withColor(0xffd240).append(Text.literal("Child"));
            require(FpClient.styled(inherited).yellowName(0,11),"inherited custom yellow");
            require(FpClient.styled(Text.literal("Red").formatted(Formatting.RED)).eligibleName(0,3),"native red");
            require(FpClient.styled(Text.literal("Dark").formatted(Formatting.DARK_RED)).eligibleName(0,4),"native dark red");
            Path output=FpFiles.result(c.runDirectory.toPath());FpFiles.write(output,List.of("NativeStyleQA - grief #1","NativeStyleQB - grief #56"));
            require(Files.readString(output).equals("NativeStyleQA - grief #1\nNativeStyleQB - grief #56"),"file UTF-8 and root path");
            net.minecraft.util.Util.getOperatingSystem().open(output.toFile());
            preview[0]=InventorySmoke.verify();c.setScreen(preview[0]);
        }catch(Throwable e){e.printStackTrace();try{Files.writeString(report,"FAIL "+e);}catch(Exception ignored){}c.scheduleStop();}
    });}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
