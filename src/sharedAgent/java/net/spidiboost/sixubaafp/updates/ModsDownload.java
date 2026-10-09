package net.spidiboost.sixubaafp.updates;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Download a second JAR, never rewrite a JAR used by the live client. The exit agent removes the old one. */
public final class ModsDownload {
    private ModsDownload() {}
    private static Path marker(BatchPlan.Change c) {
        return c.staged().getParent().resolve("pending-" + c.destination().getFileName() + ".txt");
    }
    private static String identity(BatchPlan.Change c) {
        return c.target().getFileName() + "\n" + c.oldHash() + "\n" + c.newHash() + "\n";
    }
    public static boolean owns(BatchPlan.Change c) throws IOException {
        Path marker = marker(c);
        return !Files.isSymbolicLink(marker) && Files.isRegularFile(marker)
                && Files.size(marker) <= 4096 && Files.readString(marker, StandardCharsets.UTF_8).equals(identity(c))
                && !Files.isSymbolicLink(c.destination()) && Files.isRegularFile(c.destination())
                && BatchInstall.hash(c.destination()).equals(c.newHash());
    }
    public static void publish(BatchPlan plan) throws IOException {
        BatchInstall.validate(plan);
        for (var c : plan.changes()) publish(c);
    }
    private static void publish(BatchPlan.Change c) throws IOException {
        if (c.destination().equals(c.target())) throw new IOException("Cannot replace a live JAR");
        if (Files.exists(c.destination())) {
            if (owns(c)) return;
            throw new IOException("Foreign destination");
        }
        Path marker = marker(c);
        if (Files.isSymbolicLink(marker) || (Files.exists(marker) && !Files.readString(marker).equals(identity(c))))
            throw new IOException("Foreign pending marker");
        Path temp = Files.createTempFile(c.destination().getParent(), ".spidiboost-download-", ".tmp");
        try {
            Files.copy(c.staged(), temp, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!BatchInstall.hash(temp).equals(c.newHash())) throw new IOException("Download checksum mismatch");
            if (!Files.exists(marker)) Files.writeString(marker, identity(c), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            // No replacement: a concurrently installed user JAR must never be overwritten.
            Files.move(temp, c.destination());
        } finally { Files.deleteIfExists(temp); }
    }
    public static void discard(BatchPlan.Change c) throws IOException {
        if (owns(c)) Files.delete(c.destination());
        forget(c);
    }
    public static void forget(BatchPlan.Change c) throws IOException {
        Path marker = marker(c);
        if (!Files.isSymbolicLink(marker) && Files.isRegularFile(marker) && Files.size(marker) <= 4096
                && Files.readString(marker, StandardCharsets.UTF_8).equals(identity(c))) Files.delete(marker);
    }
}
