package dev.belikhun.luna.migrator.command;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.api.string.Formatters;
import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.migrator.service.MigrationStateRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MigrationCommand implements BasicCommand {
	private static final String PERMISSION_MANUAL = "lunamigrator.manual";
	private static final long CONFIRM_WINDOW_MILLIS = 60_000L;

	private final MigrationStateRepository stateRepository;
	private final ConcurrentMap<UUID, PendingMigration> pendingMigrations;

	public MigrationCommand(MigrationStateRepository stateRepository) {
		this.stateRepository = stateRepository;
		this.pendingMigrations = new ConcurrentHashMap<>();
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
			if (!stateRepository.hasEligibleSourceData(pending.legacyUsername())) {
				pendingMigrations.remove(player.getUniqueId());
				sender.sendRichMessage(error("❌ Không có dữ liệu nguồn hợp lệ cho tên cũ này."));
				return;
			}
			if (stateRepository.isMigrated(player.getUniqueId(), pending.legacyUsername())) {
				pendingMigrations.remove(player.getUniqueId());
				sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("ĐÃ HOÀN TẤT"));
				return;
			}

			stateRepository.markMigrated(player.getUniqueId(), pending.legacyUsername());
			pendingMigrations.remove(player.getUniqueId());
			sender.sendRichMessage(success("✔ Đã xác nhận migrate thành công cho tên cũ ") + accent(pending.legacyUsername()) + success("."));
			return;
		}

		if (args.length < 1) {
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.required("ten_cu", "text")));
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("confirm")));
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("status")));
			sender.sendRichMessage(CommandStrings.usage("/migrate", CommandStrings.literal("manual"), CommandStrings.required("player", "player"), CommandStrings.required("ten_cu", "text")));
			return;
		}
		String legacyUsername = args[0];
		if ("manual".equalsIgnoreCase(legacyUsername) || "confirm".equalsIgnoreCase(legacyUsername) || "status".equalsIgnoreCase(legacyUsername)) {
			sender.sendRichMessage(error("❌ Thiếu tham số cần thiết."));
			return;
		}
		if (legacyUsername.length() < 3 || legacyUsername.length() > 16) {
			sender.sendRichMessage(error("❌ Tên cũ không hợp lệ."));
			return;
		}
		if (stateRepository.isMigrated(player.getUniqueId(), legacyUsername)) {
			sender.sendRichMessage(info("ℹ Trạng thái migrate: ") + accent("ĐÃ HOÀN TẤT"));
			return;
		}
		if (!stateRepository.hasEligibleSourceData(legacyUsername)) {
			sender.sendRichMessage(error("❌ Không có dữ liệu nguồn hợp lệ cho tên cũ này."));
			return;
		}
		Optional<UUID> claimed = stateRepository.findOnlineUuidByOldUsername(legacyUsername);
		if (claimed.isPresent() && !claimed.get().equals(player.getUniqueId())) {
			sender.sendRichMessage(error("❌ Tên cũ này đã được migrate bởi tài khoản khác."));
			return;
		}

		pendingMigrations.put(player.getUniqueId(), new PendingMigration(legacyUsername, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS));
		sender.sendRichMessage(info("ℹ Đã ghi nhận tên cũ ") + accent(legacyUsername) + info(". Dùng ") + accent("/migrate confirm")
			+ muted(" trong " + Formatters.duration(Duration.ofMillis(CONFIRM_WINDOW_MILLIS)) + " để hoàn tất."));
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
		stateRepository.markMigrated(target.getUniqueId(), legacyUsername);
		sender.sendRichMessage(success("✔ Đã migrate thủ công cho ") + accent(target.getName()) + success(" với tên cũ ") + accent(legacyUsername) + success("."));
		target.sendRichMessage(success("✔ Dữ liệu migrate của bạn đã được xác nhận bởi quản trị viên lúc " + Formatters.date(java.time.Instant.now()) + "."));
	}

	private void cleanupExpired() {
		long now = System.currentTimeMillis();
		pendingMigrations.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochMillis() < now);
	}

	private record PendingMigration(String legacyUsername, long expiresAtEpochMillis) {
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
}
