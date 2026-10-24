package dvelop.vmanhunt;

import java.util.List;

public final class Milestones {
        public static final Milestone DRAGON =
            Milestone.of("dragon", "Kill the Dragon", "minecraft:end/kill_dragon", 500, 45, 120);

    public static final List<Milestone> ALL = List.of(
            Milestone.of("nether", "Enter the Nether", "minecraft:story/enter_the_nether", 100, 10, 40),
            Milestone.of("fortress", "Enter a Fortress", "minecraft:nether/find_fortress", 150, 20, 60),
            Milestone.of("stronghold", "Enter Stronghold", "minecraft:story/follow_ender_eye", 150, 30, 75),
            Milestone.of("end", "Enter the End", "minecraft:story/enter_the_end", 250, 35, 90),
            DRAGON);

    private Milestones() {
    }
}