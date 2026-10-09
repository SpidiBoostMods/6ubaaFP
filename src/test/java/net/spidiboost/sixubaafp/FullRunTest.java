package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FullRunTest {
    @TempDir Path directory;

    @Test void emptyGriefsAreVisitedButNeverWritten() {
        var probe=new Probe();var full=new FullRun(probe,1500);full.begin(0);long now=1;
        for(int grief=1;grief<=56;grief++){
            routeToSelection(full,grief,now);
            full.tick(view(false,null,grief*2L,grief),now+1);
            full.tick(view(false,null,grief*2L,grief),now+1600);
            full.scanFinished(true,List.of(),"empty",now+1601);now+=2400;
        }
        assertFalse(full.active());assertTrue(probe.complete);assertEquals(56,full.completedGriefs());assertTrue(full.lines().isEmpty());assertTrue(probe.persisted.isEmpty());
    }

    @Test void all56MappingsAndBothPagesHaveExactSlotsWithoutSeparators() {
        int[] expected = {0,1,2,3,5,6,7,8,9,10,11,12,14,15,16,17,18,19,20,21,23,24,25,26,27,28,29,30,32,33,34,35};
        for (int grief = 1; grief <= 56; grief++) {
            assertEquals(expected[(grief - 1) % 32], ServerMenus.slotFor(grief), "grief " + grief);
            assertEquals(grief <= 32 ? 1 : 2, ServerMenus.pageFor(grief));
            assertTrue(griefs(grief <= 32 ? 1 : 2).target(grief));
            assertFalse(griefs(grief <= 32 ? 2 : 1).target(grief));
        }
        assertThrows(IllegalArgumentException.class, () -> ServerMenus.slotFor(0));
        assertThrows(IllegalArgumentException.class, () -> ServerMenus.slotFor(57));
        assertEquals(2, ServerMenus.griefNumber("§6§lГРИФ §c#2 (1.21.11)"));
        assertEquals(0, ServerMenus.griefNumber("ГРИФ ВЫЖИВАНИЕ (1.21.11)"));
    }

    @Test void exactCompassAndRootItemRequired() {
        assertTrue(ServerMenus.compass(new ServerMenus.Item("minecraft:compass", "§6ВЫБОР СЕРВЕРА §7(ПКМ)")));
        assertFalse(ServerMenus.compass(new ServerMenus.Item("minecraft:recovery_compass", "ВЫБОР СЕРВЕРА (ПКМ)")));
        assertFalse(ServerMenus.compass(new ServerMenus.Item("minecraft:compass", "Обычный компас")));
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        full.tick(view(false, null, 0, 1), 10);
        assertEquals(List.of("hub"), probe.actions);
        full.tick(view(true, null, 0, 1), 11);
        var wrong = items(45); wrong.set(21, new ServerMenus.Item("minecraft:tnt", "ГРИФ ВЫЖИВАНИЕ (1.20)"));
        full.tick(view(true, new ServerMenus.Menu(10, "Выбор сервера", wrong), 0, 1), 12);
        assertEquals(List.of("hub", "compass"), probe.actions);
        full.tick(view(true, root(), 0, 1), 12); // Same timestamp, no artificial tick delay.
        assertEquals("click:10:21", probe.actions.getLast());
    }

    @Test void complete56GriefsClicksPagesAndWrites56Lines() throws Exception {
        var probe = new Probe(); var full = new FullRun(probe, 5000); probe.full = full;
        full.begin(0);
        long now = 1;
        for (int grief = 1; grief <= 56; grief++) {
            routeToSelection(full, grief, now);
            assertEquals(FullRun.Phase.WAIT_TRANSFER, full.phase());
            assertEquals("click:" + (grief <= 32 ? 11 : 12) + ":" + ServerMenus.slotFor(grief), probe.actions.getLast());
            full.menuClosed(grief <= 32 ? 11 : 12);
            full.tick(view(false, null, grief * 2L, grief), now + 1);
            assertEquals(FullRun.Phase.WAIT_TAB, full.phase());
            full.tick(view(false, null, grief * 2L, grief), now + 1600);
            assertEquals(FullRun.Phase.SCANNING, full.phase());
            full.scanFinished(true, List.of("Nick" + grief, "Other" + grief), "done", now + 1601);
            now += 2400;
        }
        assertFalse(full.active()); assertTrue(probe.complete);
        assertEquals(56, probe.actions.stream().filter("hub"::equals).count());
        assertEquals(56, probe.actions.stream().filter("compass"::equals).count());
        assertEquals(56, probe.actions.stream().filter("scan"::equals).count());
        assertEquals(56, probe.actions.stream().filter("click:10:21"::equals).count());
        assertEquals(24, probe.actions.stream().filter("click:11:44"::equals).count());
        assertEquals(56, full.lines().size());
        assertEquals("Nick1 Other1 - grief #1", full.lines().getFirst());
        assertEquals("Nick56 Other56 - grief #56", full.lines().getLast());
        Path output = directory.resolve("6ubaafp.txt");
        FpFiles.write(output, List.of("old")); FpFiles.write(output, full.lines());
        assertEquals(full.lines(), Files.readAllLines(output));
        assertEquals("- grief #4", FullRun.format(4, List.of()));
    }

    @Test void everyStartingGriefVisitsOnlyItsRangeAndKeepsActualNumbers() {
        for (int first = 1; first <= 56; first++) {
            var probe = new Probe(); var full = new FullRun(probe, 5000, first);
            assertEquals(first, full.grief());
            full.begin(0);
            long now = 1;
            var expected = new ArrayList<String>();
            for (int grief = first; grief <= 56; grief++) {
                assertEquals(grief, full.grief());
                routeToSelection(full, grief, now);
                assertEquals(FullRun.Phase.WAIT_TRANSFER, full.phase());
                assertEquals("click:" + (grief <= 32 ? 11 : 12) + ":" + ServerMenus.slotFor(grief), probe.actions.getLast());
                full.tick(view(false, null, grief * 2L, grief), now + 1);
                full.tick(view(false, null, grief * 2L, grief), now + 1600);
                assertEquals(FullRun.Phase.SCANNING, full.phase());
                full.scanFinished(true, List.of("Nick" + grief), "done", now + 1601);
                expected.add("Nick" + grief + " - grief #" + grief);
                assertEquals(expected, probe.persisted);
                now += 2400;
            }
            assertFalse(full.active()); assertTrue(probe.complete);
            assertEquals(57 - first, full.completedGriefs());
            assertEquals(57 - first, probe.actions.stream().filter("hub"::equals).count());
            assertEquals(57 - first, probe.actions.stream().filter("scan"::equals).count());
            assertEquals(57 - Math.max(33, first), probe.actions.stream().filter("click:11:44"::equals).count());
            assertEquals(expected, full.lines());
            assertEquals("Nick56 - grief #56", full.lines().getLast());
        }
    }

    @Test void startingGriefMustBeWithinServerRangeBeforeAnySideEffects() {
        var probe = new Probe();
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 57, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new FullRun(probe, 5000, invalid));
        assertTrue(probe.actions.isEmpty()); assertEquals(0, probe.finishedCalls);
        assertTrue(probe.persisted.isEmpty());
    }

    @Test void stoppingOrDisconnectingStartedRangePreservesOnlyScannedActualGriefs() {
        for (boolean disconnect : new boolean[]{false, true}) {
            var probe = new Probe(); var full = new FullRun(probe, 5000, 33);
            full.begin(0); routeToSelection(full, 33, 1);
            full.tick(view(false, null, 1, 2), 2); full.tick(view(false, null, 1, 2), 1601);
            full.scanFinished(true, List.of("Complete"), "done", 1602);
            routeToSelection(full, 34, 2400);
            full.tick(view(false, null, 2, 3), 2401); full.tick(view(false, null, 2, 3), 4000);
            probe.currentMatches = List.of("Partial");
            if (disconnect) full.tick(new FullRun.View(false, false, false, 2, 3, 3, 0, null), 4001);
            else full.stop("user");
            assertEquals(List.of("Complete - grief #33", "Partial - grief #34"), probe.persisted);
            assertFalse(full.active()); assertEquals(1, probe.finishedCalls);
        }
    }

    @Test void retryClickOnlyWhileSameVerifiedMenuAndWaitForTabStability() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        routeToSelection(full, 1, 1);
        int actions = probe.actions.size();
        full.tick(view(true, griefs(1), 0, 1), 400);
        assertEquals(actions, probe.actions.size());
        full.tick(view(true, griefs(1), 0, 1), 401);
        assertEquals("click:11:0", probe.actions.getLast());
        full.tick(view(true, null, 0, 1), 500); // Closed, but still hub compass: no scan.
        assertEquals(FullRun.Phase.WAIT_TRANSFER, full.phase());
        full.menuClosed(11);
        full.tick(new FullRun.View(true, true, false, 0, 1, 1, 0, null, 1, true), 600); // Position confirms same-world transfer.
        full.tick(new FullRun.View(true, true, false, 0, 1, 1, 0, null, 1, true), 1499);
        assertFalse(probe.actions.contains("scan"));
        full.tick(view(false, null, 0, 2), 1501); // Tab changed late; restart its settle window.
        full.tick(view(false, null, 0, 2), 2250);
        assertFalse(probe.actions.contains("scan"));
        full.tick(view(false, null, 0, 2), 2251);
        assertEquals("scan", probe.actions.getLast());
    }

    @Test void stalePageAndWrongTargetCannotBeClicked() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        full.tick(view(true, null, 0, 1), 1); full.tick(view(true, root(), 0, 1), 1);
        var incorrect = new ArrayList<>(griefs(1).items());
        incorrect.set(0, new ServerMenus.Item("minecraft:player_head", "ГРИФ #2 (1.21.11)"));
        full.tick(view(true, new ServerMenus.Menu(11, "Выбор мира грифа 1/2", incorrect), 0, 1), 1);
        assertEquals("click:10:21", probe.actions.getLast());
        full.tick(view(true, griefs(2), 0, 1), 2);
        assertEquals("click:10:21", probe.actions.getLast());
    }

    @Test void scanFailureRetriesSameGriefWithoutHubAndSuccessReplacesPartialAttempt() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        routeToSelection(full, 1, 1); full.menuClosed(11);
        full.tick(view(false, null, 1, 2), 2); full.tick(view(false, null, 1, 2), 1601);
        full.scanFinished(false, List.of("StalePartial"), "timeout", 1700);
        assertEquals(FullRun.Phase.RETRY_SCAN, full.phase());
        full.tick(view(false, null, 1, 2), 6699);
        assertEquals(1, probe.actions.stream().filter("scan"::equals).count());
        full.tick(view(false, null, 1, 2), 6700);
        full.tick(view(false, null, 1, 2), 7450);
        assertEquals(2, probe.actions.stream().filter("scan"::equals).count());
        assertEquals(1, probe.actions.stream().filter("hub"::equals).count());
        full.scanFinished(true, List.of("Verified"), "done", 7500);
        assertEquals(List.of("Verified - grief #1"), full.lines());
    }

    @Test void stopCancelsOnceInEveryStageAndNeverRestarts() {
        for (int stage = 0; stage <= 7; stage++) {
            var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
            if (stage >= 1) full.tick(view(true, null, 0, 1), 1);
            if (stage >= 2) full.tick(view(true, root(), 0, 1), 1);
            if (stage >= 3) full.tick(view(true, griefs(1), 0, 1), 1);
            if (stage >= 4) { full.menuClosed(11); full.tick(view(false, null, 1, 2), 2); }
            if (stage >= 5) full.tick(view(false, null, 1, 2), 1601);
            if (stage >= 6) full.scanFinished(false, List.of("Partial"), "error", 1700);
            if (stage >= 7) full.tick(view(false, null, 1, 2), 6700);
            probe.currentMatches = List.of("Found");
            full.stop("user");
            int actions = probe.actions.size();
            full.stop("again"); full.tick(view(true, root(), 2, 3), 100000);
            full.scanFinished(true, List.of("Late"), "late", 100001);
            assertEquals(actions, probe.actions.size(), "stage " + stage);
            assertEquals(1, probe.cancelCalls);
            assertEquals(1, probe.finishedCalls);
            assertFalse(probe.complete);
        }
    }

    @Test void stopOnSecondPageWaitIgnoresLatePagePacketAndDoesNotAddUnscannedGrief() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        long clock = 1;
        for (int grief = 1; grief <= 32; grief++) {
            routeToSelection(full, grief, clock); full.menuClosed(11);
            full.tick(view(false, null, grief, grief), clock + 1);
            full.tick(view(false, null, grief, grief), clock + 1600);
            full.scanFinished(true, List.of("Nick" + grief), "done", clock + 1601);
            clock += 2400;
        }
        full.tick(view(true, null, 0, 1), clock);
        full.tick(view(true, null, 0, 1), clock);
        full.tick(view(true, root(), 0, 1), clock);
        full.tick(view(true, griefs(1), 0, 1), clock);
        assertEquals(FullRun.Phase.WAIT_PAGE2, full.phase());
        full.stop("user"); int actions = probe.actions.size();
        full.tick(view(true, griefs(2), 0, 2), clock + 1);
        assertEquals(actions, probe.actions.size());
        assertEquals(32, probe.persisted.size());
        assertEquals("Nick32 - grief #32", probe.persisted.getLast());
    }

    @Test void disconnectPreservesPartialAndWriteFailureDoesNotAdvanceRoute() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        routeToSelection(full, 1, 1); full.menuClosed(11);
        full.tick(view(false, null, 1, 2), 2); full.tick(view(false, null, 1, 2), 1601);
        probe.currentMatches = List.of("Found");
        full.tick(new FullRun.View(false, false, false, 1, 2, 2, 0, null), 1602);
        assertFalse(full.active()); assertEquals(List.of("Found - grief #1"), probe.persisted);
        assertEquals(1, probe.cancelCalls);

        var failed = new Probe(); failed.persistAllowed = false;
        var second = new FullRun(failed, 5000); second.begin(0);
        routeToSelection(second, 1, 1); second.menuClosed(11);
        second.tick(view(false, null, 1, 2), 2); second.tick(view(false, null, 1, 2), 1601);
        second.scanFinished(true, List.of("Found"), "done", 1602);
        assertFalse(second.active()); assertFalse(failed.complete);
        assertEquals(1, failed.actions.stream().filter("hub"::equals).count());
    }

    @Test void temporaryMissingPlayerDuringTransferDoesNotCancelFullRun() {
        var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
        routeToSelection(full, 1, 1); full.menuClosed(11);
        full.tick(new FullRun.View(true, false, false, 1, 2, 2, 0, null), 2);
        assertTrue(full.active()); assertEquals(FullRun.Phase.WAIT_TRANSFER, full.phase());
        full.tick(view(false, null, 1, 2), 1000);
        full.tick(view(false, null, 1, 2), 1750);
        assertEquals(FullRun.Phase.SCANNING, full.phase());
    }

    @Test void stopAndDisconnectAfterFiftyGriefsPreserveCompletedAndPartialRetryResults() {
        for (boolean disconnect : new boolean[]{false, true}) {
            var probe = new Probe(); var full = new FullRun(probe, 5000); full.begin(0);
            long now = 1;
            for (int grief = 1; grief <= 50; grief++) {
                routeToSelection(full, grief, now);
                full.tick(view(false, null, grief, grief), now + 1);
                full.tick(view(false, null, grief, grief), now + 1600);
                full.scanFinished(true, List.of("Nick" + grief), "done", now + 1601);
                now += 2400;
            }
            routeToSelection(full, 51, now);
            full.tick(view(false, null, 51, 51), now + 1);
            full.tick(view(false, null, 51, 51), now + 1600);
            full.scanFinished(false, List.of("PartialOld"), "timeout", now + 1601);
            probe.currentMatches = List.of("PartialOld", "PartialCurrent");
            if (disconnect) full.tick(new FullRun.View(false, false, false, 51, 51, 51, 0, null), now + 1602);
            else full.stop("user");
            assertEquals(51, probe.persisted.size());
            for (int grief = 1; grief <= 50; grief++)
                assertEquals("Nick" + grief + " - grief #" + grief, probe.persisted.get(grief - 1));
            assertEquals("PartialOld PartialCurrent - grief #51", probe.persisted.getLast());
            assertFalse(full.active()); assertEquals(1, probe.finishedCalls);
            full.stop("again"); full.tick(view(true, root(), 52, 52), now + 99999);
            assertEquals(1, probe.finishedCalls); assertEquals(51, probe.persisted.size());
        }
    }

    @Test void inventoryCheckpointsPersistCurrentGriefBeforeStopAndOnlyNonemptyAscendingRows(){
        var probe=new Probe();var full=new FullRun(probe,1500);full.begin(0);long now=1;
        for(int grief=1;grief<=56;grief++){
            routeToSelection(full,grief,now);full.tick(view(false,null,grief*2L,grief),now+1);full.tick(view(false,null,grief*2L,grief),now+1600);
            List<String> names=grief%7==0?List.of("Nick"+grief):List.of();
            assertTrue(full.checkpoint(names));
            if(!names.isEmpty())assertEquals("Nick"+grief+" - grief #"+grief,probe.persisted.getLast());
            full.scanFinished(true,names,"inventory",now+1601);now+=2400;
        }
        assertEquals(8,probe.persisted.size());assertEquals("Nick7 - grief #7",probe.persisted.getFirst());assertEquals("Nick56 - grief #56",probe.persisted.getLast());
        assertFalse(full.active());assertFalse(full.checkpoint(List.of("Late")));
    }

    @Test void inventoryCheckpointContainsCompletedPlusVerifiedPartialAndSurvivesDisconnect(){
        var probe=new Probe();var full=new FullRun(probe,1500);full.begin(0);
        routeToSelection(full,1,1);full.tick(view(false,null,1,1),2);full.tick(view(false,null,1,1),1601);
        full.scanFinished(true,List.of("A"),"done",1602);
        routeToSelection(full,2,2400);full.tick(view(false,null,2,2),2401);full.tick(view(false,null,2,2),4000);
        probe.currentMatches=List.of("B","C");assertTrue(full.checkpoint(probe.currentMatches));
        assertEquals(List.of("A - grief #1","B C - grief #2"),probe.persisted);
        full.stop("disconnect");assertEquals(List.of("A - grief #1","B C - grief #2"),probe.persisted);
    }

    private static void routeToSelection(FullRun full, int grief, long now) {
        full.tick(view(true, null, 0, 1), now);
        full.tick(view(true, null, 0, 1), now);
        full.tick(view(true, root(), 0, 1), now);
        full.tick(view(true, griefs(1), 0, 1), now);
        if (grief > 32) full.tick(view(true, griefs(2), 0, 1), now);
    }
    static ServerMenus.Menu root() {
        var items = items(45); items.set(21, new ServerMenus.Item("minecraft:tnt", "§6§lГРИФ ВЫЖИВАНИЕ §7(1.21.11)"));
        return new ServerMenus.Menu(10, "» Выбор сервера", items);
    }
    static ServerMenus.Menu griefs(int page) {
        var items = items(45);
        for (int grief = page == 1 ? 1 : 33; grief <= (page == 1 ? 32 : 56); grief++)
            items.set(ServerMenus.slotFor(grief), new ServerMenus.Item("minecraft:player_head", "§6ГРИФ §c#" + grief + " §7(1.21.11)"));
        if (page == 1) items.set(44, new ServerMenus.Item("minecraft:arrow", "§6Следующая страница"));
        return new ServerMenus.Menu(page == 1 ? 11 : 12, "» Выбор мира грифа " + page + "/2", items);
    }
    private static ArrayList<ServerMenus.Item> items(int size) {
        var items = new ArrayList<ServerMenus.Item>();
        for (int i = 0; i < size; i++) items.add(new ServerMenus.Item("minecraft:air", ""));
        return items;
    }
    private static FullRun.View view(boolean compass, ServerMenus.Menu menu, long epoch, long tab) {
        return new FullRun.View(true, true, compass, epoch, tab, tab, 0, menu);
    }
    private static final class Probe implements FullRun.Port {
        final List<String> actions = new ArrayList<>();
        List<String> persisted = List.of(), currentMatches = List.of();
        FullRun full;
        int cancelCalls, finishedCalls;
        boolean complete;
        boolean persistAllowed = true;
        public void hub() { actions.add("hub"); }
        public void useCompass() { actions.add("compass"); }
        public void click(int syncId, int slot) { actions.add("click:" + syncId + ":" + slot); }
        public boolean startScan() { actions.add("scan"); return true; }
        public void cancelScan() { cancelCalls++; }
        public List<String> scanMatches() { return currentMatches; }
        public boolean persist(List<String> lines) { persisted = List.copyOf(lines); return persistAllowed; }
        public void status(String message) {}
        public void finished(boolean complete, String reason) { this.complete = complete; finishedCalls++; }
    }
}
