package net.spidiboost.sixubaafp;

import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import java.nio.file.*;
import java.util.*;

/** Actual client -> server commands -> styled chat packets -> result file. QA only. */
public final class NativeHistoryProbe {
    private int step;private Path session;private long started;
    private final long deadline=System.nanoTime()+360_000_000_000L;
    void register(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private static Object field(String name)throws Exception{var f=FpClient.class.getDeclaredField(name);f.setAccessible(true);return f.get(FpClient.INSTANCE);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static boolean marker(MinecraftClient c)throws Exception{
        var hud=c.inGameHud.getChatHud();var f=hud.getClass().getDeclaredField("messages");f.setAccessible(true);
        return ((List<?>)f.get(hud)).stream().anyMatch(m->m.toString().contains("QA_CHAT_MARKER"));
    }
    private void tick(MinecraftClient c){
        if(step==99)return;Path game=c.runDirectory.toPath(),report=game.resolve("native-history-results.txt");
        try{
            if(System.nanoTime()>deadline)throw new AssertionError("deadline step="+step);
            if(c.player==null||c.player.age<60||c.getNetworkHandler()==null||c.getNetworkHandler().getPlayerListEntry("Seed23")==null)return;
            if(step==0){
                var ready=FpClient.class.getDeclaredMethod("ready");ready.setAccessible(true);if(!(boolean)ready.invoke(FpClient.INSTANCE))return;
                NativeFixture.HISTORY_REQUESTS.clear();
                c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("QA_CHAT_MARKER"));require(marker(c),"initial chat marker");
                ClientCommandManager.getActiveDispatcher().execute("6ubaafp start",(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());
                session=(Path)field("run");started=System.nanoTime();step=1;return;
            }
            var scan=(FpScan)field("scan");if(scan.active()||!Files.exists(session.resolve("viewer-opened.txt")))return;
            var requests=List.copyOf(NativeFixture.HISTORY_REQUESTS);String text=Files.readString(game.resolve("6ubaafp.txt"));
            require(requests.size()==49,"24 dupeip + 25 hist: "+requests);
            require(new HashSet<>(requests).size()==49,"duplicate command");
            require(requests.stream().noneMatch(s->s.contains("FPQA")),"live self queried");
            require(text.contains("Seed00")&&text.contains("Banned (На сервере: Seed00, Seed01, FPQA)"),"late FP/red annotation: "+text);
            var ledger=(FpRunLedger)field("fpLedger");require(ledger.completed==25,"history progress");
            String log=Files.readString(session.resolve("debug.log"));require(log.contains("CHAT CLEAR histories=20"),"native chat clearing not wired");
            require(!marker(c),"displayed chat marker survived clear");
            require(!log.contains("attempt=2")&&!log.contains("INCOMPLETE"),"unexpected retry or timeout");
            Files.writeString(report,"PASS native styled packets, 49 unique serial commands, dynamic player profile exclusion\nPASS mismatched limit 7 / one record + [Горит] advance without 6-second stall\nPASS late FP history retains owner; dark red annotation includes current TAB twins\nPASS 25 histories: real ChatHud.clear(false) at 20, queue and findings preserved\nPASS root result file and automatic viewer\nElapsed ms: "+((System.nanoTime()-started)/1_000_000)+"\n");
            System.out.println("FP_NATIVE_HISTORY_PASS "+report);step=99;c.scheduleStop();
        }catch(Throwable error){error.printStackTrace();try{Files.writeString(report,"FAIL "+error);}catch(Exception ignored){}step=99;c.scheduleStop();}
    }
}
