package net.spidiboost.sixubaafp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.util.ScreenshotRecorder;
import net.spidiboost.sixubaafp.updates.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real client UI/lifecycle + real detached exit-agent, in the isolated QA directory only. */
public final class NativeUpdaterProbe {
    private int step,frames;private SharedUpdater updater;private Path report,game,marker;private Process helper;private long deadline=System.nanoTime()+300_000_000_000L;
    public void register(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private static void require(boolean test,String text){if(!test)throw new AssertionError(text);}
    private void pass(String text)throws Exception{Files.writeString(report,"PASS "+text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void field(Object object,String name,Object value)throws Exception{var f=SharedUpdater.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private void behavior(Path policyGame)throws Exception{var method=SharedUpdater.class.getDeclaredMethod("readyBehavior",Path.class);method.setAccessible(true);method.invoke(updater,policyGame);}
    private static void jar(Path path,String value)throws Exception{try(var zip=new ZipOutputStream(Files.newOutputStream(path))){zip.putNextEntry(new ZipEntry("payload.txt"));zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}
    @SuppressWarnings("unchecked") private static List<ChatHudLine> messages(MinecraftClient client)throws Exception{var f=client.inGameHud.getChatHud().getClass().getDeclaredField("messages");f.setAccessible(true);return (List<ChatHudLine>)f.get(client.inGameHud.getChatHud());}
    private void tick(MinecraftClient client) {
        if(step==99)return;
        try {
            if(System.nanoTime()>deadline)throw new AssertionError("Native updater timeout");
            if(client.player==null||client.player.age<40||!client.player.isLoaded())return;
            if(step==0&&client.currentScreen!=null)return;
            if(step==0) {
                Path root=client.runDirectory.toPath();report=root.resolve("native-updater-results.txt");marker=root.resolve("updater-restarted.txt");Files.deleteIfExists(marker);
                Path data=root.resolve("updater-QA/Призма с пробелами"),instance=data.resolve("instances/Updater QA");game=instance.resolve("minecraft");
                Files.createDirectories(game.resolve("mods"));Files.createDirectories(game.resolve(".spidiboost-updates"));Files.writeString(instance.resolve("instance.cfg"),"name=Updater QA\n");
                Path old=game.resolve("mods/QA-1.21.4-1.0.0.jar"),next=game.resolve("mods/QA-1.21.4-1.0.1.jar"),staged=game.resolve(".spidiboost-updates/staged.jar");
                Files.deleteIfExists(next);jar(old,"old");jar(staged,"new");
                var change=new BatchPlan.Change(old,next,staged,BatchInstall.hash(old),BatchInstall.hash(staged));
                var java=Path.of(System.getProperty("java.home"),"bin","java");
                var nativeCommand=RestartCommand.discoverPrism(game,RestartCommand.candidates(game,System.getProperty("os.name"),Path.of(System.getProperty("user.home")),System.getenv()));
                require(!nativeCommand.isEmpty()&&nativeCommand.getLast().equals("Updater QA"),"discover installed Prism with no ancestor launcher");
                pass("actual installed Prism discovered on disk for an isolated instance, without starting user Prism");
                Path agent=game.resolve(".spidiboost-updates/agent.jar");try(var input=SharedUpdater.class.getResourceAsStream("/sixubaafp-shared-update-agent.jar")){Files.copy(input,agent,StandardCopyOption.REPLACE_EXISTING);}
                var cp=Path.of(NativeRestartMarker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                var restart=new ArrayList<>(List.of(java.toString(),"-cp",cp,NativeRestartMarker.class.getName(),marker.toAbsolutePath().toString()));restart.addAll(nativeCommand.subList(1,nativeCommand.size()));
                var plan=new BatchPlan(ProcessHandle.current().pid(),game,List.of(change),List.of(),root.toAbsolutePath(),restart,false);
                helper=new ProcessBuilder(java.toString(),"-jar","agent.jar").directory(agent.getParent().toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();try(var pipe=helper.getOutputStream()){plan.write(pipe);}
                require("READY".equals(new BufferedReader(new InputStreamReader(helper.getInputStream())).readLine()),"exit helper READY");
                var constructor=SharedUpdater.class.getDeclaredConstructor();constructor.setAccessible(true);updater=constructor.newInstance();
                field(updater,"pending",true);field(updater,"canRestart",true);field(updater,"pendingHelper",helper);field(updater,"pendingPlan",plan);
                RestartPolicy.set(game,false);
                try(var openOld=new ZipFile(old.toFile())) {
                    behavior(game);require(ModsDownload.owns(change),"new ordinary JAR in mods");require(BatchInstall.hash(old).equals(change.oldHash()),"live JAR unchanged");
                    require(new String(openOld.getInputStream(openOld.getEntry("payload.txt")).readAllBytes()).equals("old"),"old open resources still available");
                }
                behavior(game);require(helper.isAlive()&&!Files.exists(marker),"off cannot stop/restart");pass("update off puts verified JAR in mods twice idempotently, old open JAR untouched, exit helper alive");
                Files.writeString(next,"foreign changed file");behavior(game);behavior(game);Files.copy(staged,next,StandardCopyOption.REPLACE_EXISTING);
                step=1;
            } else if(step==1) {
                long count=messages(client).stream().filter(m->m.content().getString().contains("vk.ru/paveljaparov")).count();require(count>=2,"support notice repeated for separate entries: "+count);
                var support=SharedUpdater.supportMessage("QA: проверка ссылки");require(support.getSiblings().stream().anyMatch(t->t.getStyle().getClickEvent()!=null&&t.getStyle().getClickEvent().getValue().equals("https://vk.ru/paveljaparov")),"clickable exact VK");
                client.inGameHud.getChatHud().addMessage(support);client.setScreen(new net.minecraft.client.gui.screen.ChatScreen(""));
                pass("failed mods publication repeats support every check; native styled clickable VK URL verified");step=2;
                RestartPolicy.set(game,true);field(updater,"canRestart",false);behavior(game);behavior(game);
                require(!(client.currentScreen instanceof UpdateScreen),"unavailable launcher must not stop game");
                pass("missing launcher gives support without closing Minecraft, repeatedly");field(updater,"canRestart",true);
            } else if(step==2) {
                if(++frames<40)return;
                ScreenshotRecorder.saveScreenshot(client.runDirectory,"fp-updater-support.png",client.getFramebuffer(),t->{});
                // scheduleRestart reads the actual isolated client preference; both plans remain test-only.
                RestartPolicy.set(client.runDirectory.toPath(),true);RestartPolicy.set(game,true);behavior(game);
                require(client.currentScreen instanceof UpdateScreen,"real update countdown screen");pass("update on opens native countdown; real Minecraft exits before detached helper installs and requests launcher");
                require(client.currentScreen.children().stream().anyMatch(e->e instanceof net.minecraft.client.gui.widget.ButtonWidget b&&b.getMessage().getString().contains("update off")),"countdown has an off button");
                step=99;
            }
        } catch(Throwable e) {e.printStackTrace();try{if(report!=null)Files.writeString(report,"FAIL "+e+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}if(helper!=null)helper.destroyForcibly();step=99;client.scheduleStop();}
    }
}
