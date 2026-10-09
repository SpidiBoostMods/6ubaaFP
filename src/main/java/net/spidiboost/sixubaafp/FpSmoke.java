package net.spidiboost.sixubaafp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import java.nio.file.*;
import java.util.*;

/** Opt-in isolated client verification; never connects to the user's server. */
public final class FpSmoke {
    private FpSmoke() {}
    public static void register(){int[] ticks={0};ClientTickEvents.END_CLIENT_TICK.register(c->{
        if(++ticks[0]!=120)return;Path report=c.runDirectory.toPath().resolve("fp-smoke-result.txt");
        try {
            Class<?> handler=net.minecraft.client.network.ClientPlayNetworkHandler.class;
            require(Arrays.stream(handler.getDeclaredMethods()).anyMatch(m->m.getName().contains("fp$message")),"network mixin");
            Text text=Text.literal("[18:54:48] ").formatted(Formatting.GRAY).append(Text.literal("6ubaa").formatted(Formatting.GOLD))
                    .append(Text.literal(", 6uba").formatted(Formatting.GRAY)).append(Text.literal(", Online").formatted(Formatting.YELLOW));
            var styled=FpClient.styled(text).clean();
            require(styled.text().equals("6ubaa, 6uba, Online"),"styled timestamp");
            require(styled.yellowName(0,5)&&!styled.yellowName(7,11)&&styled.yellowName(13,19),"gold/yellow/gray colors");
            Text inherited=Text.literal("Parent").withColor(0xffd240).append(Text.literal("Child"));
            require(FpClient.styled(inherited).yellowName(0,11),"inherited custom yellow");
            Path output=FpFiles.result(c.runDirectory.toPath());FpFiles.write(output,List.of("NativeStyleQA - grief #1","NativeStyleQB - grief #56"));
            require(Files.readString(output).equals("NativeStyleQA - grief #1\nNativeStyleQB - grief #56"),"file UTF-8 and root path");
            net.minecraft.util.Util.getOperatingSystem().open(output.toFile());
            Files.writeString(report,"PASS native network mixin\nPASS original Text colors, custom yellow, inherited colors and timestamps\nPASS atomic root result file\n");
            System.out.println("FP_SMOKE_PASS "+report);
        }catch(Throwable e){e.printStackTrace();try{Files.writeString(report,"FAIL "+e);}catch(Exception ignored){}}
        finally{c.scheduleStop();}
    });}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
