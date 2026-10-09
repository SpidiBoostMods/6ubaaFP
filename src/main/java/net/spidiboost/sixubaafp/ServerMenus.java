package net.spidiboost.sixubaafp;

import java.util.List;
import java.util.regex.Pattern;

public final class ServerMenus {
    private static final int[] SLOTS = {
            0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 17,
            18, 19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 32, 33, 34, 35};
    private static final Pattern GRIEF = Pattern.compile("(?iu)гриф\\s*#\\s*(\\d{1,2})(?!\\d)");
    private ServerMenus() {}

    public record Item(String id, String name, int count) {
        public Item(String id,String name){this(id,name,id.equals("minecraft:air")||id.isEmpty()?0:1);}
        public Item { name = FpProtocol.normalize(name); }
        public boolean named(String expected) { return name.equals(FpProtocol.normalize(expected)); }
    }
    public record Menu(int syncId, String title, List<Item> items) {
        public Menu { title = FpProtocol.normalize(title); items = List.copyOf(items); }
        public Item at(int slot) { return slot >= 0 && slot < items.size() ? items.get(slot) : new Item("", ""); }
        public boolean root() { return title.contains("выбор сервера") && at(21).named("ГРИФ ВЫЖИВАНИЕ (1.21.11)"); }
        public int page() {
            if (!title.contains("выбор мира грифа")) return 0;
            int first = griefNumber(at(0).name());
            return first == 1 ? 1 : first == 33 ? 2 : 0;
        }
        public boolean target(int grief) {
            return page() == pageFor(grief) && griefNumber(at(slotFor(grief)).name()) == grief
                    && at(slotFor(grief)).id().equals("minecraft:player_head");
        }
        public boolean nextPage() {
            return page() == 1 && at(44).id().equals("minecraft:arrow") && at(44).named("Следующая страница");
        }
    }
    public static boolean compass(Item firstHotbar) {
        return firstHotbar != null && firstHotbar.id().equals("minecraft:compass")
                && firstHotbar.named("ВЫБОР СЕРВЕРА (ПКМ)");
    }
    public static int pageFor(int grief) { validate(grief); return grief <= 32 ? 1 : 2; }
    public static int slotFor(int grief) { validate(grief); return SLOTS[(grief - 1) % 32]; }
    public static int griefNumber(String name) {
        var matcher = GRIEF.matcher(FpProtocol.normalize(name));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
    private static void validate(int grief) {
        if (grief < 1 || grief > 56) throw new IllegalArgumentException("Grief must be 1..56");
    }
}
