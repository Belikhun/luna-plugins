package dev.belikhun.luna.patpat;

import dev.belikhun.luna.core.paper.lifecycle.PaperPluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * LunaPatPat: a library of PatPat animations, who uses which, and the resource pack that makes the
 * PatPat client mod play them. PatPat picks an animation on the client from configs in resource
 * packs, so every change here ends in rebuilding that pack and offering it to players again.
 */
public final class LunaPatPat extends JavaPlugin {
	private AnimationLibrary library;
	private Selections selections;
	private PackBuilder packs;
	private ModrinthImporter modrinth;
	private BukkitTask pendingBuild;
	private PatPatGui gui;

	PatPatGui gui() {
		return gui;
	}

	@Override
	public void onEnable() {
		if (!PaperPluginBootstrap.ensurePluginEnabled(this, "LunaCore", "LunaCore chưa sẵn sàng; LunaPatPat sẽ tắt.")) {
			return;
		}
		saveDefaultConfig();
		library = new AnimationLibrary(getDataFolder().toPath().resolve("animations"));
		selections = new Selections(new File(getDataFolder(), "selections.yml"));
		packs = new PackBuilder(getConfig(), getLogger());
		modrinth = new ModrinthImporter(getConfig().getString("modrinth.user-agent", "luna-patpat"));
		try {
			Files.createDirectories(getDataFolder().toPath().resolve("import"));
			library.load();
		} catch (IOException e) {
			getLogger().severe("could not load the animation library: " + e.getMessage());
		}
		selections.load();

		gui = new PatPatGui(this);
		getServer().getPluginManager().registerEvents(new Menu.Clicks(), this);
		PatAnimCommand command = new PatAnimCommand(this);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, c -> c.registrar().register("patanim", "Chọn hoạt ảnh PatPat", List.of("hoatanh"), command));
		getLogger().info("LunaPatPat: " + library.all().size() + " animations, " + selections.players().size() + " player choices, " + selections.pairs().size() + " pairs.");
	}

	AnimationLibrary library() {
		return library;
	}

	Selections selections() {
		return selections;
	}

	ModrinthImporter modrinth() {
		return modrinth;
	}

	void reloadAll() throws IOException {
		reloadConfig();
		packs = new PackBuilder(getConfig(), getLogger());
		library.load();
		selections.load();
	}

	void saveSelections() {
		try {
			selections.save();
		} catch (IOException e) {
			getLogger().warning("could not save selections: " + e.getMessage());
		}
	}

	/** Rebuilds the pack a few seconds after the last change (changes in a burst make one rebuild). */
	void scheduleRebuild(CommandSender notify) {
		if (pendingBuild != null) {
			pendingBuild.cancel();
		}
		long delay = Math.max(0, getConfig().getInt("rebuild-delay-seconds", 3)) * 20L;
		pendingBuild = Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> rebuild(notify), delay);
	}

	/** Builds the pack now, off the main thread, and tells {@code notify} how it went. */
	void rebuild(CommandSender notify) {
		pendingBuild = null;
		List<String> online;
		try {
			online = Bukkit.getScheduler().callSyncMethod(this, () -> Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList()).get();
		} catch (Exception e) {
			online = List.of();
		}
		try {
			PackBuilder.Result r;
			synchronized (this) {
				r = packs.build(library, selections);
			}
			String after = packs.afterBuild(online);
			getLogger().info("pack rebuilt: " + r.animations() + " animations, " + r.rules() + " rules, " + r.bytes() / 1024 + " KB\n" + after);
			tell(notify, "<green>✔ Đã dựng lại gói PatPat</green> <gray>(" + r.animations() + " hoạt ảnh, " + r.rules() + " quy tắc, " + r.bytes() / 1024 + " KB). Gói mới đang được gửi tới mọi người.</gray>");
		} catch (IOException e) {
			getLogger().warning("pack build failed: " + e.getMessage());
			tell(notify, "<red>❌ Dựng gói thất bại: " + e.getMessage() + "</red>");
		}
	}

	void tell(CommandSender who, String mini) {
		if (who == null) {
			return;
		}
		Bukkit.getScheduler().runTask(this, () -> who.sendMessage(dev.belikhun.luna.core.api.ui.LunaUi.mini(mini)));
	}

	Path importFolder() {
		return getDataFolder().toPath().resolve("import");
	}
}
