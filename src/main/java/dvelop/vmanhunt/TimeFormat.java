package dvelop.vmanhunt;

public final class TimeFormat {
    /** HH:MM:SS, for example 00:24:48. */
    public static String format(long ticks) {
        long totalSeconds = ticks / 20;
        return String.format("%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60);
    }

    private TimeFormat() {
    }
}