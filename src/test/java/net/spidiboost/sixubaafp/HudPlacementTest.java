package net.spidiboost.sixubaafp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class HudPlacementTest {
    @TempDir Path root;
    @Test void defaultsAboveActionBarDragPreservesOffsetAndSurvivesRestart()throws Exception{
        var file=root.resolve("config/6ubaafp-hud.properties");var p=new HudPlacement(file);var b=p.bounds(800,600,248,83);
        assertEquals(276,b.x());assertEquals(439,b.y());assertFalse(p.press(0,0,b));assertFalse(p.drag(1,1,800,600,248,83));
        assertTrue(p.press(b.x()+10,b.y()+12,b));assertTrue(p.drag(110,112,800,600,248,83));assertTrue(p.release());assertFalse(p.release());
        assertEquals(new HudPlacement.Bounds(100,100,248,83),new HudPlacement(file).bounds(800,600,248,83));
    }
    @Test void offscreenDragAndGuiScaleChangesClampVisibleAndCorruptConfigFallsBack()throws Exception{
        var file=root.resolve("hud.properties");Files.writeString(file,"x=not-a-number\ny=999\n");var p=new HudPlacement(file);var b=p.bounds(800,600,248,83);
        p.press(b.x(),b.y(),b);p.drag(99999,-99999,800,600,248,83);p.release();
        assertEquals(new HudPlacement.Bounds(552,0,248,83),p.bounds(800,600,248,83));
        assertEquals(new HudPlacement.Bounds(52,0,248,83),p.bounds(300,200,248,83));
    }
}
