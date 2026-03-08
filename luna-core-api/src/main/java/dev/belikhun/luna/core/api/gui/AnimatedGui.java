package dev.belikhun.luna.core.api.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AnimatedGui {
	private final Plugin plugin;
	private final GuiView view;
	private final List<Map<Integer, ItemStack>> frames;
	private final long intervalTicks;
	private BukkitTask task;
	private int frameIndex;

	public AnimatedGui(Plugin plugin, int size, Component title, long intervalTicks) {
		this.plugin = plugin;
		this.view = new GuiView(size, title);
		this.frames = new ArrayList<>();
		this.intervalTicks = Math.max(1, intervalTicks);
		this.frameIndex = 0;
	}

	public GuiView view() {
		return view;
	}

	public AnimatedGui addFrame(Map<Integer, ItemStack> frame) {
		frames.add(frame);
		return this;
	}

	public void start() {
		if (frames.isEmpty()) {
			return;
		}

		stop();
		task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::renderNextFrame, 0L, intervalTicks);
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
	}

	private void renderNextFrame() {
		Map<Integer, ItemStack> frame = frames.get(frameIndex);
		for (Map.Entry<Integer, ItemStack> entry : frame.entrySet()) {
			view.setItem(entry.getKey(), entry.getValue());
		}

		frameIndex = (frameIndex + 1) % frames.size();
	}
}

