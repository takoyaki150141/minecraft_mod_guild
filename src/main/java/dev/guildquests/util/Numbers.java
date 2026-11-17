package dev.guildquests.util;

import java.util.Locale;

/** Thousands separators for user-facing numbers (1,234,567). */
public final class Numbers {
    private Numbers() {
    }

    /** 1234567 -> "1,234,567". */
    public static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Rounds to a whole number and groups it: 1234.5 -> "1,235". */
    public static String format(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /** Groups a coin amount and appends the unit: 1234 -> "1,234 GC". */
    public static String gc(long value) {
        return format(value) + " GC";
    }
}
