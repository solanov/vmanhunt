package dvelop.vmanhunt;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.Enchantments;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VSManhunt implements ModInitializer {
	public static final String MOD_ID = "vmanhunt";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final RunState STATE = new RunState();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// The packet must be registered on BOTH sides, so it lives in common code.
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);

		ServerTickEvents.END_SERVER_TICK.register(this::onTick);

		// Deaths: the dragon (speedrunner wins) and player-vs-player kills.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!STATE.running || STATE.finished)
				return;
			if (!(entity.level() instanceof ServerLevel level))
				return;
			MinecraftServer server = level.getServer();

			if (entity instanceof EnderDragon) {
				complete(server, Milestones.DRAGON);
				return;
			}
			if (entity instanceof ServerPlayer victim && source.getEntity() instanceof ServerPlayer killer) {
				onPlayerKill(server, victim, killer);
			}
		});

		// Hunters get a fresh compass when they respawn.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (STATE.running && !STATE.finished && STATE.isHunter(newPlayer)) {
				giveCompass(newPlayer);
			}
		});

		// Players who join late get the current state.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcastSync(server));

		registerCommands();

		LOGGER.info("V's Manhunt loaded");
	}

	// ---------- commands ----------

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
			dispatcher.register(Commands.literal("manhunt")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.literal("start")
							.executes(ctx -> start(ctx.getSource())))
					.then(Commands.literal("stop")
							.executes(ctx -> stop(ctx.getSource().getServer())))
					.then(Commands.literal("compass")
							.executes(ctx -> giveAllCompasses(ctx.getSource())))
					.then(Commands.literal("speedrunner")
							.then(Commands.argument("player", EntityArgument.player())
									.executes(ctx -> setSpeedrunner(ctx.getSource(),
											EntityArgument.getPlayer(ctx, "player")))))
					.then(Commands.literal("hunter")
							.then(Commands.literal("add")
									.then(Commands.argument("player", EntityArgument.player())
											.executes(ctx -> addHunter(ctx.getSource(),
													EntityArgument.getPlayer(ctx, "player")))))
							.then(Commands.literal("remove")
									.then(Commands.argument("player", EntityArgument.player())
											.executes(ctx -> removeHunter(ctx.getSource(),
													EntityArgument.getPlayer(ctx, "player")))))));
		});
	}

	private static boolean rolesLocked() {
		return STATE.running && !STATE.finished;
	}

	private static int setSpeedrunner(CommandSourceStack src, ServerPlayer p) {
		if (rolesLocked()) {
			src.sendFailure(Component.literal("Can't change roles during a run. Use /manhunt stop first."));
			return 0;
		}
		STATE.hunters.remove(p.getUUID());
		STATE.speedrunner = p.getUUID();
		src.sendSuccess(() -> Component.literal(p.getName().getString() + " is now the SPEEDRUNNER"), true);
		return 1;
	}

	private static int addHunter(CommandSourceStack src, ServerPlayer p) {
		if (rolesLocked()) {
			src.sendFailure(Component.literal("Can't change roles during a run. Use /manhunt stop first."));
			return 0;
		}
		if (STATE.isSpeedrunner(p)) {
			src.sendFailure(Component.literal(p.getName().getString()
					+ " is the speedrunner. Pick a different speedrunner first."));
			return 0;
		}
		STATE.hunters.add(p.getUUID());
		src.sendSuccess(() -> Component.literal(p.getName().getString() + " is now a HUNTER"), true);
		return 1;
	}

	private static int removeHunter(CommandSourceStack src, ServerPlayer p) {
		if (rolesLocked()) {
			src.sendFailure(Component.literal("Can't change roles during a run. Use /manhunt stop first."));
			return 0;
		}
		STATE.hunters.remove(p.getUUID());
		src.sendSuccess(() -> Component.literal(p.getName().getString() + " is no longer a hunter"), true);
		return 1;
	}

	private static int giveAllCompasses(CommandSourceStack src) {
		for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
			if (STATE.isHunter(p))
				giveCompass(p);
		}
		src.sendSuccess(() -> Component.literal("Gave compasses to all hunters"), true);
		return 1;
	}

	// ---------- run control ----------

	private static int start(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		if (STATE.speedrunner == null) {
			src.sendFailure(Component.literal("Set a speedrunner first: /manhunt speedrunner <player>"));
			return 0;
		}

		STATE.reset();
		// Wipe old advancements so earlier progress doesn't instantly count.
		// Remove this if you always play on a fresh world.
		server.getCommands().performPrefixedCommand(
				server.createCommandSourceStack(), "advancement revoke @a everything");
		STATE.startTick = server.getTickCount();
		STATE.running = true;

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (STATE.isHunter(p))
				giveCompass(p);
		}

		server.getPlayerList().broadcastSystemMessage(Component.literal(
				"Manhunt started! Hunters: " + STATE.hunters.size() + ". Good luck!"), false);
		broadcastSync(server);
		return 1;
	}

	private static int stop(MinecraftServer server) {
		STATE.running = false;
		STATE.finished = false;
		server.getPlayerList().broadcastSystemMessage(Component.literal("Manhunt stopped."), false);
		broadcastSync(server);
		return 1;
	}

	// ---------- per-tick logic ----------

	private void onTick(MinecraftServer server) {
		if (!STATE.running || STATE.finished)
			return;
		int tick = server.getTickCount();

		if (tick % 4 == 0)
			updateCompasses(server);
		if (tick % 20 != 0)
			return; // everything below only needs to run once per second

		for (Milestone m : Milestones.ALL) {
			if (m.advancement().isEmpty() || STATE.splits.containsKey(m.id()))
				continue;
			AdvancementHolder holder = server.getAdvancements().get(Identifier.parse(m.advancement()));
			if (holder == null)
				continue;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (STATE.isSpeedrunner(p) && p.getAdvancements().getOrStartProgress(holder).isDone()) {
					complete(server, m);
					break;
				}
			}
		}

		// Fallback: the dragon was seen alive in the End, and now it's gone (covers
		// /kill).
		ServerLevel end = server.getLevel(Level.END);
		if (end != null && !end.players().isEmpty()) {
			boolean alive = !end.getDragons().isEmpty();
			if (alive) {
				STATE.dragonSeen = true;
			} else if (STATE.dragonSeen) {
				complete(server, Milestones.DRAGON);
			}
		}

		broadcastSync(server); // keeps client clocks accurate
	}

	// ---------- compass ----------

	private static void giveCompass(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(Items.COMPASS)) return; // already has one
		}
		ItemStack compass = new ItemStack(Items.COMPASS);
		compass.enchant(
				p.level().registryAccess()
						.lookupOrThrow(Registries.ENCHANTMENT)
						.getOrThrow(Enchantments.VANISHING_CURSE),
				1);
		if (!inv.add(compass)) {
			p.sendSystemMessage(Component.literal(
					"Your inventory is full! Make room, then ask an operator to run /manhunt compass"));
		}
	}

	/**
	 * Points every hunter's compass at the speedrunner. A lodestone-style compass
	 * whose
	 * target is in another dimension spins on its own, which is exactly what we
	 * want.
	 */
	private static void updateCompasses(MinecraftServer server) {
		if (STATE.speedrunner == null)
			return;
		ServerPlayer target = server.getPlayerList().getPlayer(STATE.speedrunner);
		if (target == null)
			return;

		LodestoneTracker tracker = new LodestoneTracker(
				Optional.of(GlobalPos.of(target.level().dimension(), target.blockPosition())),
				false);

		for (ServerPlayer hunter : server.getPlayerList().getPlayers()) {
			if (!STATE.isHunter(hunter))
				continue;
			Inventory inv = hunter.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.is(Items.COMPASS)
						&& !tracker.equals(stack.get(DataComponents.LODESTONE_TRACKER))) {
					stack.set(DataComponents.LODESTONE_TRACKER, tracker);
				}
			}
		}
	}

	// ---------- kills, splits, and winning ----------

	private static void onPlayerKill(MinecraftServer server, ServerPlayer victim, ServerPlayer killer) {
		if (STATE.speedrunner == null)
			return;

		if (STATE.isHunter(killer) && STATE.isSpeedrunner(victim)) {
			finish(server, RunState.HUNTERS_WIN);
		} else if (STATE.isSpeedrunner(killer) && STATE.isHunter(victim)) {
			STATE.hunterKills++;
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					killer.getName().getString() + " killed hunter " + victim.getName().getString()
							+ "  (+" + RunState.KILL_BONUS + " pts)"),
					false);
			broadcastSync(server);
		}
	}

	static void complete(MinecraftServer server, Milestone m) {
		if (STATE.finished || STATE.splits.containsKey(m.id()))
			return;

		long t = STATE.elapsedTicks(server);
		int pts = m.score(t);
		STATE.splits.put(m.id(), new RunState.Split(m.name(), t, pts));

		server.getPlayerList().broadcastSystemMessage(Component.literal(
				m.name() + " - " + TimeFormat.format(t) + "  (+" + pts + " pts)"), false);

		if (m == Milestones.DRAGON) {
			finish(server, RunState.SPEEDRUNNER_WINS);
		} else {
			broadcastSync(server);
		}
	}

	/** Stops the timer and announces the winner. Safe to call more than once. */
	static void finish(MinecraftServer server, int winner) {
		if (STATE.finished)
			return;

		long t = STATE.elapsedTicks(server); // read before the clock stops
		STATE.finished = true;
		STATE.endTick = server.getTickCount();
		STATE.winner = winner;

		String headline = (winner == RunState.SPEEDRUNNER_WINS) ? "SPEEDRUNNER WINS!" : "HUNTERS WIN!";
		server.getPlayerList().broadcastSystemMessage(Component.literal(
				headline + " Final time " + TimeFormat.format(t)
						+ " | Speedrunner score: " + STATE.totalScore()),
				false);
		broadcastSync(server);
	}

	// ---------- networking ----------

	static void broadcastSync(MinecraftServer server) {
		List<SyncPayload.Entry> entries = STATE.splits.values().stream()
				.map(s -> new SyncPayload.Entry(s.name(), s.ticks(), s.points()))
				.toList();
		SyncPayload payload = new SyncPayload(STATE.running, STATE.finished,
				STATE.elapsedTicks(server), entries, STATE.totalScore(),
				STATE.hunterKills, STATE.winner);

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			// canSend() lets players without the mod still join the server.
			if (ServerPlayNetworking.canSend(p, SyncPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}
}