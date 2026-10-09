package net.spidiboost.sixubaafp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static java.nio.file.StandardOpenOption.APPEND;

public final class NativeProbe implements ClientModInitializer {
    private int step;private long due;private final long deadline=System.nanoTime()+360_000_000_000L;
    private Path session;private boolean screenshot;private InventorySmoke.Preview preview;
    @Override public void onInitializeClient(){if(Boolean.getBoolean("sixubaafp.qa.updater"))new NativeUpdaterProbe().register();else if(Boolean.getBoolean("sixubaafp.qa.history"))new NativeHistoryProbe().register();else ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private static Object field(String name)throws Exception{var f=FpClient.class.getDeclaredField(name);f.setAccessible(true);return f.get(FpClient.INSTANCE);}
    private static InventoryScan scan()throws Exception{return (InventoryScan)field("inventory");}
    private static void command(MinecraftClient c,String value)throws Exception{ClientCommandManager.getActiveDispatcher().execute(value,(FabricClientCommandSource)c.getNetworkHandler().getCommandSource());}
    private static boolean guardReady()throws Exception{var f=(CompletableFuture<?>)field("watcher");if(f==null||!f.isDone()||f.isCompletedExceptionally())return false;var p=ResultWatch.class.getDeclaredField("process");p.setAccessible(true);return ((Process)p.get(f.join())).isAlive();}
    private static boolean worldReady()throws Exception{var m=FpClient.class.getDeclaredMethod("ready");m.setAccessible(true);return (boolean)m.invoke(FpClient.INSTANCE);}
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private void pass(Path game,String message)throws Exception{Files.writeString(game.resolve("native-results.txt"),"PASS "+message+"\n",APPEND);System.out.println("FP_NATIVE_PASS "+message);}
    private void tick(MinecraftClient c){
        if(step==99)return;Path game=c.runDirectory.toPath(),output=game.resolve("6ubaafp-inv.txt");
        try{
            if(System.nanoTime()>deadline)throw new AssertionError("Deadline step="+step+" screen="+c.currentScreen+" requests="+NativeFixture.REQUESTS);
            if(step==5){if(!Files.exists(session.resolve("viewer-opened.txt")))return;require(!scan().active(),"kick should stop");require(Files.readString(output).equals("AFound"),"kick checkpoint");pass(game,"real disconnect stops, preserves partial file and invokes text viewer");step=99;c.scheduleStop();return;}
            if(c.player==null||c.world==null||c.getNetworkHandler()==null||!c.player.isLoaded())return;
            switch(step){
                case 0->{
                    if(c.player.age<60||c.getNetworkHandler().getPlayerListEntry("DRetry")==null)return;
                    Files.writeString(game.resolve("native-results.txt"),"6ubaaFP 1.5.0 native protocol/UI checks\n");
                    require(ClientCommandManager.getActiveDispatcher().getRoot().getChild("6ubaa")==null,"removed alias");
                    command(c,"6ubaafp update off");require(!net.spidiboost.sixubaafp.updates.RestartPolicy.enabled(game),"off persisted");command(c,"6ubaafp update on");require(net.spidiboost.sixubaafp.updates.RestartPolicy.enabled(game),"on persisted");
                    pass(game,"only 6ubaafp registered; update on/off persists in native command dispatcher");
                    preview=InventorySmoke.verify();c.setScreen(preview);step=7;
                }
                case 7->{
                    if(preview.frames<40)return;
                    ScreenshotRecorder.saveScreenshot(c.runDirectory,"fp-design-preview.png",c.getFramebuffer(),t->{});
                    pass(game,"native registry/custom names, all command forms, offline red history, foreign color isolation, Player container and HUD rendered");
                    c.setScreen(null);command(c,"6ubaafp inv minecraft:compass");session=(Path)field("run");step=1;
                }
                case 1->{
                    if(!screenshot&&scan().progress().target().equals("DRetry")&&scan().progress().attempts()==2){ScreenshotRecorder.saveScreenshot(c.runDirectory,"fp-retry-hud.png",c.getFramebuffer(),t->{});screenshot=true;}
                    if(scan().active()||!Files.exists(session.resolve("viewer-opened.txt")))return;
                    require(scan().matches().equals(List.of("AFound","DRetry")),"matches "+scan().matches());
                    require(Collections.frequency(NativeFixture.REQUESTS,"DRetry")==3,"same-owner retry count "+NativeFixture.REQUESTS);
                    require(NativeFixture.REQUESTS.contains("EEmpty"),"continued after retry");require(Files.readString(output).equals("AFound DRetry"),"output");
                    pass(game,"real Player packets: matching, empty, Ошибка prefix skip, TAB departure, 3 retries, next nickname, viewer");
                    c.getNetworkHandler().sendChatCommand("qa_partial");step=2;
                }
                case 2->{if(c.getNetworkHandler().getPlayerListEntry("ZHang")==null)return;command(c,"6ubaafp inv minecraft:compass");session=(Path)field("run");step=3;}
                case 3->{
                    if(!scan().progress().target().equals("ZHang")||!guardReady())return;
                    require(Files.readString(output).equals("AFound"),"incremental checkpoint");
                    var chat=new net.minecraft.client.gui.screen.ChatScreen("");c.setScreen(chat);
                    int sw=c.getWindow().getScaledWidth(),sh=c.getWindow().getScaledHeight();var before=FpHud.bounds(sw,sh);
                    require(chat.mouseClicked(before.x()+8,before.y()+9,0),"actual ChatScreen press");
                    require(chat.mouseDragged(28,29,0,20-before.x(),20-before.y()),"actual ChatScreen drag");
                    require(chat.mouseReleased(28,29,0),"actual ChatScreen release");
                    var after=FpHud.bounds(sw,sh);require(after.x()==20&&after.y()==20,"drag coordinates "+after);
                    FpHud.configure(game.resolve("config/6ubaafp-hud.properties"));require(FpHud.bounds(sw,sh).equals(after),"saved HUD reload");
                    ScreenshotRecorder.saveScreenshot(c.runDirectory,"fp-draggable-hud.png",c.getFramebuffer(),t->{});
                    pass(game,"actual ChatScreen click/drag/release mixin, HUD config reload; AND counts and shorthand TAB native checks");
                    c.keyboard.onKey(c.getWindow().getHandle(),org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,org.lwjgl.glfw.GLFW.GLFW_PRESS,0);
                    require(!scan().active(),"actual Keyboard Esc stops inventory");due=System.nanoTime()+8_000_000_000L;step=4;
                }
                case 4->{
                    if(!Files.exists(session.resolve("viewer-opened.txt"))){if(System.nanoTime()>due)throw new AssertionError("stop viewer");return;}
                    require(!scan().active()&&Files.readString(output).equals("AFound"),"stop preserves");pass(game,"actual Esc immediately preserves already checked nickname and invokes text viewer");
                    if(!worldReady())return;
                    Path previous=session;command(c,"6ubaafp inv minecraft:compass");session=(Path)field("run");
                    require(!previous.equals(session)&&scan().active(),"fresh active scan must start before crash/kick");step=6;
                }
                case 6->{
                    if(!scan().active()||!scan().progress().target().equals("ZHang")||!guardReady())return;
                    require(Files.readString(output).equals("AFound"),"kick incremental checkpoint");
                    if(Boolean.getBoolean("sixubaafp.qa.crash")){
                        require(!Files.exists(session.resolve("viewer-opened.txt")),"fresh guard has not opened yet");
                        Files.writeString(game.resolve("native-crash-session.txt"),session.toAbsolutePath().toString());
                        pass(game,"hard JVM exit armed after a verified checkpoint and READY child watchdog");
                        // This opt-in fixture exists only in the isolated QA source set.
                        // Deliberately skip all lifecycle hooks to verify independent crash recovery.
                        Runtime.getRuntime().halt(0);return;
                    }
                    c.getNetworkHandler().sendChatCommand("qa_kick");step=5;
                }
            }
        }catch(Throwable error){error.printStackTrace();try{Files.writeString(game.resolve("native-results.txt"),"FAIL "+error+"\n",APPEND);}catch(Exception ignored){}step=99;c.scheduleStop();}
    }
}
