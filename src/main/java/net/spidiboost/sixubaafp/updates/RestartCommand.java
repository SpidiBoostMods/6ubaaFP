package net.spidiboost.sixubaafp.updates;

import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import net.fabricmc.loader.api.FabricLoader;

public final class RestartCommand {
    private RestartCommand() {}
    public static List<String> detect(Path gameDir) {
        ProcessHandle process = ProcessHandle.current();
        for (int i = 0; i < 12; i++) {
            var parent = process.parent(); if (parent.isEmpty()) break;
            process = parent.get();
            String executable = process.info().command().orElse("");
            var prism = prism(gameDir, executable);
            if (!prism.isEmpty()) return prism;
        }
        // Prism's "close launcher after launch" leaves Java parented to the OS.
        // Discover the native launcher instead of replaying EntryPoint's dead IPC.
        var fromClasspath = classpathPrism(gameDir, System.getProperty("java.class.path", ""));
        if (!fromClasspath.isEmpty()) return fromClasspath;
        var discovered = discoverPrism(gameDir, candidates(gameDir, System.getProperty("os.name"),
                Path.of(System.getProperty("user.home")), System.getenv()));
        if (!discovered.isEmpty()) return discovered;
        var info = ProcessHandle.current().info();
        if (info.arguments().isPresent()) return direct(info.command().orElse(""), info.arguments().get());
        // Java 21 ProcessHandle does not expose argv on every Windows installation.
        // Fabric and RuntimeMXBean keep already-tokenized arguments; never split the game command.
        return snapshot(info.command().orElse(""), ManagementFactory.getRuntimeMXBean().getInputArguments(),
                System.getProperty("java.class.path", ""), System.getProperty("sun.java.command", ""),
                FabricLoader.getInstance().getLaunchArguments(false));
    }
    public static List<String> classpathPrism(Path gameDir, String classpath) {
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path jar;
            try { jar = Path.of(entry); } catch (InvalidPathException e) { continue; }
            if (jar.getFileName() == null || !jar.getFileName().toString().equals("NewLaunch.jar") || !Files.isRegularFile(jar)) continue;
            Path jars = jar.toAbsolutePath().getParent();
            if (jars == null || jars.getParent() == null) continue;
            var command = discoverPrism(gameDir, List.of(jars.getParent().resolve("prismlauncher"), jars.getParent().resolve("prismlauncher.exe")));
            if (!command.isEmpty()) return command;
        }
        return List.of();
    }
    public static List<String> discoverPrism(Path gameDir, List<Path> candidates) {
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) continue;
            var command = prism(gameDir, candidate.toAbsolutePath().normalize().toString());
            if (!command.isEmpty()) return command;
        }
        return List.of();
    }
    public static List<Path> candidates(Path gameDir, String os, Path home, Map<String,String> env) {
        var paths = new LinkedHashSet<Path>();
        Path game = gameDir.toAbsolutePath().normalize(), instance = game.getParent();
        if (instance != null && instance.getParent() != null && instance.getParent().getParent() != null) {
            Path data = instance.getParent().getParent();
            paths.add(data.resolve("prismlauncher.exe")); paths.add(data.resolve("prismlauncher"));
        }
        if (os.startsWith("Mac")) {
            paths.add(Path.of("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"));
            paths.add(Path.of("/Applications/PrismLauncher.app/Contents/MacOS/prismlauncher"));
            paths.add(home.resolve("Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"));
            paths.add(home.resolve("Applications/PrismLauncher.app/Contents/MacOS/prismlauncher"));
            for (Path apps : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                try (var entries = Files.newDirectoryStream(apps, "*Prism*.app")) {
                    var bundles = new ArrayList<Path>(); entries.forEach(bundles::add); bundles.sort(Comparator.naturalOrder());
                    for (Path bundle : bundles) paths.add(bundle.resolve("Contents/MacOS/prismlauncher"));
                } catch (java.io.IOException ignored) { }
            }
        } else if (os.startsWith("Windows")) {
            for (String variable : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")) {
                String dir = env.get(variable); if (dir == null || dir.isBlank()) continue;
                paths.add(Path.of(dir, "PrismLauncher", "prismlauncher.exe"));
                paths.add(Path.of(dir, "Programs", "PrismLauncher", "prismlauncher.exe"));
            }
        }
        String path = env.getOrDefault("PATH", "");
        for (String dir : path.split(java.util.regex.Pattern.quote(os.startsWith("Windows") ? ";" : ":"))) {
            if (!dir.isBlank()) paths.add(Path.of(dir, os.startsWith("Windows") ? "prismlauncher.exe" : "prismlauncher"));
        }
        return List.copyOf(paths);
    }
    public static List<String> snapshot(String executable, List<String> vmArgs, String classpath,
                                        String mainCommand, String[] gameArgs) {
        String main = null;
        for (String candidate : List.of("net.fabricmc.loader.impl.launch.knot.KnotClient", "net.fabricmc.loader.launch.knot.KnotClient"))
            if (mainCommand.equals(candidate) || mainCommand.startsWith(candidate + " ")) main = candidate;
        if (main == null || classpath.isEmpty()) return List.of();
        var args = new ArrayList<String>(vmArgs);
        args.add("-cp"); args.add(classpath); args.add(main); args.addAll(List.of(gameArgs));
        return direct(executable, args.toArray(String[]::new));
    }
    public static List<String> prism(Path gameDir, String executable) {
        if (executable.isEmpty()) return List.of();
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.equals("prismlauncher") && !name.equals("prismlauncher.exe")) return List.of();
        Path game = gameDir.toAbsolutePath().normalize();
        if (game.getFileName() == null || !(game.getFileName().toString().equals("minecraft")
                || game.getFileName().toString().equals(".minecraft"))) return List.of();
        Path instance = game.getParent(), instances = instance.getParent();
        if (instances == null || !instances.getFileName().toString().equals("instances")
                || !Files.isRegularFile(instance.resolve("instance.cfg"))) return List.of();
        return List.of(executable, "--dir", instances.getParent().toString(), "--launch", instance.getFileName().toString());
    }
    public static List<String> direct(String executable, String[] arguments) {
        return direct(executable, arguments, System.getProperty("os.name"), System.getProperty("native.encoding", "UTF-8"));
    }
    public static List<String> direct(String executable, String[] arguments, String os, String nativeEncoding) {
        if (executable.isEmpty()) return List.of();
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Set.of("java", "java.exe", "javaw.exe").contains(name)) return List.of();
        // Launcher wrappers and @argument files can require live IPC or get deleted on exit.
        if (Arrays.stream(arguments).anyMatch(arg -> arg.startsWith("@"))) return List.of();
        boolean knot = Arrays.asList(arguments).contains("net.fabricmc.loader.impl.launch.knot.KnotClient")
                || Arrays.asList(arguments).contains("net.fabricmc.loader.launch.knot.KnotClient");
        if (!knot) return List.of();
        // Java 21's Windows launcher can lose characters outside the system codepage.
        // Never replay a corrupted nickname/classpath; native Prism uses its own launcher.
        if (os.startsWith("Windows")) {
            try {
                var encoder = java.nio.charset.Charset.forName(nativeEncoding).newEncoder();
                if (!encoder.canEncode(executable)) return List.of();
                for (String argument : arguments) if (!encoder.canEncode(argument)) return List.of();
            } catch (IllegalArgumentException e) { return List.of(); }
        }
        var command = new ArrayList<String>(); command.add(executable); command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
}
