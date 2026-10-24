package dvelop.vmanhunt;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public class RunState {
    public static final int KILL_BONUS = 10;

    public static final int NO_WINNER = 0;
    public static final int SPEEDRUNNER_WINS = 1;
    public static final int HUNTERS_WIN = 2;

    public record Split(String name, long ticks, int points) {}

    public boolean running, finished, dragonSeen;
    public long startTick, endTick;
    public int hunterKills, winner;

    public UUID speedrunner;
    public final Set<UUID> hunters = new HashSet<>();
    public final Map<String, Split> splits = new LinkedHashMap<>();

    public long elapsedTicks(MinecraftServer server) {
        if (!running) return 0;
        return (finished ? endTick : server.getTickCount()) - startTick;
    }

    public int totalScore() {
        int splitPoints = splits.values().stream().mapToInt(Split::points).sum();
        return splitPoints + hunterKills * KILL_BONUS;
    }

    public boolean isSpeedrunner(ServerPlayer p) {
        return p.getUUID().equals(speedrunner);
    }

    public boolean isHunter(ServerPlayer p) {
        return hunters.contains(p.getUUID());
    }

    /** Clears run progress but keeps who the speedrunner and hunters are. */
    public void reset() {
        running = false;
        finished = false;
        dragonSeen = false;
        hunterKills = 0;
        winner = NO_WINNER;
        splits.clear();
    }
}