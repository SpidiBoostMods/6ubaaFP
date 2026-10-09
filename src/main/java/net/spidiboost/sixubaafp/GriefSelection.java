package net.spidiboost.sixubaafp;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** A bare number keeps the original start-to-56 command; lists and ranges are exact selections. */
public final class GriefSelection {
    private static final Pattern PART = Pattern.compile("(?U)\\s*([0-9]+)\\s*(?:-\\s*([0-9]+)\\s*)?");
    private GriefSelection() {}

    public static List<Integer> from(int first) {
        validate(first);
        return IntStream.rangeClosed(first, 56).boxed().toList();
    }

    public static List<Integer> parse(String specification) {
        if (specification == null || specification.isBlank())
            throw new IllegalArgumentException("Укажите грифы: например 3-5, 8-12, 53, 56.");
        boolean exact = specification.contains(",") || specification.contains("-");
        var selected = new TreeSet<Integer>();
        for (String part : specification.split(",", -1)) {
            var matcher = PART.matcher(part);
            if (!matcher.matches())
                throw new IllegalArgumentException("Неверный список грифов. Пример: 3-5, 8-12, 53, 56.");
            int first = number(matcher.group(1));
            int last = matcher.group(2) == null ? first : number(matcher.group(2));
            if (first > last)
                throw new IllegalArgumentException("Начало диапазона должно быть не больше конца: " + first + "-" + last + ".");
            if (!exact) return from(first);
            for (int grief = first; grief <= last; grief++) selected.add(grief);
        }
        return List.copyOf(selected);
    }

    private static int number(String value) {
        try {
            int number = Integer.parseInt(value);
            validate(number);
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Номера грифов должны быть от 1 до 56.");
        }
    }
    private static void validate(int grief) {
        if (grief < 1 || grief > 56)
            throw new IllegalArgumentException("Номера грифов должны быть от 1 до 56.");
    }
}
