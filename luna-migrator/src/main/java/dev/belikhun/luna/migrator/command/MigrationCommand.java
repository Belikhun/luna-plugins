package dev.belikhun.luna.migrator.command;

import dev.belikhun.luna.core.api.auth.OfflineUuid;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.paper.LunaCore;
import dev.belikhun.luna.migrator.service.MigrationDataTransferService;
import dev.belikhun.luna.migrator.service.MigrationStateRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
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
	private static final String PERMISSION_MANUAL = "lunamigrator.manual";
	private static final long CONFIRM_WINDOW_MILLIS = 60_000L;
	private static final int DEFAULT_DISCONNECT_COUNTDOWN_SECONDS = 5;

	private final JavaPlugin plugin;
	private final MigrationStateRepository stateRepository;
	private final MigrationDataTransferService dataTransferService;
	private final ConcurrentMap<UUID, PendingMigration> pendingMigrations;
	private final ConcurrentMap<UUID, ActiveMigration> activeMigrations;

	public MigrationCommand(JavaPlugin plugin, MigrationStateRepository stateRepository, MigrationDataTransferService dataTransferService) {
		this.plugin = plugin;
		this.stateRepository = stateRepository;
		this.dataTransferService = dataTransferService;
		this.pendingMigrations = new ConcurrentHashMap<>();
		this.activeMigrations = new ConcurrentHashMap<>();
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		if (args.length > 0 && "manual".equalsIgnoreCase(args[0])) {
			handleManual(sender, args);
			return;
		}
		if (!(sender instanceof Player player)) {
			sender.sendRichMessage(error("❌ Lệnh này chỉ dùng trong game."));
			return;
		}

		cleanupExpired();
		if (activeMigrations.containsKey(player.getUniqueId())) {
			sender.sendRichMessage(info("ℹ Tiến trình migrate đang chạy. Vui lòng chờ hoàn tất."));
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
		if ("manual".equalsIgnoreCase(legacyUsername) || "confirm".equalsIgnoreCase(legacyUsername) || "status".equalsIgnoreCase(legacyUsername)) {
			sender.sendRichMessage(error("❌ Thiếu tham số cần thiết."));
			return;
		}
		prepareAndShowDetails(player, legacyUsername, false);
	}

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		if (args.length == 0) {
			return List.of("status", "confirm", "ten_cu", "manual");
		}
		if (args.length == 1) {
			return CommandCompletions.filterPrefix(List.of("status", "confirm", "ten_cu", "manual"), args[0]);
		}
		if (args.length == 2 && "manual".equalsIgnoreCase(args[0])) {
			List<String> players = new ArrayList<>();
			for (Player online : Bukkit.getOnlinePlayers()) {
				players.add(online.getName());
			}
			return CommandCompletions.filterPrefix(players, args[1]);
		}
		if (args.length == 3 && "manual".equalsIgnoreCase(args[0])) {
			return CommandCompletions.filterPrefix(List.of("ten_cu"), args[2]);
		}
		return List.of();
	}

	private void handleManual(CommandSender sender, String[] args) {
		if (!sender.hasPermission(PERMISSION_MANUAL)) {
			sender.sendRichMessage(error("❌ Bạn không có quyền dùng /migrate manual."));
			return;
		}
		if (args.length < 3) {
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("manual"), CommandStrings.required("player", "player"), CommandStrings.required("ten_cu", "text")));
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
		if (stateRepository.isMigrated(target.getUniqueId(), legacyUsername)) {
			sender.sendRichMessage(info("ℹ Tài khoản đã migrate trước đó."));
			return;
		}
		if (!stateRepository.hasEligibleSourceData(legacyUsername)) {
			sender.sendRichMessage(error("❌ Không có dữ liệu nguồn hợp lệ cho tên cũ này."));
			return;
		}
		Optional<UUID> claimed = stateRepository.findOnlineUuidByOldUsername(legacyUsername);
		if (claimed.isPresent() && !claimed.get().equals(target.getUniqueId())) {
			sender.sendRichMessage(error("❌ Tên cũ này đã được migrate bởi UUID khác."));
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

	private void prepareAndShowDetails(Player player, String legacyUsername, boolean autoDetected) {
		if (legacyUsername.length() < 3 || legacyUsername.length() > 16) {
			if (autoDetected) {
				player.sendRichMessage(error("❌ Không tìm thấy dữ liệu cũ để migrate tự động."));
				player.sendRichMessage(info("ℹ Nếu tên cũ khác hiện tại, dùng: ") + accent("/migrate <ten_cu>"));
				return;
			}

			player.sendRichMessage(error("❌ Tên cũ không hợp lệ."));
			return;
		}

		if (!validateMigrationCandidate(player.getUniqueId(), legacyUsername, player)) {
			if (autoDetected) {
				player.sendRichMessage(info("ℹ Nếu tên cũ khác tên hiện tại, thử: ") + accent("/migrate <ten_cu>"));
			}
			return;
		}

		pendingMigrations.put(player.getUniqueId(), new PendingMigration(legacyUsername, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS));
		showMigrationDetails(player, legacyUsername, autoDetected);
	}

	private boolean validateMigrationCandidate(UUID onlineUuid, String legacyUsername, CommandSender sender) {
		if (stateRepository.isMigrated(onlineUuid, legacyUsername)) {
			sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("ĐÃ HOÀN TẤT"));
			return false;
		}
		if (!stateRepository.hasEligibleSourceData(legacyUsername)) {
			sender.sendRichMessage(error("❌ Không có dữ liệu nguồn hợp lệ cho tên cũ này."));
			return false;
		}

		Optional<UUID> claimed = stateRepository.findOnlineUuidByOldUsername(legacyUsername);
		if (claimed.isPresent() && !claimed.get().equals(onlineUuid)) {
			sender.sendRichMessage(error("❌ Tên cũ này đã được migrate bởi tài khoản khác."));
			return false;
		}

		return true;
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
		BossBar bossBar = Bukkit.createBossBar("Đang chuẩn bị migrate...", BarColor.BLUE, BarStyle.SOLID);
		bossBar.addPlayer(player);
		bossBar.setProgress(0D);
		bossBar.setVisible(true);
		activeMigrations.put(player.getUniqueId(), new ActiveMigration(bossBar));

		player.sendRichMessage(info("ℹ Đã bắt đầu migrate dữ liệu từ tên cũ ") + accent(legacyUsername) + info("."));
		if (initiator != player) {
			initiator.sendRichMessage(info("ℹ Đang migrate cho ") + accent(player.getName()) + info("..."));
		}

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			MigrationDataTransferService.TransferResult transfer;
			try {
				transfer = dataTransferService.transfer(
					legacyUsername,
					player.getUniqueId(),
					player.getName(),
					update -> onProgressUpdate(player, update)
				);
			} catch (Exception exception) {
				String message = exception.getMessage();
				if (message == null || message.isBlank()) {
					message = exception.getClass().getSimpleName();
				}
				transfer = MigrationDataTransferService.TransferResult.failed(message);
			}

			MigrationDataTransferService.TransferResult finalTransfer = transfer;
			Bukkit.getScheduler().runTask(plugin, () -> finalizeMigration(player, legacyUsername, initiator, clearPendingOnFinish, finalTransfer));
		});
	}

	private void onProgressUpdate(Player player, MigrationDataTransferService.ProgressUpdate update) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			ActiveMigration active = activeMigrations.get(player.getUniqueId());
			if (active == null) {
				return;
			}

			BossBar bar = active.bossBar();
			double percent = clamp(update.percent());
			bar.setProgress(percent);
			bar.setColor(BarColor.BLUE);
			bar.setTitle("⏳ " + update.currentTask() + " • " + String.format(Locale.US, "%.1f", percent * 100D) + "%");
			if (update.taskCompleted() && player.isOnline()) {
				player.sendRichMessage(success("✔ Hoàn tất: ") + info(update.currentTask()));
			}
		});
	}

	private void finalizeMigration(
		Player player,
		String legacyUsername,
		CommandSender initiator,
		boolean clearPendingOnFinish,
		MigrationDataTransferService.TransferResult transfer
	) {
		if (clearPendingOnFinish) {
			pendingMigrations.remove(player.getUniqueId());
		}

		if (!transfer.success()) {
			removeBossBar(player.getUniqueId());
			if (player.isOnline()) {
				player.sendRichMessage(error("❌ Migrate thất bại: " + transfer.message()));
			}
			if (initiator != player) {
				initiator.sendRichMessage(error("❌ Migrate cho " + player.getName() + " thất bại: " + transfer.message()));
			}
			return;
		}

		stateRepository.markMigrated(player.getUniqueId(), legacyUsername);
		if (player.isOnline()) {
			player.sendRichMessage(success("✔ Migrate dữ liệu hoàn tất cho tên cũ ") + accent(legacyUsername) + success("."));
			sendTransferSummary(player, transfer);
		}
		if (initiator != player) {
			initiator.sendRichMessage(success("✔ Đã migrate thành công cho ") + accent(player.getName()) + success("."));
		}

		if (transfer.kickAfterSuccess() && player.isOnline()) {
			int countdown = Math.max(1, plugin.getConfig().getInt("migration.disconnect-countdown-seconds", DEFAULT_DISCONNECT_COUNTDOWN_SECONDS));
			startDisconnectCountdown(player, countdown);
		} else {
			removeBossBar(player.getUniqueId());
		}
	}

	private void startDisconnectCountdown(Player player, int countdownSeconds) {
		ActiveMigration active = activeMigrations.get(player.getUniqueId());
		if (active == null) {
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

		removed.bossBar().removeAll();
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
		var coreConfig = LunaCore.services().configStore();
		String moneySymbol = coreConfig.get("strings.money.currencySymbol").asString("₫");
		boolean moneyGrouping = coreConfig.get("strings.money.grouping").asBoolean(true);
		String moneyFormat = coreConfig.get("strings.money.format").asString("{amount}{symbol}");
		return Formatters.money(amount, moneySymbol, moneyGrouping, moneyFormat);
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
