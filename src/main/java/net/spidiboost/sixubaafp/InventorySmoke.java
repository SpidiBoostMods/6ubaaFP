package net.spidiboost.sixubaafp;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import java.util.*;

/** Isolated native fixtures only; enabled by the existing smoke flag, never a user's connection. */
final class InventorySmoke {
    private InventorySmoke(){}
    static Preview verify(){
        var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>();
        FpClient.INSTANCE.registerCommands(dispatcher);
        for(String root:List.of("6ubaafp"))for(String sub:List.of("inv","invsee"))for(String mode:List.of("","none ","full ")){
            String command=root+" "+sub+" "+mode+"minecraft:compass, Алмазный меч";
            var parsed=dispatcher.parse(command,null);require(parsed.getExceptions().isEmpty()&&!parsed.getReader().canRead(),"native command parse "+command);
            require(parsed.getContext().getNodes().stream().anyMatch(n->n.getNode().getName().equals("предметы")),"greedy items argument");
            if(!mode.isEmpty())require(parsed.getContext().getNodes().stream().anyMatch(n->n.getNode().getName().equals(mode.strip())),"mode is literal not item name");
        }
        require(dispatcher.getRoot().getChild("6ubaa")==null,"removed alias is absent");
        for(String root:List.of("6ubaafp"))for(String sub:List.of("start","full","stop","status","open","update"))
            require(dispatcher.getRoot().getChild(root).getChild(sub)!=null,"legacy command preserved");
        for(String setting:List.of("on","off"))require(!dispatcher.parse("6ubaafp update "+setting,null).getReader().canRead(),"update setting parse");
        for(String command:List.of("6ubaafp inv comp","6ubaafp invsee full 1, 4, 2-5 comp","6ubaafp inv compass,obsid")){
            var completions=dispatcher.getCompletionSuggestions(dispatcher.parse(command,null)).join().getList();
            String expected=command.endsWith("obsid")?"minecraft:obsidian":"minecraft:compass";
            require(completions.stream().anyMatch(s->s.getText().equals(expected)),"native shorthand suggestion: "+command);
            require(completions.stream().filter(s->s.getText().equals(expected)).allMatch(s->s.apply(command).endsWith(expected)),"suggestion range preserves route and previous terms");
        }
        var ordinary=Text.literal("Чужой чат 🗝").withColor(0x123456).asOrderedText();require(FpTheme.animate(ordinary)==ordinary,"foreign text unchanged");
        require(FpTheme.message("Юникод 🗝").getString().equals("[6ubaaFP]  Юникод 🗝"),"gradient Unicode unchanged");
        require(net.fabricmc.loader.api.FabricLoader.getInstance().getObjectShare().get("spidiboost:update-save-sixubaafp") instanceof Runnable,"updater preservation hook");
        long[] fpTime={0};var fpRequests=new ArrayList<String>();var fpResults=new ArrayList<FpScan.Result>();
        var fp=new FpScan(new FpScan.Port(){
            public void send(String command){fpRequests.add(command);}
            public Collection<String> online(){return List.of("Owner");}
            public void log(String value){}public void finish(FpScan.Result r){fpResults.add(r);}
        },()->fpTime[0]);
        fp.start(List.of("Owner"),false);
        Text reply=Text.literal("Сканирование Owner.\n").append(Text.literal("RedOffline").formatted(net.minecraft.util.Formatting.DARK_RED));
        fp.accept(FpClient.styled(reply));fpTime[0]+=5;fp.tick();
        require(fpRequests.getLast().equals("hist RedOffline ban 100"),"native offline dark red query without TAB");
        fp.accept(FpProtocol.Line.plain("История RedOffline (Лимит: 1):\nRedOffline был забанен куратором Mod\nПо причине: Funpay [Истек]"));fpTime[0]+=5;fp.tick();
        require(fpResults.getFirst().names().equals(List.of("RedOffline")),"native offline dark red history");
        PlayerInventory viewer=new PlayerInventory(null);
        viewer.setStack(0,new ItemStack(Items.COMPASS));
        var handler=GenericContainerScreenHandler.createGeneric9x4(42,viewer);
        require(FpClient.inventoryItems(handler,viewer).size()==36,"only 36 server slots");
        var query=InventoryQuery.parse("minecraft:compass, Алмазный меч");
        require(FpClient.inventoryItems(handler,viewer).stream().noneMatch(query::matches),"viewer compass excluded");
        var renamed=new ItemStack(Items.STICK);renamed.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Алмазный меч").withColor(0x55ffff));
        var stacks=DefaultedList.ofSize(72,ItemStack.EMPTY);stacks.set(0,new ItemStack(Items.COMPASS));stacks.set(35,renamed);stacks.set(63,new ItemStack(Items.DIAMOND_SWORD));
        var packet=new InventoryS2CPacket(42,1,stacks,ItemStack.EMPTY);
        handler.updateSlotStacks(packet.getRevision(),packet.getContents(),packet.getCursorStack());
        var extracted=FpClient.inventoryItems(handler,viewer);
        require(query.matches(extracted)&&!query.matches(extracted.get(0))&&!query.matches(extracted.get(35)),"native AND: registry and anvil custom name on last server slot");
        handler.getInventory().setStack(1,new ItemStack(Items.OBSIDIAN,2));handler.getInventory().setStack(2,new ItemStack(Items.OBSIDIAN,4));
        var counted=InventoryQuery.parse("compass, Алмазный меч,obsidian(6)",id->net.minecraft.registry.Registries.ITEM.containsId(net.minecraft.util.Identifier.of(id)));
        require(counted.matches(FpClient.inventoryItems(handler,viewer)),"native ItemStack counts aggregate to exactly six");
        handler.getInventory().setStack(2,new ItemStack(Items.OBSIDIAN,5));require(!counted.matches(FpClient.inventoryItems(handler,viewer)),"native rejects seven against exact six");
        long[] time={0};var requests=new ArrayList<String>();var outcomes=new ArrayList<FpScan.Result>();
        var scanner=new InventoryScan(new InventoryScan.Port(){
            public void send(String c){requests.add(c);}public Collection<String> online(){return List.of("NativeA","NativeB","NativeC");}
            public void close(int sync){}public boolean checkpoint(List<String> names){return true;}
            public void log(String value){}public void finish(FpScan.Result r){outcomes.add(r);}
        },()->time[0],query);
        scanner.start(List.of("NativeA","NativeB","NativeC"));
        scanner.opened(42,"Player");scanner.contents(42,extracted);time[0]+=5;scanner.tick();time[0]+=5;scanner.tick();
        handler.getInventory().clear();scanner.opened(43,"Player");scanner.contents(43,FpClient.inventoryItems(handler,viewer));time[0]+=5;scanner.tick();time[0]+=5;scanner.tick();
        handler.getInventory().setStack(35,renamed);scanner.opened(44,"Player");scanner.contents(44,FpClient.inventoryItems(handler,viewer));time[0]+=5;scanner.tick();time[0]+=5;scanner.tick();
        require(requests.equals(List.of("invsee NativeA","invsee NativeB","invsee NativeC")),"native serial queue");
        require(outcomes.size()==1&&outcomes.getFirst().names().equals(List.of("NativeA")),"native AND excludes partial requirements and empty container");
        handler.getInventory().setStack(0,new ItemStack(Items.COMPASS));
        return new Preview(new GenericContainerScreen(handler,viewer,Text.literal("Player")));
    }
    private static void require(boolean test,String label){if(!test)throw new AssertionError(label);}
    /** Render the real container without a game world or player's HandledScreen.tick(). */
    static final class Preview extends Screen {
        private final GenericContainerScreen container;int frames;
        Preview(GenericContainerScreen container){super(Text.literal("Inventory smoke preview"));this.container=container;}
        @Override protected void init(){container.init(client,width,height);}
        @Override public void render(DrawContext context,int mouseX,int mouseY,float delta){
            container.render(context,mouseX,mouseY,delta);
            FpHud.draw(context,new FpHud.View("ИНВЕНТАРИ","InvRetry","Ждём сервер · повтор 3",7,24,3,1,12,true));
            int y=Math.max(100,height-48);
            for(var line:client.textRenderer.wrapLines(FpTheme.message("Предмет найден · NativeA"),width-24)){context.drawTextWithShadow(client.textRenderer,line,12,y,0xffffff);y+=11;}
            for(var line:client.textRenderer.wrapLines(FpTheme.error("GitHub временно недоступен · игра продолжается"),width-24)){context.drawTextWithShadow(client.textRenderer,line,12,y,0xffffff);y+=11;}
            frames++;
        }
    }
}
