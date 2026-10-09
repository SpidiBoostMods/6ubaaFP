package net.spidiboost.sixubaafp;

import java.util.*;

/** Commas separate alternatives; display names are exact, case-insensitive, formatting-free. */
public record InventoryQuery(List<Term> terms) {
    public record Term(boolean registry, String value) {}
    public InventoryQuery { terms=List.copyOf(terms);if(terms.isEmpty())throw new IllegalArgumentException("Укажи предметы через запятую."); }
    public static InventoryQuery parse(String input) {
        var terms=new LinkedHashSet<Term>();
        for(String raw:input.split(",",-1)) {
            String value=FpProtocol.normalize(raw);
            if(value.isEmpty())throw new IllegalArgumentException("Пустой предмет между запятыми.");
            boolean registry=value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
            if(!registry&&value.startsWith("minecraft:"))throw new IllegalArgumentException("Неверный ID предмета: "+raw);
            terms.add(new Term(registry,value));
        }
        return new InventoryQuery(List.copyOf(terms));
    }
    public boolean matches(ServerMenus.Item item) {
        if(item.id().equals("minecraft:air"))return false;
        String name=FpProtocol.normalize(item.name());
        return terms.stream().anyMatch(t->t.registry()?t.value().equals(item.id()):t.value().equals(name));
    }
}
