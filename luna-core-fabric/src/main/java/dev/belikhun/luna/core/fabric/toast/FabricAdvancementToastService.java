package dev.belikhun.luna.core.fabric.toast;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.fabric.util.FabricPlayerNames;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.criterion.ImpossibleTrigger;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class FabricAdvancementToastService implements AutoCloseable {
	private static final long REMOVE_DELAY_MILLIS = 2000L;
	private static final String DEFAULT_TITLE = "Bạn được nhắc đến";
	private static final String DEFAULT_KEY_PREFIX = "mention_toast";
	private static final String CRITERION_NAME = "trigger";
	private static final ImpossibleTrigger IMPOSSIBLE_TRIGGER = new ImpossibleTrigger();
	private static final Identifier TOAST_BACKGROUND = Identifier.tryParse("minecraft:textures/gui/advancements/backgrounds/adventure.png");

	private final LunaLogger logger;
	private final Supplier<MinecraftServer> serverSupplier;
	private final ScheduledExecutorService cleanupExecutor;

	public FabricAdvancementToastService(LunaLogger logger, Supplier<MinecraftServer> serverSupplier) {
		this.logger = logger == null ? LunaLogger.forLogger(java.util.logging.Logger.getLogger("FabricAdvancementToastService"), true) : logger.scope("Toast");
		this.serverSupplier = serverSupplier == null ? () -> null : serverSupplier;
		this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "luna-fabric-toast-cleanup");
			thread.setDaemon(true);
			return thread;
		});
	}

	public ToastResult sendOneShot(ServerPlayer player, String keyPrefix, String titleText, String subtitleText) {
		if (player == null) {
			return ToastResult.fail("player is null");
		}

		MinecraftServer server = serverSupplier.get();
		if (server == null) {
			return ToastResult.fail("server is null");
		}

		String normalizedTitle = normalizeText(titleText);
		String normalizedSubtitle = normalizeText(subtitleText);
		String toastText = normalizedSubtitle.isBlank() ? normalizedTitle : normalizedSubtitle;
		if (toastText.isBlank()) {
			toastText = DEFAULT_TITLE;
		}

		Identifier advancementId = Identifier.tryParse("luna:" + buildPath(keyPrefix, player.getUUID()));
		if (advancementId == null) {
			return ToastResult.fail("invalid advancement id");
		}

		AdvancementRequirements requirements = AdvancementRequirements.allOf(List.of(CRITERION_NAME));
		AdvancementHolder holder = new AdvancementHolder(
			advancementId,
			new Advancement(
				Optional.empty(),
				Optional.of(new DisplayInfo(
					new ItemStack(Items.PAPER),
					Component.literal(toastText),
					Component.empty(),
					Optional.ofNullable(TOAST_BACKGROUND).map(ClientAsset.ResourceTexture::new),
					AdvancementType.GOAL,
					true,
					false,
					true
				)),
				AdvancementRewards.EMPTY,
				Map.of(CRITERION_NAME, new Criterion<>(IMPOSSIBLE_TRIGGER, new ImpossibleTrigger.TriggerInstance())),
				requirements,
				false
			)
		);

		AdvancementProgress progress = new AdvancementProgress();
		progress.update(requirements);
		progress.grantProgress(CRITERION_NAME);

		try {
			player.connection.send(new ClientboundUpdateAdvancementsPacket(
				false,
				List.of(holder),
				Set.of(),
				Map.of(advancementId, progress),
				true
			));
			scheduleRemoval(player.getUUID(), advancementId);
			return ToastResult.ok();
		} catch (Throwable throwable) {
			logger.debug("Không thể gửi fake advancement toast cho " + FabricPlayerNames.resolve(player) + ": " + throwable.getMessage());
			return ToastResult.fail(throwable.getClass().getSimpleName());
		}
	}

	@Override
	public void close() {
		cleanupExecutor.shutdownNow();
	}

	private void scheduleRemoval(UUID playerId, Identifier advancementId) {
		cleanupExecutor.schedule(() -> {
			MinecraftServer server = serverSupplier.get();
			if (server == null) {
				return;
			}

			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) {
					return;
				}

				player.connection.send(new ClientboundUpdateAdvancementsPacket(
					false,
					List.of(),
					Set.of(advancementId),
					Map.of(),
					false
				));
			});
		}, REMOVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
	}

	private String buildPath(String keyPrefix, UUID playerId) {
		String prefix = normalizePath((keyPrefix == null || keyPrefix.isBlank()) ? DEFAULT_KEY_PREFIX : keyPrefix);
		return prefix + "_" + playerId.toString().replace("-", "") + "_" + Long.toHexString(System.nanoTime());
	}

	private String normalizePath(String value) {
		return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9/_.-]", "_");
	}

	private String normalizeText(String value) {
		return value == null ? "" : value.trim();
	}

	public record ToastResult(boolean success, String failureReason) {
		public static ToastResult ok() {
			return new ToastResult(true, "");
		}

		public static ToastResult fail(String failureReason) {
			return new ToastResult(false, failureReason == null ? "unknown" : failureReason);
		}
	}
}
