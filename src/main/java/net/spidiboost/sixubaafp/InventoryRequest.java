package net.spidiboost.sixubaafp;

import java.util.*;
import java.util.regex.Pattern;

/** Split an optional numeric route prefix from comma-separated item requirements. */
public record InventoryRequest(List<Integer> route,String items,int itemOffset) {
    private static final Pattern NUMBER=Pattern.compile("([0-9]+)(?:\\s*-\\s*([0-9]+))?");
    public static InventoryRequest split(String input,boolean full){
        int pos=0;while(pos<input.length()&&Character.isWhitespace(input.charAt(pos)))pos++;
        if(!full||pos==input.length()||!Character.isDigit(input.charAt(pos)))return new InventoryRequest(GriefSelection.from(1),input,0);
        var route=new TreeSet<Integer>();
        while(true){
            var m=NUMBER.matcher(input);m.region(pos,input.length());
            if(!m.lookingAt())throw new IllegalArgumentException("Неверный список грифов. Пример: full 1, 4, 2-5 compass.");
            int first=number(m.group(1)),last=m.group(2)==null?first:number(m.group(2));
            if(first>last)throw new IllegalArgumentException("Начало диапазона больше конца: "+first+"-"+last);
            for(int n=first;n<=last;n++)route.add(n);
            pos=m.end();int end=pos;
            while(pos<input.length()&&Character.isWhitespace(input.charAt(pos)))pos++;
            if(pos<input.length()&&input.charAt(pos)==','){
                pos++;while(pos<input.length()&&Character.isWhitespace(input.charAt(pos)))pos++;
                if(pos==input.length()||!Character.isDigit(input.charAt(pos)))throw new IllegalArgumentException("После запятой в маршруте нужен номер грифа.");
                continue;
            }
            if(pos<input.length()&&Character.isDigit(input.charAt(pos))&&pos>end)continue;
            if(pos==end||pos==input.length())throw new IllegalArgumentException("После грифов укажи предметы через пробел.");
            return new InventoryRequest(List.copyOf(route),input.substring(pos),pos);
        }
    }
    private static int number(String s){try{int n=Integer.parseInt(s);if(n<1||n>56)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Номера грифов должны быть от 1 до 56.");}}
}
