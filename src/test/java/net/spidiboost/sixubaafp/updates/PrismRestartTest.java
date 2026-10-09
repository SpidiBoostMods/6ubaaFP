package net.spidiboost.sixubaafp.updates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrismRestartTest {
    @TempDir Path root;
    private Path game() throws Exception {
        Path instance=root.resolve("Призма с пробелами/instances/1.21.4");Files.createDirectories(instance.resolve("minecraft"));
        Files.writeString(instance.resolve("instance.cfg"),"name=QA");return instance.resolve("minecraft");
    }
    @Test void closedPrismIsDiscoveredWithoutAParentProcess() throws Exception {
        Path game=game(),exe=root.resolve(System.getProperty("os.name").startsWith("Windows")?"prismlauncher.exe":"prismlauncher");
        Files.writeString(exe,"fixture");exe.toFile().setExecutable(true);
        assertEquals(List.of(exe.toString(),"--dir",game.getParent().getParent().getParent().toString(),"--launch","1.21.4"),RestartCommand.discoverPrism(game,List.of(root.resolve("missing"),exe)));
    }
    @Test void noLauncherDoesNotReplayPrismEntryPointOrUnverifiedForeignProgram() throws Exception {
        Path game=game(),foreign=root.resolve("other.exe");Files.writeString(foreign,"fixture");foreign.toFile().setExecutable(true);
        assertTrue(RestartCommand.discoverPrism(game,List.of(foreign)).isEmpty());
        assertTrue(RestartCommand.direct("java",new String[]{"-jar","NewLaunch.jar","org.prismlauncher.EntryPoint"}).isEmpty());
        assertTrue(RestartCommand.prism(root.resolve("minecraft"),"prismlauncher").isEmpty());
    }
    @Test void usesTheExactOriginalPrismInstallationFromNewLaunchJar() throws Exception {
        Path game=game(),install=root.resolve("Prism Launcher 2.app/Contents/MacOS"),jars=install.resolve("jars");Files.createDirectories(jars);
        Path jar=jars.resolve("NewLaunch.jar");Files.writeString(jar,"fixture");
        Path exe=install.resolve(System.getProperty("os.name").startsWith("Windows")?"prismlauncher.exe":"prismlauncher");Files.writeString(exe,"fixture");exe.toFile().setExecutable(true);
        var command=RestartCommand.classpathPrism(game,root.resolve("irrelevant.jar")+java.io.File.pathSeparator+jar);
        assertEquals(exe.toString(),command.getFirst());assertEquals("1.21.4",command.getLast());
    }
    @Test void discoveryCoversMacBundlesPortableWindowsAndPath() throws Exception {
        Path game=game(),home=root.resolve("home");
        var mac=RestartCommand.candidates(game,"Mac OS X",home,Map.of("PATH","/opt/homebrew/bin:/usr/local/bin"));
        assertTrue(mac.contains(Path.of("/Applications/PrismLauncher.app/Contents/MacOS/prismlauncher")));
        assertTrue(mac.contains(home.resolve("Applications/PrismLauncher.app/Contents/MacOS/prismlauncher")));
        assertTrue(mac.contains(Path.of("/opt/homebrew/bin/prismlauncher")));
        var win=RestartCommand.candidates(game,"Windows 11",home,Map.of("LOCALAPPDATA",root.resolve("AppData").toString(),"PATH",root.resolve("Portable").toString()));
        assertTrue(win.contains(root.resolve("AppData/Programs/PrismLauncher/prismlauncher.exe")));
        assertTrue(win.contains(game.getParent().getParent().getParent().resolve("prismlauncher.exe")));
        assertTrue(win.contains(root.resolve("Portable/prismlauncher.exe")));
    }
}
