package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class GriefSelectionTest {
    @Test void bareNumberKeepsStartTo56ButRangeHasAnExactEnd() {
        assertEquals(IntStream.rangeClosed(3, 56).boxed().toList(), GriefSelection.parse(" 3 "));
        assertEquals(List.of(56), GriefSelection.parse("56"));
        assertEquals(List.of(3, 4, 5), GriefSelection.parse("3-5"));
        assertEquals(List.of(3), GriefSelection.parse("3-3"));
        assertEquals(List.of(3, 53, 56), GriefSelection.parse("3,53,56"));
    }

    @Test void spacesListsOverlapsAndUnsortedSelectionsProduceOneAscendingRoute() {
        var expected = List.of(3,4,5,8,9,10,11,12,53,56);
        for (String text : List.of("3-5,8-12,53,56", "3-5, 8-12, 53, 56",
                " 3 - 5 ,\t8 - 12 , 53 , 56 ", "56,53,8-12,3-5,4,9-11,56", "03-05,08-12,53,56"))
            assertEquals(expected, GriefSelection.parse(text), text);
        assertEquals(IntStream.rangeClosed(1,56).boxed().toList(), GriefSelection.parse("1-56,1-56,1,56"));
    }

    @Test void malformedAndOutOfRangeSpecificationsAreRejectedWhole() {
        for (String text : List.of("", " ", "0", "57", "-1", "2147483648", "1-57", "0-3", "5-3",
                "3,", ",3", "3,,5", "3 5", "3-", "3--5", "3-5-7", "3;5", "3/5", "abc", "3-5,57", "3-5,8-6"))
            assertThrows(IllegalArgumentException.class, () -> GriefSelection.parse(text), text);
        assertThrows(IllegalArgumentException.class, () -> GriefSelection.parse(null));
    }

    @Test void disjointRouteChecksOnlySelectedGriefsAndFinishesAtItsActualEnd() {
        for (String text : List.of("3-5", "3-5,8-12,53,56", "32-34,53", "3,5", "53-53")) {
            var route = GriefSelection.parse(text);
            var probe = new Probe(); var full = new FullRun(probe, 5000, route);
            full.begin(0); long now = 1;
            var expected = new ArrayList<String>();
            for (int grief : route) {
                assertEquals(grief, full.grief());
                select(full, grief, now);
                assertEquals("click:" + (grief <= 32 ? 11 : 12) + ":" + ServerMenus.slotFor(grief), probe.actions.getLast());
                full.tick(world(false, null, grief), now+1);
                full.tick(world(false, null, grief), now+1600);
                assertEquals(FullRun.Phase.SCANNING, full.phase());
                full.scanFinished(true, List.of("Nick"+grief), "done", now+1601);
                expected.add("Nick"+grief+" - grief #"+grief);
                assertEquals(expected, probe.saved);
                now+=2400;
            }
            assertFalse(full.active()); assertTrue(probe.complete);
            assertEquals(route.size(), full.completedGriefs());
            assertEquals(route.size(), probe.actions.stream().filter("scan"::equals).count());
            assertEquals(route.size(), probe.actions.stream().filter("hub"::equals).count());
            assertEquals(route.stream().filter(n -> n>32).count(), probe.actions.stream().filter("click:11:44"::equals).count());
        }
    }

    @Test void routeIsImmutableAndInvalidConstructorRoutesHaveNoSideEffects() {
        var probe = new Probe(); var route = new ArrayList<>(List.of(3, 5));
        var full = new FullRun(probe, 5000, route); route.clear();
        full.begin(0); select(full,3,1); full.tick(world(false,null,3),2); full.tick(world(false,null,3),1601);
        full.scanFinished(true,List.of(),"done",1602);
        assertEquals(5,full.grief());
        for (List<Integer> bad : List.of(List.<Integer>of(), List.of(0), List.of(57), List.of(3,3), List.of(5,3))) {
            var other = new Probe();
            assertThrows(IllegalArgumentException.class, () -> new FullRun(other,5000,bad));
            assertTrue(other.actions.isEmpty());
        }
    }

    @Test void retryStaysOnSelectedGriefAndStopOrDisconnectKeepsExactPartialNumber() {
        for (boolean disconnect : List.of(false,true)) {
            var probe = new Probe(); var full = new FullRun(probe,5000,List.of(3,53,56));
            full.begin(0); select(full,3,1); full.tick(world(false,null,3),2); full.tick(world(false,null,3),1601);
            full.scanFinished(true,List.of("Complete"),"done",1602);
            select(full,53,2400); full.tick(world(false,null,53),2401); full.tick(world(false,null,53),4000);
            full.scanFinished(false,List.of("PartialOld"),"timeout",4100);
            full.tick(world(false,null,53),9100); full.tick(world(false,null,53),9850);
            assertEquals(53,full.grief()); assertEquals(FullRun.Phase.SCANNING,full.phase());
            assertEquals(2,probe.actions.stream().filter("hub"::equals).count());
            probe.matches=List.of("PartialCurrent");
            if(disconnect)full.tick(new FullRun.View(false,false,false,0,0,0,0,null),9851);
            else full.stop("user");
            assertFalse(full.active());
            assertEquals(List.of("Complete - grief #3","PartialOld PartialCurrent - grief #53"),probe.saved);
        }
    }

    private static FullRun.View world(boolean compass, ServerMenus.Menu menu, int grief) {
        return new FullRun.View(true,true,compass,compass?0:grief,1,compass?1:grief+1,0,menu);
    }
    private static void select(FullRun full, int grief, long now) {
        full.tick(world(true,null,grief),now); full.tick(world(true,null,grief),now);
        full.tick(world(true,FullRunTest.root(),grief),now);
        full.tick(world(true,FullRunTest.griefs(1),grief),now);
        if(grief>32)full.tick(world(true,FullRunTest.griefs(2),grief),now);
    }
    private static final class Probe implements FullRun.Port {
        final List<String> actions=new ArrayList<>();
        List<String> saved=List.of(),matches=List.of(); boolean complete;
        public void hub(){actions.add("hub");}
        public void useCompass(){actions.add("compass");}
        public void click(int sync,int slot){actions.add("click:"+sync+":"+slot);}
        public boolean startScan(){actions.add("scan");return true;}
        public void cancelScan(){}
        public List<String> scanMatches(){return matches;}
        public boolean persist(List<String> lines){saved=List.copyOf(lines);return true;}
        public void status(String message){}
        public void finished(boolean complete,String reason){this.complete=complete;}
    }
}
