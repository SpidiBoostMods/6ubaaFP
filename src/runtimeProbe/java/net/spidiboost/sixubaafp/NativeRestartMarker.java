package net.spidiboost.sixubaafp;
import java.nio.file.*;
import java.util.*;
/** Standalone launcher substitute: no Minecraft dependencies and no user-instance launch. */
public final class NativeRestartMarker {
    public static void main(String[] args)throws Exception {
        if(args.length!=5||!args[1].equals("--dir")||!args[3].equals("--launch")||!args[4].equals("Updater QA"))throw new AssertionError("Invalid restart arguments");
        Path game=Path.of(args[2]).resolve("instances/Updater QA/minecraft");
        if(Files.exists(game.resolve("mods/QA-1.21.4-1.0.0.jar"))||!Files.exists(game.resolve("mods/QA-1.21.4-1.0.1.jar")))throw new AssertionError("Restart happened before installation");
        Files.writeString(Path.of(args[0]),"PASS old Minecraft exited; new JAR verified; native launcher args preserve instance and Unicode paths\n");
    }
}
