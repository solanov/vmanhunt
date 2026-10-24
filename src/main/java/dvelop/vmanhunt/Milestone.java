package dvelop.vmanhunt;

/** One objective. Times are in game ticks (20 ticks = 1 second). */
public record Milestone(String id, String name, String advancement,
        int maxPoints, long fastTicks, long slowTicks) {

    public static Milestone of(String id, String name, String advancement,
            int maxPoints, int fastMinutes, int slowMinutes) {
        return new Milestone(id, name, advancement, maxPoints,
                fastMinutes * 60L * 20L, slowMinutes * 60L * 20L);
    }

    /** Max points at or under fastTicks, falling linearly to 10% at slowTicks. */
    public int score(long ticks) {
        if (ticks <= fastTicks)
            return maxPoints;
        if (ticks >= slowTicks)
            return maxPoints / 10;
        double t = (double) (ticks - fastTicks) / (slowTicks - fastTicks);
        return (int) Math.round(maxPoints * (1.0 - 0.9 * t));
    }
}