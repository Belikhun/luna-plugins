package dev.belikhun.luna.migrator.service;

import dev.belikhun.luna.core.api.auth.OfflineUuid;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.user.User;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MigrationDataTransferService {
	private final JavaPlugin plugin;
	private static final ProgressListener NOOP_PROGRESS_LISTENER = update -> {
	};

	public MigrationDataTransferService(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public TransferResult transfer(String legacyUsername, UUID onlineUuid, String onlineUsername) {
		return transfer(legacyUsername, onlineUuid, onlineUsername, NOOP_PROGRESS_LISTENER);
	}

	public TransferResult transfer(String legacyUsername, UUID onlineUuid, String onlineUsername, ProgressListener progressListener) {
		if (!plugin.getConfig().getBoolean("migration.transfer.enabled", true)) {
			return TransferResult.success(0, 0, false, 0D, 0);
		}

		UUID offlineUuid = OfflineUuid.fromUsername(legacyUsername);
		boolean migrateMoney = plugin.getConfig().getBoolean("migration.transfer.migrate-money", true);
		boolean migrateHuskHomes = plugin.getConfig().getBoolean("migration.transfer.migrate-huskhomes-homes", true);
		boolean overwriteExisting = plugin.getConfig().getBoolean("migration.transfer.overwrite-existing", true);
		boolean backupExisting = plugin.getConfig().getBoolean("migration.transfer.backup-existing", true);
		boolean copyPlayerData = plugin.getConfig().getBoolean("migration.transfer.migrate-playerdata", true);
		boolean copyStats = plugin.getConfig().getBoolean("migration.transfer.migrate-stats", true);
		boolean copyAdvancements = plugin.getConfig().getBoolean("migration.transfer.migrate-advancements", true);
		boolean kickAfterSuccess = plugin.getConfig().getBoolean("migration.transfer.kick-after-success", true);
		List<WorldCopySpec> worldCopySpecs = List.of(
			new WorldCopySpec(copyPlayerData, "playerdata", "dat"),
			new WorldCopySpec(copyStats, "stats", "json"),
			new WorldCopySpec(copyAdvancements, "advancements", "json")
		);
		List<WorldCopySpec> enabledWorldCopySpecs = worldCopySpecs.stream().filter(WorldCopySpec::enabled).toList();
		int worldTaskCount = plugin.getServer().getWorlds().size() * enabledWorldCopySpecs.size();
		int totalTasks = worldTaskCount + (migrateMoney ? 1 : 0) + (migrateHuskHomes ? 1 : 0);
		ProgressTracker progressTracker = new ProgressTracker(Math.max(1, totalTasks), progressListener == null ? NOOP_PROGRESS_LISTENER : progressListener);

		int copied = 0;
		int missing = 0;
		int migratedHuskHomesHomes = 0;
		double migratedMoney = 0D;
		List<String> errors = new ArrayList<>();
		String backupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());

		for (World world : plugin.getServer().getWorlds()) {
			File worldFolder = world.getWorldFolder();
			for (WorldCopySpec spec : enabledWorldCopySpecs) {
				String taskName = "World " + world.getName() + " • " + spec.directoryName();
				progressTracker.start(taskName);

				CopyResult result = copyWorldData(
					worldFolder.toPath(),
					spec.directoryName(),
					offlineUuid,
					onlineUuid,
					spec.extension(),
					overwriteExisting,
					backupExisting,
					backupStamp
				);
				copied += result.copied;
				missing += result.missing;
				errors.addAll(result.errors);
				progressTracker.complete(taskName);
			}
		}

		if (migrateMoney) {
			String taskName = "Chuyển số dư Vault";
			progressTracker.start(taskName);
			MoneyTransferResult moneyTransfer = migrateMoney(offlineUuid, onlineUuid);
			if (!moneyTransfer.success()) {
				return TransferResult.failed(moneyTransfer.message());
			}
			migratedMoney = moneyTransfer.transferredAmount();
			progressTracker.complete(taskName);
		}

		if (migrateHuskHomes) {
			String taskName = "Chuyển dữ liệu HuskHomes";
			progressTracker.start(taskName);
			HuskHomesTransferResult huskHomesTransfer = migrateHuskHomesHomes(offlineUuid, legacyUsername, onlineUuid, onlineUsername);
			if (!huskHomesTransfer.success()) {
				return TransferResult.failed(huskHomesTransfer.message());
			}
			migratedHuskHomesHomes = huskHomesTransfer.homesMoved();
			progressTracker.complete(taskName);
		}

		if (!errors.isEmpty()) {
			return TransferResult.failed(errors.get(0));
		}

		if (copied <= 0 && migratedMoney <= 0D && migratedHuskHomesHomes <= 0) {
			return TransferResult.failed("Không tìm thấy dữ liệu world nào để chuyển từ UUID cũ sang UUID mới.");
		}

		return TransferResult.success(copied, missing, kickAfterSuccess, migratedMoney, migratedHuskHomesHomes);
	}

	private HuskHomesTransferResult migrateHuskHomesHomes(UUID offlineUuid, String legacyUsername, UUID onlineUuid, String onlineUsername) {
		if (!plugin.getServer().getPluginManager().isPluginEnabled("HuskHomes")) {
			return HuskHomesTransferResult.failed("Đang bật migrate HuskHomes nhưng plugin HuskHomes chưa được cài hoặc chưa bật.");
		}

		long timeoutSeconds = Math.max(2L, plugin.getConfig().getLong("migration.transfer.huskhomes-timeout-seconds", 10L));
		try {
			HuskHomesAPI api = HuskHomesAPI.getInstance();
			User oldUser = User.of(offlineUuid, legacyUsername);
			User newUser = User.of(onlineUuid, onlineUsername == null ? legacyUsername : onlineUsername);
			List<Home> homes = api.getUserHomes(oldUser).get(timeoutSeconds, TimeUnit.SECONDS);
			if (homes.isEmpty()) {
				return HuskHomesTransferResult.success(0);
			}

			int moved = 0;
			for (Home home : homes) {
				String homeName = home.getName();
				boolean homePublic = home.isPublic();
				api.createHome(newUser, homeName, home, true, false, true, true)
					.get(timeoutSeconds, TimeUnit.SECONDS);
				api.setHomePrivacy(newUser, homeName, homePublic);
				api.deleteHome(home);
				moved++;
			}

			return HuskHomesTransferResult.success(moved);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return HuskHomesTransferResult.failed("Tiến trình migrate HuskHomes đã bị gián đoạn.");
		} catch (TimeoutException exception) {
			return HuskHomesTransferResult.failed("Timeout khi migrate dữ liệu HuskHomes.");
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			String message = cause.getMessage();
			if (message == null || message.isBlank()) {
				message = cause.getClass().getSimpleName();
			}
			return HuskHomesTransferResult.failed("Không thể migrate dữ liệu HuskHomes: " + message);
		} catch (Exception exception) {
			String message = exception.getMessage();
			if (message == null || message.isBlank()) {
				message = exception.getClass().getSimpleName();
			}
			return HuskHomesTransferResult.failed("Không thể migrate dữ liệu HuskHomes: " + message);
		}
	}

	private MoneyTransferResult migrateMoney(UUID offlineUuid, UUID onlineUuid) {
		RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
		if (provider == null || provider.getProvider() == null) {
			return MoneyTransferResult.failed("Không tìm thấy Economy provider từ Vault để migrate tiền.");
		}

		Economy economy = provider.getProvider();
		OfflinePlayer oldPlayer = Bukkit.getOfflinePlayer(offlineUuid);
		OfflinePlayer newPlayer = Bukkit.getOfflinePlayer(onlineUuid);

		double oldBalance = Math.max(0D, economy.getBalance(oldPlayer));
		if (oldBalance <= 0D) {
			return MoneyTransferResult.success(0D);
		}

		if (!economy.hasAccount(newPlayer)) {
			economy.createPlayerAccount(newPlayer);
		}

		EconomyResponse withdraw = economy.withdrawPlayer(oldPlayer, oldBalance);
		if (!withdraw.transactionSuccess()) {
			return MoneyTransferResult.failed("Không thể trừ tiền từ tài khoản cũ: " + withdraw.errorMessage);
		}

		EconomyResponse deposit = economy.depositPlayer(newPlayer, oldBalance);
		if (!deposit.transactionSuccess()) {
			EconomyResponse rollback = economy.depositPlayer(oldPlayer, oldBalance);
			String rollbackState = rollback.transactionSuccess() ? "rollback thành công" : "rollback thất bại";
			return MoneyTransferResult.failed("Không thể cộng tiền vào tài khoản mới: " + deposit.errorMessage + " (" + rollbackState + ")");
		}

		double remains = Math.max(0D, economy.getBalance(oldPlayer));
		if (remains > 0D) {
			economy.withdrawPlayer(oldPlayer, remains);
		}

		return MoneyTransferResult.success(oldBalance);
	}

	private CopyResult copyWorldData(
		Path worldRoot,
		String directoryName,
		UUID fromUuid,
		UUID toUuid,
		String extension,
		boolean overwriteExisting,
		boolean backupExisting,
		String backupStamp
	) {
		Path dir = worldRoot.resolve(directoryName);
		if (!Files.isDirectory(dir)) {
			return new CopyResult(0, 1, List.of());
		}

		Path source = dir.resolve(fromUuid + "." + extension);
		if (!Files.exists(source)) {
			return new CopyResult(0, 1, List.of());
		}

		try {
			backupOriginalSource(worldRoot.getFileName().toString(), directoryName, source, backupStamp);
		} catch (IOException exception) {
			return new CopyResult(0, 0, List.of(
				"Không thể sao lưu dữ liệu gốc " + directoryName + " trong world " + worldRoot.getFileName() + ": " + exception.getMessage()
			));
		}

		Path target = dir.resolve(toUuid + "." + extension);
		try {
			if (Files.exists(target)) {
				if (!overwriteExisting) {
					return new CopyResult(0, 0, List.of());
				}
				if (backupExisting) {
					backupExistingTarget(worldRoot.getFileName().toString(), directoryName, target, backupStamp);
				}
			}

			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			return new CopyResult(1, 0, List.of());
		} catch (IOException exception) {
			return new CopyResult(0, 0, List.of(
				"Không thể chuyển dữ liệu " + directoryName + " trong world " + worldRoot.getFileName() + ": " + exception.getMessage()
			));
		}
	}

	private void backupOriginalSource(String worldName, String directoryName, Path source, String backupStamp) throws IOException {
		Path backupDir = plugin.getDataFolder().toPath()
			.resolve("backup")
			.resolve(backupStamp)
			.resolve("original")
			.resolve(worldName)
			.resolve(directoryName);
		Files.createDirectories(backupDir);
		Files.copy(source, backupDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
	}

	private void backupExistingTarget(String worldName, String directoryName, Path target, String backupStamp) throws IOException {
		Path backupDir = plugin.getDataFolder().toPath()
			.resolve("backup")
			.resolve(backupStamp)
			.resolve("target")
			.resolve(worldName)
			.resolve(directoryName);
		Files.createDirectories(backupDir);
		Files.copy(target, backupDir.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
	}

	private record CopyResult(int copied, int missing, List<String> errors) {
	}

	private record WorldCopySpec(boolean enabled, String directoryName, String extension) {
	}

	private record MoneyTransferResult(boolean success, String message, double transferredAmount) {
		private static MoneyTransferResult success(double transferredAmount) {
			return new MoneyTransferResult(true, "", transferredAmount);
		}

		private static MoneyTransferResult failed(String message) {
			return new MoneyTransferResult(false, message, 0D);
		}
	}

	private record HuskHomesTransferResult(boolean success, String message, int homesMoved) {
		private static HuskHomesTransferResult success(int homesMoved) {
			return new HuskHomesTransferResult(true, "", homesMoved);
		}

		private static HuskHomesTransferResult failed(String message) {
			return new HuskHomesTransferResult(false, message, 0);
		}
	}

	public record TransferResult(boolean success, String message, int copiedEntries, int missingEntries, boolean kickAfterSuccess, double migratedMoney, int migratedHuskHomesHomes) {
		public static TransferResult success(int copiedEntries, int missingEntries, boolean kickAfterSuccess, double migratedMoney, int migratedHuskHomesHomes) {
			return new TransferResult(true, "", copiedEntries, missingEntries, kickAfterSuccess, migratedMoney, migratedHuskHomesHomes);
		}

		public static TransferResult failed(String message) {
			return new TransferResult(false, message, 0, 0, false, 0D, 0);
		}
	}

	@FunctionalInterface
	public interface ProgressListener {
		void onUpdate(ProgressUpdate update);
	}

	public record ProgressUpdate(String currentTask, int completedTasks, int totalTasks, boolean taskCompleted) {
		public double percent() {
			if (totalTasks <= 0) {
				return 1D;
			}

			double value = (double) completedTasks / (double) totalTasks;
			if (value < 0D) {
				return 0D;
			}
			return Math.min(1D, value);
		}
	}

	private static final class ProgressTracker {
		private final int totalTasks;
		private final ProgressListener listener;
		private int completedTasks;

		private ProgressTracker(int totalTasks, ProgressListener listener) {
			this.totalTasks = totalTasks;
			this.listener = listener;
			this.completedTasks = 0;
		}

		private void start(String task) {
			listener.onUpdate(new ProgressUpdate(task, completedTasks, totalTasks, false));
		}

		private void complete(String task) {
			completedTasks = Math.min(totalTasks, completedTasks + 1);
			listener.onUpdate(new ProgressUpdate(task, completedTasks, totalTasks, true));
		}
	}
}
