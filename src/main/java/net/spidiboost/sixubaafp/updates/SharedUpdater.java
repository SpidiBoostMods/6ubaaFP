package net.spidiboost.sixubaafp.updates;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.*;
import org.slf4j.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Each mod bundles its own relocated copy. ObjectShare elects one process-wide coordinator. */
public final class SharedUpdater {
    public static final String OWNER="spidiboost:update-coordinator-v1";
    public static final String RESERVATION="spidiboost:update-reservation-v2";
    private static final Logger LOG=LoggerFactory.getLogger("SpidiBoost-updater");
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"SpidiBoost-updates");t.setDaemon(true);return t;});
    private final ExecutorService downloads=Executors.newFixedThreadPool(4,r->{var t=new Thread(r,"SpidiBoost-download");t.setDaemon(true);return t;});
    private final AtomicBoolean restartQueued=new AtomicBoolean();private volatile boolean pending,canRestart;
    private final UpdateChecks checks=new UpdateChecks();
    private final Set<String> failedChecks=ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> retry;
    @FunctionalInterface interface Fetcher { byte[] get(java.net.URI uri,int limit)throws Exception; }
    private final Fetcher http;
    SharedUpdater(){this(new GitHubDownload()::get);}
    SharedUpdater(Fetcher http){this.http=http;}
    private final Map<String,UpdateRow> rows=new ConcurrentHashMap<>();
    private final Map<String,BatchPlan.Change> prepared=new ConcurrentHashMap<>();
    private volatile Process pendingHelper;
    private volatile BatchPlan pendingPlan;
    private record Installed(ModCatalog mod,String version,Path jar){}
    public static void preserve(String id,Runnable save){FabricLoader.getInstance().getObjectShare().put("spidiboost:update-save-"+id,save);}
    public static boolean claim(net.fabricmc.loader.api.ObjectShare share,Runnable owner){
        Object reservation=share.get(RESERVATION);
        if(reservation instanceof List<?> list&&list.size()==2&&share.get(OWNER)==list.getFirst()&&list.get(1) instanceof java.util.concurrent.atomic.AtomicReference<?> gate){
            Object preferred=share.get("spidiboost:update-preferred-sixubaafp-v3");
            if(preferred instanceof java.util.concurrent.atomic.AtomicReference<?> ref) {
                if(!SharedUpdater.class.getPackageName().equals("net.spidiboost.sixubaafp.updates"))return false;
                @SuppressWarnings("unchecked") var typed=(java.util.concurrent.atomic.AtomicReference<Runnable>)ref;
                return typed.compareAndSet(null,owner);
            }
            @SuppressWarnings("unchecked") var typed=(java.util.concurrent.atomic.AtomicReference<Runnable>)gate;return typed.compareAndSet(null,owner);
        }
        return share.putIfAbsent(OWNER,owner)==null;
    }
    public static void requestCheck(){Object r=FabricLoader.getInstance().getObjectShare().get(OWNER);if(r instanceof Runnable run)run.run();}
    public static void preferenceChanged(){Object r=FabricLoader.getInstance().getObjectShare().get("spidiboost:update-restart-v2");if(r instanceof Runnable run)run.run();}
    public static Map<String,String> status(String id){
        Object value=FabricLoader.getInstance().getObjectShare().get("spidiboost:update-status-v2:"+id);
        var result=new LinkedHashMap<String,String>();if(value instanceof Map<?,?> m)m.forEach((k,v)->{if(k instanceof String a&&v instanceof String b)result.put(a,b);});return Map.copyOf(result);
    }
    public static void initialize() {
        var loader=FabricLoader.getInstance();
        // Runnable is a JDK interface: relocated packages and mod loading order cannot break election.
        var updater=new SharedUpdater();
        if(!claim(loader.getObjectShare(),()->updater.check(UpdateChecks.Mode.DISCOVERY))){updater.worker.shutdown();updater.downloads.shutdown();return;}
        loader.getObjectShare().put("spidiboost:update-restart-v2",(Runnable)()->updater.check(UpdateChecks.Mode.ACTIVATE));
        if(loader.isDevelopmentEnvironment()){
            for(var spec:ModCatalog.ALL)loader.getModContainer(spec.id()).ifPresent(m->updater.publish(spec.id(),m.getMetadata().getVersion().getFriendlyString(),"","development","Проверка GitHub отключена в тестовом окружении"));
            updater.worker.shutdown();updater.downloads.shutdown();return;
        }
        ClientLifecycleEvents.CLIENT_STARTED.register(c->updater.check(UpdateChecks.Mode.ACTIVATE));
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->updater.check(UpdateChecks.Mode.ACTIVATE));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->{updater.worker.shutdownNow();updater.downloads.shutdownNow();});
    }
    private List<Installed> installed(Path game)throws IOException {
        var loader=FabricLoader.getInstance();var list=new ArrayList<Installed>();
        for(var spec:ModCatalog.ALL) {
            var found=loader.getModContainer(spec.id());if(found.isEmpty())continue;var mod=found.get();String version=mod.getMetadata().getVersion().getFriendlyString();
            rows.put(spec.id(),new UpdateRow(spec.id(),spec.name(),version,"Проверяем обновление…"));
            publish(spec.id(),version,"","checking","Проверяем GitHub…");
            var paths=mod.getOrigin().getPaths();Path jar=null;
            if(paths.size()==1){Path p=paths.getFirst();if(!Files.isSymbolicLink(p)&&Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".jar")){Path real=p.toRealPath();if(real.getParent().equals(game.resolve("mods").toRealPath()))jar=real;}}
            if(jar==null){rows.put(spec.id(),new UpdateRow(spec.id(),spec.name(),version,"Внешний или вложенный JAR: обновление вручную"));publish(spec.id(),version,"","external","JAR не в mods этого инстанса: обновление вручную");}
            else list.add(new Installed(spec,version,jar));
        }return list;
    }
    public List<UpdateRow> snapshot(){return ModCatalog.ALL.stream().map(m->rows.get(m.id())).filter(Objects::nonNull).toList();}
    private void state(Installed m,String v,String status){rows.put(m.mod().id(),new UpdateRow(m.mod().id(),m.mod().name(),v,status));}
    private void publish(String id,String installed,String latest,String state,String text){FabricLoader.getInstance().getObjectShare().put("spidiboost:update-status-v2:"+id,Map.of("installed",installed,"latest",latest,"state",state,"status",text));}
    public static Text supportMessage(String reason) {
        return Text.empty().append(UpdateScreen.gradient("6ubaaFP · обновление"))
                .append(Text.literal("\n"+reason+" Игра продолжит работать.\n").styled(s->s.withColor(0xffd8ad)))
                .append(Text.literal("Обратиться за помощью → vk.ru/paveljaparov").styled(s->s.withColor(0x79dfb3)
                        .withUnderline(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL,"https://vk.ru/paveljaparov"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Text.literal("Открыть страницу поддержки в VK")))));
    }
    private void notice(String reason,boolean support) {
        var client=MinecraftClient.getInstance();
        client.execute(()->{Text text=support?supportMessage(reason):Text.empty().append(UpdateScreen.gradient("6ubaaFP · ")).append(Text.literal(reason).styled(s->s.withColor(0xffd8ad)));
            client.inGameHud.getChatHud().addMessage(text);});
    }
    private void readyBehavior(Path game) {
        if(RestartPolicy.enabled(game)) {
            if(canRestart)MinecraftClient.getInstance().execute(this::scheduleRestart);
            else notice("Новая версия найдена, но безопасный запуск лаунчера не определён.",true);
        } else {
            try {
                if(pendingHelper==null||!pendingHelper.isAlive()||pendingPlan==null)throw new IOException("Exit helper unavailable");
                ModsDownload.publish(pendingPlan);
                for(var c:pendingPlan.changes())for(var mod:ModCatalog.ALL)if(c.destination().getFileName().toString().startsWith(mod.name()+"-")) {
                    var old=status(mod.id());publish(mod.id(),old.getOrDefault("installed",""),old.getOrDefault("latest",""),"downloaded","Новая версия в mods; старая удалится после выхода. Без перезапуска.");
                }
                notice("Обновление скачано в mods. Помощник удалит старый JAR после выхода; автоперезапуск выключен.",false);
            } catch(IOException e) { LOG.warn("Could not publish prepared update to mods ({})",e.getClass().getSimpleName());
                notice("Не удалось безопасно положить обновление в mods.",true); }
        }
    }
    private void scheduleRestart(){
        Path game=FabricLoader.getInstance().getGameDir();
        if(!pending||!canRestart||!RestartPolicy.enabled(game)||!restartQueued.compareAndSet(false,true))return;
        var client=MinecraftClient.getInstance();
        client.setScreen(new UpdateScreen(this::snapshot,"Обновление готово · перезапуск через 4 секунды","/6ubaafp update off — не перезапускать автоматически",true,client.currentScreen));
        CompletableFuture.delayedExecutor(4,TimeUnit.SECONDS).execute(()->client.execute(()->{
            restartQueued.set(false);
            if(!pending||!canRestart||pendingHelper==null||!pendingHelper.isAlive()||!RestartPolicy.enabled(game)){
                if(client.currentScreen instanceof UpdateScreen screen)screen.cancelRestart();return;
            }
            for(var spec:ModCatalog.ALL){Object hook=FabricLoader.getInstance().getObjectShare().get("spidiboost:update-save-"+spec.id());if(hook instanceof Runnable r)try{r.run();}catch(RuntimeException e){LOG.warn("Could not preserve {} results ({})",spec.name(),e.getClass().getSimpleName());}}
            client.scheduleStop();
        }));
    }
    private void check(UpdateChecks.Mode mode) {
        if(worker.isShutdown())return;
        if(!checks.request(mode))return;
        worker.execute(()->{
            var changes=new ArrayList<BatchPlan.Change>();Process helper=null;
            failedChecks.clear();
            var oldRetry=retry;if(oldRetry!=null)oldRetry.cancel(false);retry=null;
            try {
                Path game=FabricLoader.getInstance().getGameDir().toRealPath();List<Installed> installed=installed(game);
                if(installed.isEmpty())return;
                boolean activate=mode==UpdateChecks.Mode.ACTIVATE;
                Path dir=game.resolve(".spidiboost-updates");
                if(activate){Files.createDirectories(dir);if(!dir.toRealPath().startsWith(game)||Files.isSymbolicLink(dir))throw new IOException("Foreign update directory");}
                // Per-mod failures do not suppress healthy updates for the other installed mods.
                var futures=installed.stream().map(m->CompletableFuture.supplyAsync(()->prepare(m,game,dir,activate),downloads)).toList();
                for(var future:futures){var c=future.join();if(c!=null)changes.add(c);}
                // A background/manual status check only discovers versions: no JAR, helper or restart.
                if(!activate||changes.isEmpty())return;
                List<String> detectedRestart=RestartCommand.detect(game);
                // An explicitly listed old mod JAR on the replayed classpath becomes invalid after a rename.
                List<String> restart=changes.stream().anyMatch(c->detectedRestart.stream().anyMatch(a->a.contains(c.target().toString())))?List.of():detectedRestart;
                if(pendingPlan!=null&&pendingHelper!=null&&pendingHelper.isAlive()
                        &&pendingPlan.changes().equals(changes)&&pendingPlan.command().equals(restart)) {
                    canRestart=!restart.isEmpty();readyBehavior(game);changes.clear();return;
                }
                // A newer release may appear during this same game session. Replace only our own waiting helper.
                pending=false;canRestart=false;
                if(pendingHelper!=null) {
                    pendingHelper.destroy();if(!pendingHelper.waitFor(3,TimeUnit.SECONDS)){pendingHelper.destroyForcibly();if(!pendingHelper.waitFor(3,TimeUnit.SECONDS))throw new IOException("Old helper still alive");}
                    pendingHelper=null;
                }
                if(pendingPlan!=null)for(var old:pendingPlan.changes())if(!changes.contains(old)) {
                    ModsDownload.discard(old);Files.deleteIfExists(old.staged());
                }
                Path agent=extractAgent(dir),java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
                helper=new ProcessBuilder(java.toString(),"-jar",agent.getFileName().toString()).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("agent.log").toFile())).start();
                boolean legacy=installed.stream().anyMatch(m->m.mod().id().equals("spidicard")&&new Release(m.mod(),"1.5.0","","").newerThan(m.version()));
                var removals=new ArrayList<BatchPlan.Removal>();
                if(changes.stream().anyMatch(c->c.destination().getFileName().toString().startsWith("SpidiBan-"))) {
                    var addon=FabricLoader.getInstance().getModContainer("spidiban_shist");
                    if(addon.isPresent()){var paths=addon.get().getOrigin().getPaths();if(paths.size()!=1||Files.isSymbolicLink(paths.getFirst()))throw new IOException("Legacy addon outside own instance");Path oldAddon=paths.getFirst().toRealPath();if(!oldAddon.getParent().equals(game.resolve("mods").toRealPath()))throw new IOException("External legacy addon");removals.add(new BatchPlan.Removal(oldAddon,BatchInstall.hash(oldAddon)));}
                }
                var plan=new BatchPlan(ProcessHandle.current().pid(),game,changes,removals,Path.of(System.getProperty("user.dir")).toAbsolutePath(),restart,legacy);
                try(var pipe=helper.getOutputStream()){plan.write(pipe);}Process waiting=helper;
                var ready=CompletableFuture.supplyAsync(()->{try{return new BufferedReader(new InputStreamReader(waiting.getInputStream(),StandardCharsets.UTF_8)).readLine();}catch(IOException e){throw new CompletionException(e);}});
                if(!"READY".equals(ready.get(15,TimeUnit.SECONDS)))throw new IOException("Updater helper did not become ready");
                pendingPlan=plan;pendingHelper=helper;pending=true;canRestart=!restart.isEmpty();
                LOG.info("Update prepared: {} mod(s), restart available={}, autoRestart={}",changes.size(),canRestart,RestartPolicy.enabled(game));
                readyBehavior(game);
                changes.clear();helper=null;
            }catch(Exception e){LOG.warn("Update check failed ({})",e.getClass().getSimpleName());
                failedChecks.add("coordinator");
                // Installation errors with a real update are actionable; network checks stay silent.
                if(!changes.isEmpty()&&mode==UpdateChecks.Mode.ACTIVATE)notice("Не удалось подготовить обновление или запуск лаунчера.",true);
                for(var c:changes)for(var mod:ModCatalog.ALL)if(c.destination().getFileName().toString().startsWith(mod.name()+"-")){
                    var old=status(mod.id());rows.put(mod.id(),new UpdateRow(mod.id(),mod.name(),old.getOrDefault("installed",""),"Установка отложена"));publish(mod.id(),old.getOrDefault("installed",""),old.getOrDefault("latest",""),"unavailable","Установка отложена; повторим проверку при следующем входе");
                }
            }
            finally{
                if(helper!=null)helper.destroyForcibly();
                for(var c:changes)if(pendingPlan==null||!pendingPlan.changes().contains(c))try{ModsDownload.discard(c);Files.deleteIfExists(c.staged());prepared.values().remove(c);}catch(IOException ignored){}
                if(!worker.isShutdown()){
                    if(failedChecks.isEmpty())checks.success();
                    else retry=worker.schedule(()->check(UpdateChecks.Mode.DISCOVERY),checks.failedDelaySeconds(),TimeUnit.SECONDS);
                }
                var next=checks.finish();if(next!=null&&!worker.isShutdown())check(next);
            }
        });
    }
    private BatchPlan.Change prepare(Installed m,Path game,Path dir) {
        return prepare(m,game,dir,true);
    }
    private BatchPlan.Change prepare(Installed m,Path game,Path dir,boolean activate) {
        Path staged=null;
        try {
            var release=Release.parse(m.mod(),new String(http.get(m.mod().latest(),16384),StandardCharsets.UTF_8));
            LOG.debug("{}: loaded={}, GitHub={}, newer={}",m.mod().name(),m.version(),release.version(),release.newerThan(m.version()));
            publish(m.mod().id(),m.version(),release.version(),release.newerThan(m.version())?"available":"current",release.newerThan(m.version())?(activate?"Новая версия найдена; загружаем…":"Новая версия найдена; ожидает следующего запуска или входа на сервер"):"Установлена актуальная версия");
            if(!activate){state(m,m.version(),release.newerThan(m.version())?"Обновление ожидает следующего входа":"Установлена актуальная версия");return null;}
            if(!release.newerThan(m.version())) {
                // Older updaters replaced a JAR in place, keeping its previous version in the filename.
                if(m.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+")&&!m.jar().getFileName().toString().equals(m.mod().artifact(m.version()))) {
                    String same=BatchInstall.hash(m.jar());var local=new Release(m.mod(),m.version(),m.mod().artifact(m.version()),same);
                    staged=Files.createTempFile(dir,"rename-",".jar");Files.copy(m.jar(),staged,StandardCopyOption.REPLACE_EXISTING);Artifact.validate(staged,local);
                    var rename=new BatchPlan.Change(m.jar(),game.resolve("mods").resolve(local.artifact()),staged,same,same);state(m,m.version(),"Имя JAR приведено к установленной версии");publish(m.mod().id(),m.version(),release.version(),"staged","Имя JAR исправится после выхода");staged=null;return rename;
                }
                state(m,m.version(),"Установлена актуальная версия");return null;
            }
            var cached=prepared.get(m.mod().id());
            if(cached!=null&&cached.destination().getFileName().toString().equals(release.artifact())
                    &&cached.newHash().equals(release.sha256())&&Files.isRegularFile(cached.staged())&&BatchInstall.hash(cached.staged()).equals(release.sha256())) {
                publish(m.mod().id(),m.version(),release.version(),"staged","Загружено и проверено; готово к установке");
                state(m,m.version()+" → "+release.version(),"Обновление уже скачано ✓");return cached;
            }
            state(m,m.version()+" → "+release.version(),"Загружаем обновление…");String old=BatchInstall.hash(m.jar());
            staged=Files.createTempFile(dir,"release-",".jar");Files.write(staged,http.get(release.download(),32*1024*1024));Artifact.validate(staged,release);
            Path destination=game.resolve("mods").resolve(release.artifact());
            var c=new BatchPlan.Change(m.jar(),destination,staged,old,release.sha256());prepared.put(m.mod().id(),c);state(m,release.version(),"Обновление проверено ✓");publish(m.mod().id(),m.version(),release.version(),"staged","Загружено и проверено; установится после выхода");staged=null;return c;
        }catch(Exception e){failedChecks.add(m.mod().id());state(m,m.version(),"Проверка недоступна. Текущая версия сохранена");publish(m.mod().id(),m.version(),"","unavailable","GitHub временно недоступен; повторим проверку в фоне");LOG.debug("{} update failed ({})",m.mod().name(),e.getClass().getSimpleName());
            return null;}
        finally{if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){}}
    }
    private static Path extractAgent(Path directory)throws IOException {
        try(var in=SharedUpdater.class.getResourceAsStream("/sixubaafp-shared-update-agent.jar")) {
            if(in==null)throw new IOException("Missing updater helper");byte[] data=in.readNBytes(1048577);if(data.length>1048576)throw new IOException("Helper too large");
            Path p=Files.createTempFile(directory,"agent-",".jar");Files.write(p,data);return p;
        }
    }
}
