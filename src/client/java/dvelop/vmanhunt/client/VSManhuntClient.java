package dvelop.vmanhunt.client;

import dvelop.vmanhunt.RunState;
import dvelop.vmanhunt.SyncPayload;
import dvelop.vmanhunt.TimeFormat;
import dvelop.vmanhunt.VSManhunt;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;

public class VSManhuntClient implements ClientModInitializer {
	private static SyncPayload latest;
	private static long ticksSinceSync;

	@Override
	public void onInitializeClient() {
		// Receive state from the server.
		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
			latest = payload;
			ticksSinceSync = 0;
		});

		// Advance the clock smoothly between syncs (pauses when the game pauses).
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (latest != null && latest.running() && !latest.finished() && !mc.isPaused()) {
				ticksSinceSync++;
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> latest = null);

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.CHAT,
				VSManhunt.id("timer"),
				(graphics, tickCounter) -> {
					if (latest == null || !latest.running()) return;

					Minecraft mc = Minecraft.getInstance();
					long elapsed = latest.finished()
							? latest.elapsedTicks()
							: latest.elapsedTicks() + ticksSinceSync;

					int x = 6, y = 6;
					graphics.text(mc.font, "Time: " + TimeFormat.format(elapsed), x, y, 0xFFFFFF55, true);
					y += 12;

					for (SyncPayload.Entry e : latest.splits()) {
						graphics.text(mc.font,
								e.name() + " - " + TimeFormat.format(e.ticks()) + " (+" + e.points() + ")",
								x, y, 0xFFFFFFFF, true);
						y += 10;
					}

					if (latest.hunterKills() > 0) {
						graphics.text(mc.font,
								"Hunter kills: " + latest.hunterKills()
										+ " (+" + (latest.hunterKills() * RunState.KILL_BONUS) + ")",
								x, y, 0xFFFF5555, true);
						y += 10;
					}

					graphics.text(mc.font, "Score: " + latest.totalScore(), x, y + 2, 0xFF55FF55, true);

					if (latest.finished()) {
						String result = latest.winner() == RunState.SPEEDRUNNER_WINS
								? "SPEEDRUNNER WINS!"
								: "HUNTERS WIN!";
						graphics.text(mc.font, result, x, y + 18, 0xFFFFAA00, true);
					}
				});
	}
}