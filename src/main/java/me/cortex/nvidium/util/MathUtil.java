package me.cortex.nvidium.util;

public final class MathUtil {
    private MathUtil() {}

    public static long alignUp(long number, long alignment) {
        long delta = number % alignment;
        return delta == 0?number: number + (alignment - delta);
    }
}
