package net.spidiboost.sixubaafp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRequirementsTest {
    static ServerMenus.Item item(String id,String name,int count){return new ServerMenus.Item("minecraft:"+id,name,count);}
    @Test void exampleRequiresEveryItemAndExactSummedQuantity(){
        var q=InventoryQuery.parse("пасхальный топор, minecraft:totem_of_undying,порыв ветра, minecraft:obsidian(6)");
        var items=new ArrayList<>(List.of(item("stick","Пасхальный топор",1),item("totem_of_undying","Тотем",3),item("wind_charge","Порыв ветра",20),item("obsidian","Обсидиан",2),item("obsidian","Обсидиан",4)));
        assertTrue(q.matches(items));
        for(int i=0;i<items.size();i++){var partial=new ArrayList<>(items);partial.remove(i);assertFalse(q.matches(partial));}
        items.add(item("obsidian","Обсидиан",1));assertFalse(q.matches(items));
    }
    @Test void comparisonTruthTableAlwaysExcludesZero(){
        for(String expression:List.of("(6)","(=6)","(>=6)","(>6)","(<=2)","(<2)","(>0)","(>=0)","")){
            var q=InventoryQuery.parse("compass"+expression);
            for(int n=0;n<20;n++){
                boolean expected=n>0&&switch(expression){case "(6)","(=6)"->n==6;case "(>=6)"->n>=6;case "(>6)"->n>6;case "(<=2)"->n<=2;case "(<2)"->n<2;default->true;};
                assertEquals(expected,q.matches(List.of(item("compass","Whatever",n))),expression+" count="+n);
            }
        }
        for(String bad:List.of("compass(0)","compass(<1)","compass(<=0)","compass(-1)","compass(=>6)","compass()","compass(6","compass(6.5)","compass(999999999999999999999)","compass(>9223372036854775807)"))assertThrows(IllegalArgumentException.class,()->InventoryQuery.parse(bad),bad);
    }
    @Test void namesCountsNormalizationAndDuplicateRequirements(){
        var q=InventoryQuery.parse(" §6Порыв  ВЕТРА ( >= 6 ),порыв ветра(>=6)");assertEquals(1,q.terms().size());
        assertTrue(q.matches(List.of(item("stick","Порыв ветра",3),item("wind_charge","§bПорыв ветра",3))));
        assertFalse(q.matches(List.of(item("wind_charge","Порыв ветра героя",64))));
        assertFalse(q.matches(List.of(item("air","Порыв ветра",64))));
        assertTrue(InventoryQuery.parse("Топор (пасхальный)").matches(item("stick","Топор (пасхальный)",1)));
        assertTrue(InventoryQuery.parse("Топор (пасхальный)(2)").matches(item("stick","Топор (пасхальный)",2)));
    }
    @Test void shorthandKnownIdsAndEnglishNamesStayDistinct(){
        var known=Set.of("minecraft:compass","minecraft:obsidian");
        assertEquals(InventoryQuery.parse("minecraft:compass"),InventoryQuery.parse("compass",known::contains));
        var q=InventoryQuery.parse("Special(2)",known::contains);assertFalse(q.terms().getFirst().registry());assertTrue(q.matches(item("stick","Special",2)));
    }
    @Test void selectedRoutesAcceptSpacesCommasRangesDeduplicateAndSort(){
        for(String input:List.of("1, 4, 2-5 compass", "5 4 3 2 1 compass", "1,4,2 - 5 compass", "1, 2,3,4,6-10, 5,8 compass")){
            var r=InventoryRequest.split(input,true);assertEquals(input.substring(r.itemOffset()),r.items());assertEquals("compass",r.items());
            assertEquals(GriefSelection.from(1).subList(0,input.contains("10")?10:5),r.route());
        }
        assertEquals(List.of(32,33,56),InventoryRequest.split("56,32-33 compass,obsidian(6)",true).route());
        assertEquals(List.of(3),InventoryRequest.split("3 compass",true).route());
        assertEquals(56,InventoryRequest.split("compass",true).route().size());
        assertEquals("compass, obsidian",InventoryRequest.split("compass, obsidian",false).items());
        for(String invalid:List.of("0 compass","57 compass","5-2 compass","1,,2 compass","1-57 compass","1-compass","1, compass","1 2","1-2"))assertThrows(IllegalArgumentException.class,()->InventoryRequest.split(invalid,true),invalid);
    }
    @Test void inventoryScannerUsesWholeSnapshotAndNotAnySingleSlot(){
        var h=new InventoryScanTest.H();h.scan=new InventoryScan(h,()->h.time,InventoryQuery.parse("compass,obsidian(6)"));h.start("A","B","C");
        h.menu(1,List.of(item("compass","Compass",1)));h.tick(5);
        h.menu(2,List.of(item("compass","Compass",1),item("obsidian","Obsidian",2),item("obsidian","Obsidian",4)));h.tick(5);
        h.menu(3,List.of(item("compass","Compass",1),item("obsidian","Obsidian",7)));h.tick(5);
        assertEquals(List.of("B"),h.result.names());assertTrue(h.result.complete());
    }
}
