package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Every comma-separated requirement must hold; counts sum over all server slots. */
public record InventoryQuery(List<Term> terms) {
    private static final Pattern AMOUNT=Pattern.compile("^(.*)\\(\\s*(>=|<=|>|<|=)?\\s*([0-9]+)\\s*\\)\\s*$");
    public record Term(boolean registry,String value,long min,long max) {
        public Term(boolean registry,String value){this(registry,value,1,Long.MAX_VALUE);}
        boolean accepts(ServerMenus.Item item){return item.count()>0&&!item.id().equals("minecraft:air")&&(registry?value.equals(item.id()):value.equals(FpProtocol.normalize(item.name())));}
    }
    public InventoryQuery { terms=List.copyOf(terms);if(terms.isEmpty())throw new IllegalArgumentException("Укажи предметы через запятую."); }
    public static InventoryQuery parse(String input){return parse(input,id->true);}
    public static InventoryQuery parse(String input,Predicate<String> knownId) {
        var terms=new LinkedHashSet<Term>();
        for(String raw:input.split(",",-1)) {
            String value=FpProtocol.normalize(raw);long min=1,max=Long.MAX_VALUE;
            var amount=AMOUNT.matcher(value);
            if(amount.matches()) {
                value=amount.group(1).strip();long n;
                try{n=Long.parseLong(amount.group(3));}catch(NumberFormatException e){throw new IllegalArgumentException("Слишком большое количество: "+raw);}
                String op=amount.group(2)==null?"=":amount.group(2);
                switch(op){
                    case "="->{min=n;max=n;}
                    case ">="->min=Math.max(1,n);
                    case ">"->{if(n==Long.MAX_VALUE)throw new IllegalArgumentException("Слишком большое количество.");min=n+1;}
                    case "<="->max=n;
                    case "<"->max=n-1;
                    default->throw new IllegalArgumentException("Неверное сравнение количества.");
                }
                if(min<1||max<min)throw new IllegalArgumentException("Количество должно быть >0: "+raw);
            }else if(value.matches(".*\\(\\s*(?:[0-9<>=+.-].*|\\)?)"))
                throw new IllegalArgumentException("Неверное количество: "+raw+". Пример: obsidian(6), compass(>=1).");
            if(value.isEmpty())throw new IllegalArgumentException("Пустой предмет между запятыми.");
            boolean registry=value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
            if(!registry&&value.contains(":"))throw new IllegalArgumentException("Неверный ID предмета: "+raw);
            if(!registry&&value.matches("[a-z0-9_./-]+")&&knownId.test("minecraft:"+value)){value="minecraft:"+value;registry=true;}
            terms.add(new Term(registry,value,min,max));
        }
        return new InventoryQuery(List.copyOf(terms));
    }
    public boolean matches(ServerMenus.Item item){return matches(List.of(item));}
    public boolean matches(List<ServerMenus.Item> items) {
        return terms.stream().allMatch(t->{long count=0;for(var item:items)if(t.accepts(item))count+=item.count();return count>=t.min()&&count<=t.max();});
    }
    public String describe(List<ServerMenus.Item> items){var result=new ArrayList<String>();for(var t:terms){long count=0;for(var item:items)if(t.accepts(item))count+=item.count();result.add(t.value()+"="+count+" required="+t.min()+".."+(t.max()==Long.MAX_VALUE?"∞":t.max()));}return String.join("; ",result);}
}
