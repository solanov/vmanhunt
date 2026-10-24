package dvelop.vmanhunt;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record SyncPayload(boolean running, boolean finished, long elapsedTicks,
                          List<Entry> splits, int totalScore,
                          int hunterKills, int winner) implements CustomPacketPayload {

    public record Entry(String name, long ticks, int points) {}

    public static final Type<SyncPayload> TYPE = new Type<>(VSManhunt.id("sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC =
            StreamCodec.ofMember(SyncPayload::write, SyncPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(running);
        buf.writeBoolean(finished);
        buf.writeLong(elapsedTicks);
        buf.writeVarInt(splits.size());
        for (Entry e : splits) {
            buf.writeUtf(e.name());
            buf.writeLong(e.ticks());
            buf.writeVarInt(e.points());
        }
        buf.writeVarInt(totalScore);
        buf.writeVarInt(hunterKills);
        buf.writeVarInt(winner);
    }

    private static SyncPayload read(RegistryFriendlyByteBuf buf) {
        boolean running = buf.readBoolean();
        boolean finished = buf.readBoolean();
        long elapsed = buf.readLong();
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUtf(), buf.readLong(), buf.readVarInt()));
        }
        int total = buf.readVarInt();
        int kills = buf.readVarInt();
        int winner = buf.readVarInt();
        return new SyncPayload(running, finished, elapsed, list, total, kills, winner);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}