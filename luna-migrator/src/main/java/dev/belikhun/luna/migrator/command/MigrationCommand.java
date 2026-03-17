package dev.belikhun.luna.migrator.command;

import dev.belikhun.luna.core.api.auth.OfflineUuid;
import dev.belikhun.luna.core.api.messaging.CorePlayerMessageChannels;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.migrator.service.MigrationDataTransferService;
import dev.belikhun.luna.migrator.service.MigrationEligibilityService;
import dev.belikhun.luna.migrator.service.MigrationStateRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MigrationCommand implements BasicCommand {
	private static final String PERMISSION_USE = "lunamigrator.use";
	private static final String PERMISSION_MANUAL = "lunamigrator.manual";
	private static final String PERMISSION_TEST = "lunamigrator.test";
	private static final String PERMISSION_CLEAR = "lunamigrator.clear";
	private static final long CONFIRM_WINDOW_MILLIS = 60_000L;
	private static final long OFFLINE_MIGRATION_DELAY_TICKS = 60L;
	private static final int DEFAULT_DISCONNECT_COUNTDOWN_SECONDS = 5;

	private final JavaPlugin plugin;
	private final MigrationStateRepository stateRepository;
	private final MigrationDataTransferService dataTransferService;
	private final MigrationEligibilityService eligibilityService;
	private final ConcurrentMap<UUID, PendingMigration> pendingMigrations;
	private final ConcurrentMap<UUID, ActiveMigration> activeMigrations;
	private final ConcurrentMap<UUID, String> reconnectBlockMessages;

	public MigrationCommand(
		JavaPlugin plugin,
		MigrationStateRepository stateRepository,
		MigrationDataTransferService dataTransferService,
		MigrationEligibilityService eligibilityService
	) {
		this.plugin = plugin;
		this.stateRepository = stateRepository;
		this.dataTransferService = dataTransferService;
		this.eligibilityService = eligibilityService;
		this.pendingMigrations = new ConcurrentHashMap<>();
		this.activeMigrations = new ConcurrentHashMap<>();
		this.reconnectBlockMessages = new ConcurrentHashMap<>();
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (!sender.hasPermission(PERMISSION_USE)) {
			sender.sendRichMessage(error("❌ Bạn không có quyền dùng /migrate."));
			return;
		}

		if (args.length > 0 && "test".equalsIgnoreCase(args[0])) {
			handleTest(sender, args);
			return;
		}
		if (args.length > 0 && "manual".equalsIgnoreCase(args[0])) {
			handleManual(sender, args);
			return;
		}
		if (args.length > 0 && "clear".equalsIgnoreCase(args[0])) {
			handleClear(sender, args);
			return;
		}
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage(error("❌ Lệnh này chỉ dùng trong game."));
			return;
		}

		cleanupExpired();
		if (activeMigrations.containsKey(player.getUniqueId())) {
			sender.sendRichMessage(reconnectBlockedMessage(player.getUniqueId()));
			return;
		}

		Optional<String> oldUsername = stateRepository.findOldUsername(player.getUniqueId());
		if (oldUsername.isPresent()) {
			sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("ĐÃ HOÀN TẤT") + muted(" (tên cũ: " + oldUsername.get() + ")"));
			return;
		}

		if (args.length > 0 && "status".equalsIgnoreCase(args[0])) {
			PendingMigration pending = pendingMigrations.get(player.getUniqueId());
			if (pending == null) {
				sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("CHƯA THỰC HIỆN"));
				return;
			}
			long remainMs = Math.max(1000L, pending.expiresAtEpochMillis() - System.currentTimeMillis());
			sender.sendRichMessage(info("ℹ Đang chờ xác nhận migrate từ tên cũ ") + accent(pending.legacyUsername())
				+ muted(" (còn " + Formatters.duration(Duration.ofMillis(remainMs)) + ").")
				+ info(" Dùng ") + accent("/migrate confirm") + info("."));
			return;
		}

		if (args.length > 0 && "confirm".equalsIgnoreCase(args[0])) {
			PendingMigration pending = pendingMigrations.get(player.getUniqueId());
			if (pending == null || pending.expiresAtEpochMillis() < System.currentTimeMillis()) {
				pendingMigrations.remove(player.getUniqueId());
				sender.sendRichMessage(error("❌ Không có yêu cầu migrate đang chờ xác nhận."));
				return;
			}

			if (!validateMigrationCandidate(player.getUniqueId(), pending.legacyUsername(), sender)) {
				pendingMigrations.remove(player.getUniqueId());
				return;
			}

			startMigration(player, pending.legacyUsername(), sender, true);
			return;
		}

		if (args.length < 1) {
			prepareAndShowDetails(player, player.getName(), true);
			return;
		}

		String legacyUsername = args[0];
		if ("manual".equalsIgnoreCase(legacyUsername) || "confirm".equalsIgnoreCase(legacyUsername) || "status".equalsIgnoreCase(legacyUsername) || "test".equalsIgnoreCase(legacyUsername) || "clear".equalsIgnoreCase(legacyUsername)) {
			sender.sendRichMessage(error("❌ Thiếu tham số cần thiết."));
			return;
		}
		prepareAndShowDetails(player, legacyUsername, false);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length == 0) {
			return List.of("status", "confirm", "test", "oldname", "manual", "clear");
		}
		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("status", "confirm", "test", "oldname", "manual", "clear"), args[0]);
		}
		if (args.length == 2 && "test".equalsIgnoreCase(args[0])) {
			List<String> players = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				players.add(online.getName());
			}
			players.add("oldname");
			return CommandCompletions.filterPrefix(players, args[1]);
		}
		if (args.length == 3 && "test".equalsIgnoreCase(args[0])) {
			return CommandCompletions.filterPrefix(List.of("oldname"), args[2]);
		}
		if (args.length == 2 && "manual".equalsIgnoreCase(args[0])) {
			List<String> players = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				players.add(online.getName());
			}
			return CommandCompletions.filterPrefix(players, args[1]);
		}
		if (args.length == 3 && "manual".equalsIgnoreCase(args[0])) {
			return CommandCompletions.filterPrefix(List.of("oldname"), args[2]);
		}
		if (args.length == 2 && "clear".equalsIgnoreCase(args[0])) {
			List<String> players = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				players.add(online.getName());
			}
			players.add("player");
			return CommandCompletions.filterPrefix(players, args[1]);
		}
		return List.of();
	}

	private void handleManual(CommandSender sender, String[] args) {
		if (!sender.hasPermission(PERMISSION_MANUAL)) {
			sender.sendRichMessage(error("❌ Bạn không có quyền dùng /migrate manual."));
			return;
		}
		if (args.length < 3) {
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("manual"), CommandStrings.required("player", "player"), CommandStrings.required("oldname", "text")));
			return;
		}
		Player target = Bukkit.getPlayerExact(args[1]);
		if (target == null) {
			sender.sendRichMessage(error("❌ Không tìm thấy player online: " + args[1]));
			return;
		}
		String legacyUsername = args[2];
		if (legacyUsername.length() < 3 || legacyUsername.length() > 16) {
			sender.sendRichMessage(error("❌ Tên cũ không hợp lệ."));
			return;
		}
		if (!validateMigrationCandidate(target.getUniqueId(), legacyUsername, sender)) {
			return;
		}
		if (activeMigrations.containsKey(target.getUniqueId())) {
			sender.sendRichMessage(info("ℹ Người chơi này đang chạy migrate."));
			return;
		}

		sender.sendRichMessage(info("ℹ Đã bắt đầu migrate thủ công cho ") + accent(target.getName()) + info("."));
		target.sendRichMessage(info("ℹ Quản trị viên đã bắt đầu migrate dữ liệu của bạn."));
		startMigration(target, legacyUsername, sender, false);
	}

	private void handleClear(CommandSender sender, String[] args) {
		if (!sender.hasPermission(PERMISSION_CLEAR) && !sender.hasPermission(PERMISSION_MANUAL)) {
			sender.sendRichMessage(error("❌ Bạn không có quyền dùng /migrate clear."));
			return;
		}

		if (args.length < 2) {
			if (sender instanceof Player player) {
				clearMigrationState(sender, player.getUniqueId(), player.getName());
				return;
			}
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("clear"), CommandStrings.required("player", "player")));
			return;
		}

		String targetName = args[1];
		Player targetOnline = Bukkit.getPlayerExact(targetName);
		if (targetOnline != null) {
			clearMigrationState(sender, targetOnline.getUniqueId(), targetOnline.getName());
			return;
		}

		OfflinePlayer targetOffline = Bukkit.getOfflinePlayer(targetName);
		clearMigrationState(sender, targetOffline.getUniqueId(), targetName);
	}

	private void handleTest(CommandSender sender, String[] args) {
		if (!sender.hasPermission(PERMISSION_TEST) && !sender.hasPermission(PERMISSION_MANUAL)) {
			sender.sendRichMessage(error("❌ Bạn không có quyền dùng /migrate test."));
			return;
		}

		Player targetOnline = null;
		UUID targetUuid;
		String targetName;
		String legacyUsername;

		if (args.length <= 1) {
			if (!(sender instanceof Player player)) {
				sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("test"), CommandStrings.required("player", "player"), CommandStrings.required("oldname", "text")));
				return;
			}
			targetOnline = player;
			targetUuid = player.getUniqueId();
			targetName = player.getName();
			legacyUsername = player.getName();
		} else if (args.length == 2) {
			if (sender instanceof Player player) {
				targetOnline = player;
				targetUuid = player.getUniqueId();
				targetName = player.getName();
				legacyUsername = args[1];
			} else {
				targetOnline = Bukkit.getPlayerExact(args[1]);
				if (targetOnline != null) {
					targetUuid = targetOnline.getUniqueId();
					targetName = targetOnline.getName();
					legacyUsername = targetOnline.getName();
				} else {
					OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[1]);
					targetUuid = offlinePlayer.getUniqueId();
					targetName = args[1];
					legacyUsername = args[1];
				}
			}
		} else {
			targetOnline = Bukkit.getPlayerExact(args[1]);
			if (targetOnline != null) {
				targetUuid = targetOnline.getUniqueId();
				targetName = targetOnline.getName();
			} else {
				OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[1]);
				targetUuid = offlinePlayer.getUniqueId();
				targetName = args[1];
			}
			legacyUsername = args[2];
		}

		if (legacyUsername.length() < 3 || legacyUsername.length() > 16) {
			sender.sendRichMessage(error("❌ Tên cũ không hợp lệ (3-16 ký tự)."));
			return;
		}

		sender.sendRichMessage(success("✔ Kết quả test migrate (không ghi dữ liệu):"));
		sender.sendRichMessage(info("ℹ Mục tiêu: ") + accent(targetName) + info(" | UUID: ") + accent(targetUuid.toString()));
		sender.sendRichMessage(info("ℹ Tên cũ kiểm tra: ") + accent(legacyUsername));

		boolean inProgress = isMigrationInProgress(targetUuid);
		sender.sendRichMessage(info("ℹ Tiến trình đang chạy: ") + accent(inProgress ? "CÓ" : "KHÔNG"));

		Optional<String> migratedOldName = stateRepository.findOldUsername(targetUuid);
		sender.sendRichMessage(info("ℹ Đã migrate trước đó: ") + accent(migratedOldName.isPresent() ? "CÓ" : "KHÔNG")
			+ (migratedOldName.isPresent() ? muted(" (tên cũ: " + migratedOldName.get() + ")") : ""));

		boolean eligibleSource = stateRepository.hasEligibleSourceData(legacyUsername);
		sender.sendRichMessage(info("ℹ Có dữ liệu nguồn hợp lệ: ") + accent(eligibleSource ? "CÓ" : "KHÔNG"));

		Optional<UUID> claimed = stateRepository.findOnlineUuidByOldUsername(legacyUsername);
		if (claimed.isPresent() && !claimed.get().equals(targetUuid)) {
			sender.sendRichMessage(error("❌ Tên cũ đang thuộc UUID khác: " + claimed.get()));
		} else {
			sender.sendRichMessage(info("ℹ Trạng thái claim tên cũ: ") + accent("HỢP LỆ"));
		}

		boolean offlineModeRequired = requiresOfflineWorldDataMigration();
		sender.sendRichMessage(info("ℹ Cần migrate offline (file world): ") + accent(offlineModeRequired ? "CÓ" : "KHÔNG"));
		if (offlineModeRequired) {
			sender.sendRichMessage(info("ℹ Chính sách an toàn: ") + accent("ngắt kết nối trước") + info(", chờ ít nhất ") + accent("3 giây") + info(", rồi mới copy file."));
		}

		if (plugin.getConfig().getBoolean("migration.transfer.migrate-money", true)) {
			RegisteredServiceProvider<Economy> economyProvider = Bukkit.getServicesManager().getRegistration(Economy.class);
			sender.sendRichMessage(info("ℹ Vault economy provider: ") + accent(economyProvider != null && economyProvider.getProvider() != null ? "SẴN SÀNG" : "KHÔNG TÌM THẤY"));
		}

		if (plugin.getConfig().getBoolean("migration.transfer.migrate-huskhomes-homes", true)) {
			boolean huskHomesEnabled = plugin.getServer().getPluginManager().isPluginEnabled("HuskHomes");
			sender.sendRichMessage(info("ℹ HuskHomes plugin: ") + accent(huskHomesEnabled ? "SẴN SÀNG" : "CHƯA BẬT"));
		}

		if (targetOnline != null && targetOnline.isOnline()) {
			LunaCore.services().pluginMessaging().send(targetOnline, CorePlayerMessageChannels.CHAT_RELAY, writer -> writer.writeUtf("<yellow>ℹ Đây là tin nhắn test channel migrate.</yellow>"));
			sender.sendRichMessage(info("ℹ Test chat relay: ") + accent("ĐÃ GỬI") + info(" (đến player online)."));
		} else {
			sender.sendRichMessage(info("ℹ Test chat relay: ") + accent("BỎ QUA") + info(" (player không online)."));
		}

		if (eligibleSource && !inProgress && (!claimed.isPresent() || claimed.get().equals(targetUuid))) {
			sender.sendRichMessage(success("✔ Kết luận: hệ thống sẵn sàng chạy migrate cho tổ hợp test này."));
		} else {
			sender.sendRichMessage(error("❌ Kết luận: có điều kiện chưa đạt, xem các dòng cảnh báo ở trên."));
		}
	}

	private void prepareAndShowDetails(Player player, String legacyUsername, boolean autoDetected) {
		if (legacyUsername.length() < 3 || legacyUsername.length() > 16) {
			if (autoDetected) {
				player.sendRichMessage(error("❌ Không tìm thấy dữ liệu cũ để migrate tự động."));
				player.sendRichMessage(info("ℹ Nếu tên cũ khác hiện tại, dùng: ") + accent("/migrate <oldname>"));
				return;
			}

			player.sendRichMessage(error("❌ Tên cũ không hợp lệ."));
			return;
		}

		if (!validateMigrationCandidate(player.getUniqueId(), legacyUsername, player)) {
			if (autoDetected) {
				player.sendRichMessage(info("ℹ Nếu tên cũ khác tên hiện tại, thử: ") + accent("/migrate <oldname>"));
			}
			return;
		}

		pendingMigrations.put(player.getUniqueId(), new PendingMigration(legacyUsername, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS));
		showMigrationDetails(player, legacyUsername, autoDetected);
	}

	private boolean validateMigrationCandidate(UUID onlineUuid, String legacyUsername, CommandSender sender) {
		MigrationEligibilityService.MigrationEligibility eligibility = eligibilityService.evaluate(onlineUuid, legacyUsername);
		if (eligibility.eligible()) {
			return true;
		}

		switch (eligibility.status()) {
			case SAME_UUID -> sender.sendRichMessage(error("❌ Không thể migrate vì UUID cũ trùng UUID hiện tại."));
			case ALREADY_MIGRATED -> sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("ĐÃ HOÀN TẤT"));
			case NO_SOURCE_DATA -> sender.sendRichMessage(error("❌ Không có dữ liệu nguồn hợp lệ cho tên cũ này."));
			case CLAIMED_BY_OTHER -> sender.sendRichMessage(error("❌ Tên cũ này đã được migrate bởi tài khoản khác."));
			case ELIGIBLE -> {
				return true;
			}
		}

		return false;
	}

	private void showMigrationDetails(Player player, String legacyUsername, boolean autoDetected) {
		UUID oldUuid = OfflineUuid.fromUsername(legacyUsername);
		UUID currentUuid = player.getUniqueId();
		long remainMs = Math.max(1000L, pendingMigrations.get(player.getUniqueId()).expiresAtEpochMillis() - System.currentTimeMillis());

		player.sendRichMessage(promptBase("✔ Đã phát hiện dữ liệu cũ sẵn sàng migrate.")
			+ (autoDetected ? promptBase(" (tự động theo tên hiện tại)") : ""));
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("Tên cũ") + promptBase(": ") + promptValue(legacyUsername));
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("UUID cũ") + promptBase(": ") + promptValue(oldUuid.toString()));
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("UUID hiện tại") + promptBase(": ") + promptValue(currentUuid.toString()));
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("Hạng mục sẽ chuyển") + promptBase(":"));
		for (String line : includedDataLines()) {
			player.sendRichMessage(promptBase("• ") + promptKeyword(line));
		}
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("Thực hiện ngay") + promptBase(": ") + promptAction("/migrate confirm"));
		player.sendRichMessage(promptBase("ℹ ") + promptKeyword("Xác nhận còn hiệu lực") + promptBase(": ") + promptValue(Formatters.duration(Duration.ofMillis(remainMs))));
		if (plugin.getConfig().getBoolean("migration.transfer.kick-after-success", true)) {
			int countdown = Math.max(1, plugin.getConfig().getInt("migration.disconnect-countdown-seconds", DEFAULT_DISCONNECT_COUNTDOWN_SECONDS));
			player.sendRichMessage(promptBase("ℹ ") + promptKeyword("Lưu ý") + promptBase(": sau khi hoàn tất, bạn sẽ bị ngắt kết nối sau ") + promptValue(countdown + " giây") + promptBase("."));
		}
	}

	private List<String> includedDataLines() {
		List<String> lines = new ArrayList<>();
		if (plugin.getConfig().getBoolean("migration.transfer.migrate-playerdata", true)) {
			lines.add("Dữ liệu playerdata (inventory, vị trí)");
		}
		if (plugin.getConfig().getBoolean("migration.transfer.migrate-stats", true)) {
			lines.add("Thống kê người chơi (stats)");
		}
		if (plugin.getConfig().getBoolean("migration.transfer.migrate-advancements", true)) {
			lines.add("Tiến trình advancements");
		}
		if (plugin.getConfig().getBoolean("migration.transfer.migrate-money", true)) {
			lines.add("Số xu trong tài khoản");
		}
		if (plugin.getConfig().getBoolean("migration.transfer.migrate-huskhomes-homes", true)) {
			lines.add("Danh sách vị trí nhà đã đặt + dữ liệu người chơi");
		}
		if (lines.isEmpty()) {
			lines.add("Không có hạng mục nào đang bật.");
		}
		return lines;
	}

	private void startMigration(Player player, String legacyUsername, CommandSender initiator, boolean clearPendingOnFinish) {
		reconnectBlockMessages.put(player.getUniqueId(), "<yellow>⏳ Đang migrate dữ liệu. Vui lòng thử lại sau vài giây.</yellow>");

		if (requiresOfflineWorldDataMigration()) {
			activeMigrations.put(player.getUniqueId(), new ActiveMigration(null));
			sendPlayerProgress(player, info("ℹ Để tránh ghi đè dữ liệu mới, bạn sẽ được ngắt kết nối trước khi migrate file world."));
			if (initiator != player) {
				initiator.sendRichMessage(info("ℹ Đang chuyển ") + accent(player.getName()) + info(" sang chế độ migrate an toàn (offline)..."));
			}

			Bukkit.getScheduler().runTask(plugin, () -> player.kick(net.kyori.adventure.text.Component.text("Đang chuẩn bị migrate dữ liệu an toàn. Vui lòng vào lại sau vài giây.")));
			Bukkit.getScheduler().runTaskLater(plugin, () -> runOfflineSafeMigration(player.getUniqueId(), player.getName(), legacyUsername, initiator, clearPendingOnFinish), OFFLINE_MIGRATION_DELAY_TICKS);
			return;
		}

		BossBar bossBar = Bukkit.createBossBar("Đang chuẩn bị migrate...", BarColor.BLUE, BarStyle.SOLID);
		bossBar.addPlayer(player);
		bossBar.setProgress(0D);
		bossBar.setVisible(true);
		activeMigrations.put(player.getUniqueId(), new ActiveMigration(bossBar));

		sendPlayerProgress(player, info("ℹ Đã bắt đầu migrate dữ liệu từ tên cũ ") + accent(legacyUsername) + info("."));
		if (initiator != player) {
			initiator.sendRichMessage(info("ℹ Đang migrate cho ") + accent(player.getName()) + info("..."));
		}

		runMigrationAsync(player.getUniqueId(), player.getName(), legacyUsername, player, initiator, clearPendingOnFinish);
	}

	private void runOfflineSafeMigration(UUID playerUuid, String playerName, String legacyUsername, CommandSender initiator, boolean clearPendingOnFinish) {
		Player stillOnline = Bukkit.getPlayer(playerUuid);
		if (stillOnline != null && stillOnline.isOnline()) {
			activeMigrations.remove(playerUuid);
			reconnectBlockMessages.remove(playerUuid);
			if (initiator != stillOnline) {
				initiator.sendRichMessage(error("❌ " + playerName + " vẫn còn online. Không thể migrate file world an toàn."));
			}
			stillOnline.sendRichMessage(error("❌ Không thể bắt đầu migrate an toàn vì bạn vẫn đang online."));
			return;
		}

		runMigrationAsync(playerUuid, playerName, legacyUsername, null, initiator, clearPendingOnFinish);
	}

	private void runMigrationAsync(UUID playerUuid, String playerName, String legacyUsername, Player livePlayer, CommandSender initiator, boolean clearPendingOnFinish) {

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			MigrationDataTransferService.TransferResult transfer;
			try {
				transfer = dataTransferService.transfer(
					legacyUsername,
					playerUuid,
					playerName,
					update -> {
						if (livePlayer != null) {
							onProgressUpdate(livePlayer, update);
						}
					}
				);
			} catch (Exception exception) {
				String message = exception.getMessage();
				if (message == null || message.isBlank()) {
					message = exception.getClass().getSimpleName();
				}
				transfer = MigrationDataTransferService.TransferResult.failed(message);
			}

			MigrationDataTransferService.TransferResult finalTransfer = transfer;
			Bukkit.getScheduler().runTask(plugin, () -> finalizeMigration(playerUuid, playerName, legacyUsername, initiator, clearPendingOnFinish, finalTransfer));
		});
	}

	private void onProgressUpdate(Player player, MigrationDataTransferService.ProgressUpdate update) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			ActiveMigration active = activeMigrations.get(player.getUniqueId());
			if (active == null || active.bossBar() == null) {
				return;
			}

			BossBar bar = active.bossBar();
			double percent = clamp(update.percent());
			bar.setProgress(percent);
			bar.setColor(BarColor.BLUE);
			bar.setTitle("⏳ " + update.currentTask() + " • " + String.format(Locale.US, "%.1f", percent * 100D) + "%");
			if (update.taskCompleted() && player.isOnline()) {
				reconnectBlockMessages.put(player.getUniqueId(), "<yellow>⏳ Đang migrate dữ liệu: " + update.currentTask() + "</yellow>");
				sendPlayerProgress(player, success("✔ Hoàn tất: ") + info(update.currentTask()));
			}
		});
	}

	private void finalizeMigration(
		UUID playerUuid,
		String playerName,
		String legacyUsername,
		CommandSender initiator,
		boolean clearPendingOnFinish,
		MigrationDataTransferService.TransferResult transfer
	) {
		Player player = Bukkit.getPlayer(playerUuid);
		if (clearPendingOnFinish) {
			pendingMigrations.remove(playerUuid);
		}

		if (!transfer.success()) {
			removeBossBar(playerUuid);
			reconnectBlockMessages.remove(playerUuid);
			if (player != null && player.isOnline()) {
				player.sendRichMessage(error("❌ Migrate thất bại: " + transfer.message()));
			}
			if (initiator != player) {
				initiator.sendRichMessage(error("❌ Migrate cho " + playerName + " thất bại: " + transfer.message()));
			}
			return;
		}

		stateRepository.markMigrated(playerUuid, legacyUsername);
		reconnectBlockMessages.remove(playerUuid);
		if (player != null && player.isOnline()) {
			sendPlayerProgress(player, success("✔ Migrate dữ liệu hoàn tất cho tên cũ ") + accent(legacyUsername) + success("."));
			sendTransferSummary(player, transfer);
		}
		if (initiator != player) {
			initiator.sendRichMessage(success("✔ Đã migrate thành công cho ") + accent(playerName) + success("."));
			sendTransferSummary(initiator, transfer);
		}

		if (player != null && transfer.kickAfterSuccess() && player.isOnline()) {
			int countdown = Math.max(1, plugin.getConfig().getInt("migration.disconnect-countdown-seconds", DEFAULT_DISCONNECT_COUNTDOWN_SECONDS));
			startDisconnectCountdown(player, countdown);
		} else {
			removeBossBar(playerUuid);
		}
	}

	private void startDisconnectCountdown(Player player, int countdownSeconds) {
		ActiveMigration active = activeMigrations.get(player.getUniqueId());
		if (active == null || active.bossBar() == null) {
			return;
		}

		BossBar bar = active.bossBar();
		bar.setColor(BarColor.RED);
		player.sendRichMessage(info("ℹ Hoàn tất migrate. Bạn sẽ bị ngắt kết nối sau ") + accent(String.valueOf(countdownSeconds)) + info(" giây."));

		new BukkitRunnable() {
			private int remaining = countdownSeconds;

			@Override
			public void run() {
				if (!player.isOnline()) {
					removeBossBar(player.getUniqueId());
					cancel();
					return;
				}

				if (remaining <= 0) {
					removeBossBar(player.getUniqueId());
					player.kick(net.kyori.adventure.text.Component.text("Dữ liệu migrate đã hoàn tất. Vui lòng vào lại để tải dữ liệu mới."));
					cancel();
					return;
				}

				bar.setTitle("⏳ Ngắt kết nối sau " + remaining + " giây...");
				bar.setProgress(clamp((double) remaining / (double) countdownSeconds));
				remaining--;
			}
		}.runTaskTimer(plugin, 0L, 20L);
	}

	private void removeBossBar(UUID playerUuid) {
		ActiveMigration removed = activeMigrations.remove(playerUuid);
		if (removed == null) {
			return;
		}

		if (removed.bossBar() != null) {
			removed.bossBar().removeAll();
		}
	}

	private boolean requiresOfflineWorldDataMigration() {
		if (!plugin.getConfig().getBoolean("migration.transfer.enabled", true)) {
			return false;
		}
		return plugin.getConfig().getBoolean("migration.transfer.migrate-playerdata", true)
			|| plugin.getConfig().getBoolean("migration.transfer.migrate-stats", true)
			|| plugin.getConfig().getBoolean("migration.transfer.migrate-advancements", true);
	}

	private void clearMigrationState(CommandSender sender, UUID targetUuid, String targetName) {
		if (isMigrationInProgress(targetUuid)) {
			sender.sendRichMessage(error("❌ Người chơi này đang migrate, không thể clear lúc này."));
			return;
		}

		Optional<String> oldName = stateRepository.findOldUsername(targetUuid);
		boolean cleared = stateRepository.clearMigrated(targetUuid);
		pendingMigrations.remove(targetUuid);
		reconnectBlockMessages.remove(targetUuid);

		if (cleared) {
			sender.sendRichMessage(success("✔ Đã clear trạng thái migrate cho ") + accent(targetName)
				+ (oldName.isPresent() ? muted(" (oldname cũ: " + oldName.get() + ")") : "") + success("."));
		} else {
			sender.sendRichMessage(info("ℹ Không có trạng thái migrate đã lưu cho ") + accent(targetName) + info("."));
		}

		Player targetOnline = Bukkit.getPlayer(targetUuid);
		if (targetOnline != null && targetOnline.isOnline() && targetOnline != sender) {
			targetOnline.sendRichMessage(info("ℹ Quản trị viên đã clear trạng thái migrate của bạn. Bạn có thể chạy lại ") + accent("/migrate") + info(" để test."));
		}
	}

	private void sendPlayerProgress(Player player, String miniMessage) {
		player.sendRichMessage(miniMessage);
		LunaCore.services().pluginMessaging().send(player, CorePlayerMessageChannels.CHAT_RELAY, writer -> writer.writeUtf(miniMessage));
	}

	public boolean isMigrationInProgress(UUID playerUuid) {
		return activeMigrations.containsKey(playerUuid);
	}

	public String reconnectBlockedMessage(UUID playerUuid) {
		return reconnectBlockMessages.getOrDefault(playerUuid, "<yellow>⏳ Đang migrate dữ liệu. Vui lòng thử lại sau vài giây.</yellow>");
	}

	private void sendTransferSummary(CommandSender receiver, MigrationDataTransferService.TransferResult transfer) {
		receiver.sendRichMessage(info("ℹ Đã chuyển ") + accent(String.valueOf(transfer.copiedEntries()))
			+ info(" bản ghi dữ liệu (thiếu ") + accent(String.valueOf(transfer.missingEntries())) + info(")."));
		if (transfer.migratedMoney() > 0D) {
			receiver.sendRichMessage(info("ℹ Đã chuyển số dư Vault: ") + accent(formatMoney(transfer.migratedMoney())));
		}
		if (transfer.migratedHuskHomesHomes() > 0) {
			receiver.sendRichMessage(info("ℹ Đã chuyển nhà: ") + accent(String.valueOf(transfer.migratedHuskHomesHomes())));
		}
		if (transfer.migratedHuskHomesUserData()) {
			receiver.sendRichMessage(info("ℹ Đã chuyển thông tin nhà của người chơi."));
		}
	}

	private String formatMoney(double amount) {
		return Formatters.money(LunaCore.services().configStore(), amount);
	}

	private void cleanupExpired() {
		long now = System.currentTimeMillis();
		pendingMigrations.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() < now);
	}

	private record PendingMigration(String legacyUsername, long expiresAtEpochMillis) {
	}

	private record ActiveMigration(BossBar bossBar) {
	}

	private double clamp(double value) {
		if (value < 0D) {
			return 0D;
		}
		return Math.min(1D, value);
	}

	private String info(String text) {
		return "<color:" + LunaPalette.INFO_500 + ">" + text + "</color>";
	}

	private String success(String text) {
		return "<color:" + LunaPalette.SUCCESS_500 + ">" + text + "</color>";
	}

	private String error(String text) {
		return "<color:" + LunaPalette.DANGER_500 + ">" + text + "</color>";
	}

	private String muted(String text) {
		return "<color:" + LunaPalette.NEUTRAL_300 + ">" + text + "</color>";
	}

	private String accent(String text) {
		return "<color:" + LunaPalette.PRIMARY_300 + ">" + text + "</color>";
	}

	private String promptBase(String text) {
		return "<color:" + LunaPalette.NEUTRAL_50 + ">" + text + "</color>";
	}

	private String promptKeyword(String text) {
		return "<color:" + LunaPalette.NEUTRAL_50 + ">" + text + "</color>";
	}

	private String promptValue(String text) {
		return "<color:" + LunaPalette.AMBER_300 + ">" + text + "</color>";
	}

	private String promptAction(String text) {
		return "<color:" + LunaPalette.SUCCESS_500 + "><u>" + text + "</u></color>";
	}
}
