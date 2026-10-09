package net.spidiboost.sixubaafp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class FpFiles {
    private FpFiles() {}
    public static Path result(Path minecraft) {return minecraft.toAbsolutePath().normalize().resolve("6ubaafp.txt");}
    public static void write(Path file,List<String> lines) throws IOException {
        Path absolute=file.toAbsolutePath().normalize();Files.createDirectories(absolute.getParent());
        Path temporary=Files.createTempFile(absolute.getParent(),".6ubaafp-",".tmp");
        try {
            Files.writeString(temporary,String.join("\n",lines),StandardCharsets.UTF_8);
            try {Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException error) {Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
}
