package net.spidiboost.sixubaafp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.util.Identifier;

public final class FpClient implements ClientModInitializer {
    public static FpClient INSTANCE;
    private MinecraftClient client; private Path result,run;
    private FpScan scan;private FullRun full;private boolean scanForFull,pumping;
    private InventoryScan inventory;private InventoryQuery inventoryQuery;private boolean inventoryMode;
    private ClientConnection connection;private long epoch,position,tabRevision,scanEpoch,flushAt;
    private int menuSync=-1;private String menuTitle="";
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"6ubaaFP-files");t.setDaemon(true);return t;});
    private record Log(Path path,String text) {}
    private final Queue<Log> logs=new ConcurrentLinkedQueue<>();
    @Override public void onInitializeClient() {
        INSTANCE=this;client=MinecraftClient.getInstance();result=FpFiles.result(client.runDirectory.toPath());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->registerCommands(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(c->pump());
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->{stop("Клиент закрывается.");flush();io.shutdown();try{io.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        net.spidiboost.sixubaafp.updates.SharedUpdater.preserve("sixubaafp",()->stop("Сохранение перед обновлением."));
        if(Boolean.getBoolean("sixubaafp.smoke")) FpSmoke.register();
    }
    void registerCommands(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> dispatcher) {
            for(String alias:List.of("6ubaafp","6ubaa")) {
            var root=ClientCommandManager.literal(alias);
            root.then(ClientCommandManager.literal("start").executes(ctx->{start(false);return 1;}));
            root.then(ClientCommandManager.literal("full").executes(ctx->{start(true);return 1;}));
            root.then(ClientCommandManager.literal("stop").executes(ctx->{stop("Остановлено пользователем.");return 1;}));
            root.then(ClientCommandManager.literal("status").executes(ctx->{message(status());return 1;}));
            root.then(ClientCommandManager.literal("open").executes(ctx->{open();return 1;}));
            for(String sub:List.of("inv","invsee")) {
                var inv=ClientCommandManager.literal(sub).executes(ctx->{inventoryUsage();return 0;});
                inv.then(inventoryArgument(false));
                inv.then(ClientCommandManager.literal("full").executes(ctx->{inventoryUsage();return 0;}).then(inventoryArgument(true)));
                inv.then(ClientCommandManager.literal("none").executes(ctx->{inventoryUsage();return 0;}).then(inventoryArgument(false)));
                root.then(inv);
            }
            dispatcher.register(root);
            }
    }
    private void inventoryUsage(){message("/6ubaa inv [none|full] minecraft:compass, Алмазный меч — укажи предметы.");}
    private com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,String> inventoryArgument(boolean all) {
        return ClientCommandManager.argument("предметы",StringArgumentType.greedyString()).suggests((context,builder)->{
            String input=builder.getRemaining();int comma=input.lastIndexOf(',');int offset=comma+1;
            while(offset<input.length()&&Character.isWhitespace(input.charAt(offset)))offset++;
            var values=builder.createOffset(builder.getStart()+offset);String prefix=input.substring(offset).toLowerCase(Locale.ROOT);
            Registries.ITEM.getIds().stream().map(Identifier::toString).filter(id->id.startsWith(prefix)).sorted().forEach(values::suggest);
            if("алмазный меч".startsWith(prefix))values.suggest("Алмазный меч");return values.buildFuture();
        }).executes(ctx->{
            try {
                InventoryQuery query=InventoryQuery.parse(StringArgumentType.getString(ctx,"предметы"));
                for(var term:query.terms())if(term.registry()&&(!Registries.ITEM.containsId(Identifier.of(term.value()))||term.value().equals("minecraft:air")))
                    throw new IllegalArgumentException("Неизвестный или пустой предмет: "+term.value());
                start(all,query);
            }catch(IllegalArgumentException e){message(e.getMessage());return 0;}return 1;
        });
    }
    private static long now(){return System.nanoTime()/1000000;}
    private boolean fullActive(){return full!=null&&full.active();}
    private boolean scanActive(){return inventoryMode?inventory!=null&&inventory.active():scan!=null&&scan.active();}
    private List<String> scanMatches(){return inventoryMode?inventory==null?List.of():inventory.matches():scan==null?List.of():scan.matches();}
    private void cancelScan(String reason){if(!scanActive())return;if(inventoryMode)inventory.stop(reason);else scan.stop(reason);}
    private boolean ready() {
        return client.player!=null&&client.world!=null&&client.getNetworkHandler()!=null&&client.interactionManager!=null
                &&client.player.isLoaded()&&!client.player.isRemoved()&&client.getOverlay()==null
                &&client.world.isChunkLoaded(client.player.getBlockX()>>4,client.player.getBlockZ()>>4)
                &&!(client.currentScreen instanceof DownloadingTerrainScreen)&&!(client.currentScreen instanceof ReconfiguringScreen)
                &&!(client.currentScreen instanceof ConnectScreen);
    }
    private List<String> tab() {
        if(client.getNetworkHandler()==null)return List.of();
        return client.getNetworkHandler().getListedPlayerListEntries().stream().map(e->e.getProfile().getName())
                .filter(FpProtocol::valid).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    public void start(boolean all) {
        start(all,null);
    }
    private void start(boolean all,InventoryQuery query) {
        if(fullActive()||scanActive()){message("Уже идёт проверка. /6ubaafp stop");return;}
        if(!ready()){message("Подключись к серверу и дождись загрузки мира.");return;}
        full=null;inventoryMode=query!=null;inventoryQuery=query;scan=null;inventory=null;
        result=client.runDirectory.toPath().resolve(inventoryMode?"6ubaafp-inv.txt":"6ubaafp.txt");
        closeMenu();connection=client.getNetworkHandler().getConnection();
        try {
            run=client.runDirectory.toPath().resolve("sixubaafp").resolve(LocalDateTime.now().toString().replace(':','-')+"-"+UUID.randomUUID().toString().substring(0,8));
            Files.createDirectories(run);
            if(Files.exists(result))Files.copy(result,run.resolve("previous-"+result.getFileName()));
            FpFiles.write(result,List.of());
        }catch(Exception e){message("Не могу подготовить файл: "+e.getMessage());return;}
        log("VERSION="+net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("sixubaafp").orElseThrow().getMetadata().getVersion().getFriendlyString()+" MODE="+(inventoryMode?"INV ":"FP ")+(all?"FULL":"START")+" query="+query+" file="+result);
        if(!all){startScan(false);return;}
        full=new FullRun(new FullRun.Port(){
            public void hub(){closeMenu();send("hub");}
            public void useCompass(){if(ready()&&ServerMenus.compass(item(client.player.getInventory().getStack(0)))){client.player.getInventory().selectedSlot=0;client.interactionManager.interactItem(client.player,Hand.MAIN_HAND);}}
            public void click(int sync,int slot){if(ready()&&client.player.currentScreenHandler.syncId==sync){log("CLICK sync="+sync+" slot="+slot);client.interactionManager.clickSlot(sync,slot,0,SlotActionType.PICKUP,client.player);}}
            public void closeMenu(){FpClient.this.closeMenu();}
            public boolean startScan(){return FpClient.this.startScan(true);}
            public void cancelScan(){FpClient.this.cancelScan("Остановлена текущая проверка грифа.");}
            public List<String> scanMatches(){return scanForFull?FpClient.this.scanMatches():List.of();}
            public boolean persist(List<String> lines){return save(lines);}
            public void status(String text){log("NAV "+text);message(text);}
            public void finished(boolean complete,String reason){message(reason+" Файл: "+result);log("FULL END complete="+complete+" reason="+reason);flush();open();}
        },1500);
        full.begin(now());
    }
    private boolean startScan(boolean fromFull) {
        if(!ready()||scanActive())return false;
        scanForFull=fromFull;scanEpoch=epoch;
        if(inventoryMode){
            closeMenu();inventory=new InventoryScan(new InventoryScan.Port(){
                public void send(String command){FpClient.this.send(command);}
                public Collection<String> online(){return tab();}
                public void close(int sync){if(client.player!=null&&client.player.currentScreenHandler.syncId==sync)closeMenu();}
                public boolean checkpoint(List<String> names){return scanForFull&&fullActive()?full.checkpoint(names):save(List.of(String.join(" ",names)));}
                public void log(String value){FpClient.this.log(value);}
                public void finish(FpScan.Result output){scanFinished(output);}
            },FpClient::now,inventoryQuery);
            List<String> names=tab();message("Проверяю инвентари TAB: "+names.size()+" игроков.");inventory.start(names);return true;
        }
        scan=new FpScan(new FpScan.Port(){
            public void send(String command){FpClient.this.send(command);}
            public Collection<String> online(){return tab();}
            public void log(String text){FpClient.this.log(text);}
            public void finish(FpScan.Result output){
                scanFinished(output);
            }
        },FpClient::now);
        List<String> names=tab();message("Запрашиваю /dupeip для "+names.size()+" игроков. Проверяю все жёлтые и красные аккаунты, включая офлайн.");
        scan.start(names,false);return true;
    }
    private void scanFinished(FpScan.Result output){
                log("SCAN END complete="+output.complete()+" matches="+output.names()+" unresolved="+output.unresolved());
                try{Files.writeString(run.resolve("unresolved.txt"),String.join("\n",output.unresolved()),StandardCharsets.UTF_8);}catch(Exception e){log("UNRESOLVED WRITE ERROR "+e);}
                if(scanForFull&&fullActive()) {
                    if(output.reason().startsWith("Сервер отклонил")||(inventoryMode&&!output.complete()))full.stop(output.reason());
                    else full.scanFinished(output.complete(),output.names(),output.reason(),now());
                }
                else if(!scanForFull){save(List.of(String.join(" ",output.names())));message(output.reason()+" Найдено: "+output.names().size()+". Файл: "+result);flush();open();}
    }
    private void send(String command){if(client.getNetworkHandler()==null||!client.getNetworkHandler().getConnection().isOpen())throw new IllegalStateException("Нет игрового соединения");client.getNetworkHandler().sendChatCommand(command);}
    public void stop(String reason){if(fullActive())full.stop(reason);else cancelScan(reason);}
    public String status(){return (fullActive()?"Гриф #"+full.grief()+": "+full.phaseLabel()+". ":"")+(inventoryMode?inventory==null?"Нет активной проверки.":inventory.status():scan==null?"Нет активной проверки.":scan.status());}
    public void pump(){
        if(pumping)return;pumping=true;
        try {
            var network=client.getNetworkHandler();boolean connected=connection!=null&&connection.isOpen()&&(network==null||network.getConnection()==connection);
            if(fullActive()) {
                var names=tab();boolean loaded=ready();boolean tabPresent=network!=null&&client.player!=null&&network.getPlayerListEntry(client.player.getUuid())!=null&&!names.isEmpty();
                int grief=0;if(network!=null){var sidebar=network.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);if(sidebar!=null)grief=ServerMenus.griefNumber(sidebar.getDisplayName().getString());}
                String self=client.player==null?"":client.player.getGameProfile().getName();
                int others=(int)names.stream().filter(n->!n.equalsIgnoreCase(self)).count();
                full.tick(new FullRun.View(connected,loaded,loaded&&ServerMenus.compass(item(client.player.getInventory().getStack(0))),epoch,tabRevision,names.hashCode(),grief,menu(),position,tabPresent,others),now());
            }
            if(scanActive()) {
                if(!connected)cancelScan("Соединение потеряно; сохранён частичный результат.");
                else if(scanEpoch!=epoch){if(inventoryMode)stop("Сервер/мир изменился во время проверки.");else scan.stop("Сервер/мир изменился во время проверки.");}
                else if(ready()){if(inventoryMode)inventory.tick();else scan.tick();}
            }
        }catch(Exception error){log("ERROR "+error+" "+Arrays.toString(error.getStackTrace()));stop("Ошибка проверки: "+error.getMessage());}
        finally{pumping=false;if(now()-flushAt>=250){flushAt=now();flush();}}
    }
    private void closeMenu(){if(client.player!=null&&client.player.currentScreenHandler!=client.player.playerScreenHandler){client.player.closeHandledScreen();menuSync=-1;}}
    private static ServerMenus.Item item(ItemStack stack){return new ServerMenus.Item(Registries.ITEM.getId(stack.getItem()).toString(),stack.isEmpty()?"":stack.getName().getString());}
    private ServerMenus.Menu menu(){if(client.player==null||client.player.currentScreenHandler==client.player.playerScreenHandler||client.player.currentScreenHandler.syncId!=menuSync)return null;var list=new ArrayList<ServerMenus.Item>();for(var slot:client.player.currentScreenHandler.slots){if(slot.inventory==client.player.getInventory())break;list.add(item(slot.getStack()));}return new ServerMenus.Menu(menuSync,menuTitle,list);}
    public static List<ServerMenus.Item> inventoryItems(net.minecraft.screen.ScreenHandler handler,net.minecraft.entity.player.PlayerInventory viewer){
        var items=new ArrayList<ServerMenus.Item>();for(var slot:handler.slots)if(slot.inventory!=viewer)items.add(item(slot.getStack()));return List.copyOf(items);
    }
    public void opened(int sync,String title){menuSync=sync;menuTitle=title;if(inventoryMode&&scanActive())inventory.opened(sync,title);pump();}
    public void contents(int sync,boolean complete){
        if(inventoryMode&&scanActive()&&client.player!=null&&client.player.currentScreenHandler.syncId==sync){
            var items=inventoryItems(client.player.currentScreenHandler,client.player.getInventory());
            if(complete)inventory.contents(sync,items);else inventory.changed(sync,items);
        }pump();
    }
    public void closed(int sync){if(fullActive())full.menuClosed(sync);if(inventoryMode&&scanActive())inventory.closed(sync);if(menuSync==sync)menuSync=-1;pump();}
    public void worldChanged(){epoch++;menuSync=-1;}
    public void positionChanged(){position++;}
    public void tabChanged(){tabRevision++;}
    public static FpProtocol.Line styled(Text text){
        StringBuilder value=new StringBuilder();List<Integer> colors=new ArrayList<>();
        text.visit((style,part)->{value.append(part);int rgb=style.getColor()==null?-1:style.getColor().getRgb();for(int i=0;i<part.length();i++)colors.add(rgb);return Optional.empty();},net.minecraft.text.Style.EMPTY);
        int[] rgb=new int[colors.size()];for(int i=0;i<rgb.length;i++)rgb[i]=colors.get(i);return new FpProtocol.Line(value.toString(),rgb);
    }
    public void message(Text text,boolean overlay){if(overlay||!scanActive())return;try{if(inventoryMode)inventory.message(styled(text));else scan.accept(styled(text));}catch(Exception e){log("PARSE ERROR "+e);stop("Ошибка разбора; смотри debug.log.");}}
    private boolean save(List<String> lines){try{FpFiles.write(result,lines);return true;}catch(Exception e){log("FILE ERROR "+e);message("Ошибка записи: "+e.getMessage());return false;}}
    private void open(){if(result==null||!Files.isRegularFile(result)){message("Файл ещё не создан.");return;}Path file=result.toAbsolutePath();io.execute(()->{try{Util.getOperatingSystem().open(file.toFile());}catch(Exception e){client.execute(()->message("Не удалось открыть "+file+": "+e.getMessage()));}});}
    private void message(String value){if(client.player!=null)client.player.sendMessage(Text.literal("[6ubaaFP] "+value),false);}
    private void log(String value){if(run!=null)logs.add(new Log(run.resolve("debug.log"),LocalDateTime.now()+" ["+Thread.currentThread().getName()+"] "+value+"\n"));}
    private void flush(){Map<Path,StringBuilder> batch=new LinkedHashMap<>();Log l;while((l=logs.poll())!=null)batch.computeIfAbsent(l.path(),k->new StringBuilder()).append(l.text());if(batch.isEmpty()||io.isShutdown())return;io.execute(()->batch.forEach((path,value)->{try{Files.writeString(path,value,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){client.execute(()->message("Ошибка журнала: "+e.getMessage()));}}));}
}
