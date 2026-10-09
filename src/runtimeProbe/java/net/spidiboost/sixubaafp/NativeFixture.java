package net.spidiboost.sixubaafp;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.screen.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static net.minecraft.server.command.CommandManager.*;

/** Only enabled in the isolated Gradle verification run, never included in the mod. */
public final class NativeFixture implements ModInitializer {
    static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
    static final List<String> NAMES=new CopyOnWriteArrayList<>();
    static final List<String> HISTORY_REQUESTS=new CopyOnWriteArrayList<>();
    private static ServerPlayerEntity delayedPlayer;private static int delay;
    static volatile boolean partial;
    private static int retry;
    private static UUID id(String name){return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));}
    static void tab(ServerPlayerEntity player, boolean small){
        var remove=NAMES.stream().map(NativeFixture::id).toList();
        if(!remove.isEmpty())player.networkHandler.sendPacket(new PlayerRemoveS2CPacket(remove));
        NAMES.clear();NAMES.addAll(Boolean.getBoolean("sixubaafp.qa.history")?java.util.stream.IntStream.range(0,24).mapToObj(i->String.format(java.util.Locale.ROOT,"Seed%02d",i)).toList():small?List.of("AFound","ZHang"):List.of("AFound","BMissing","CGone","DRetry","EEmpty"));
        var players=new ArrayList<ServerPlayerEntity>();
        for(String name:NAMES){var fake=new ServerPlayerEntity(player.getServer(),player.getServerWorld(),new GameProfile(id(name),name),player.getClientOptions());fake.networkHandler=player.networkHandler;players.add(fake);}
        player.networkHandler.sendPacket(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,PlayerListS2CPacket.Action.UPDATE_LISTED,PlayerListS2CPacket.Action.UPDATE_GAME_MODE),players));
    }
    @Override public void onInitialize(){
        ServerTickEvents.END_SERVER_TICK.register(server->{if(delayedPlayer!=null&&--delay<=0){var player=delayedPlayer;delayedPlayer=null;player.sendMessage(Text.literal("Seed00 был забанен куратором QA"));player.sendMessage(Text.literal("По причине: FP_RW [Горит]"));}});
        ServerLifecycleEvents.SERVER_STARTED.register(server->{try{var field=server.getClass().getDeclaredField("lanPort");field.setAccessible(true);field.setInt(server,0);}catch(Exception e){throw new RuntimeException(e);}});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            handler.player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            handler.player.getAbilities().flying=true;handler.player.sendAbilitiesUpdate();
            tab(handler.player,false);
        });
        CommandRegistrationCallback.EVENT.register((d,r,e)->{
            d.register(literal("dupeip").then(argument("nick",StringArgumentType.word()).executes(ctx->{
                String nick=StringArgumentType.getString(ctx,"nick");HISTORY_REQUESTS.add("dupeip "+nick);var player=ctx.getSource().getPlayerOrThrow();
                player.sendMessage(Text.literal("[18:45:21] Сканирование "+nick+". [Онлайн] [Оффлайн] [Забанен]"));
                var body=Text.literal(nick).formatted(net.minecraft.util.Formatting.GOLD);
                if(nick.equals("Seed00")||nick.equals("Seed01"))body.append(Text.literal(", Banned").formatted(net.minecraft.util.Formatting.DARK_RED)).append(Text.literal(", "+(nick.equals("Seed00")?"Seed01":"Seed00")).formatted(net.minecraft.util.Formatting.YELLOW));
                body.append(Text.literal(", FPQA").formatted(net.minecraft.util.Formatting.GOLD)).append(Text.literal(", Gray [C] [H]").formatted(net.minecraft.util.Formatting.GRAY));
                player.sendMessage(body);return 1;
            })));
            d.register(literal("hist").then(argument("args",StringArgumentType.greedyString()).executes(ctx->{
                String args=StringArgumentType.getString(ctx,"args"),nick=args.split(" ")[0];HISTORY_REQUESTS.add("hist "+args);var player=ctx.getSource().getPlayerOrThrow();
                player.sendMessage(Text.literal("История "+nick+" (Лимит: 7): [CopyFull]"));
                player.sendMessage(Text.literal("-- [2026-10-08 15:29] --"));
                player.sendMessage(Text.literal(nick+" был забанен куратором QA"));
                player.sendMessage(Text.literal("По причине: "+(nick.equals("Banned")?"Funpay":"Читы")+" [Горит]"));
                if(nick.equals("Seed00")){delayedPlayer=player;delay=3;}
                return 1;
            })));
            d.register(literal("invsee").then(argument("nick",StringArgumentType.word()).executes(ctx->{
                String name=StringArgumentType.getString(ctx,"nick");REQUESTS.add(name);var player=ctx.getSource().getPlayerOrThrow();
                if(name.equals("ZHang"))return 1;
                if(name.equals("CGone")){player.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(id(name))));return 1;}
                if(name.equals("BMissing")||name.equals("FPQA")){player.sendMessage(Text.literal("Ошибка: Игрок не найден."));return 1;}
                if(name.equals("DRetry")&&++retry<3)return 1;
                var items=new SimpleInventory(36);if(!name.equals("EEmpty"))items.setStack(5,new ItemStack(Items.COMPASS));
                player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,own,ignored)->new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X4,sync,own,items,4),Text.literal("Player")));
                return 1;
            })));
            d.register(literal("qa_partial").executes(ctx->{partial=true;tab(ctx.getSource().getPlayerOrThrow(),true);return 1;}));
            d.register(literal("qa_kick").executes(ctx->{ctx.getSource().getPlayerOrThrow().networkHandler.disconnect(Text.literal("Isolated QA kick"));return 1;}));
        });
    }
}
