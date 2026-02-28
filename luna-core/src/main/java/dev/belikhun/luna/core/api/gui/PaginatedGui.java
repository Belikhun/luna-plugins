package dev.belikhun.luna.core.api.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;

public final class PaginatedGui<T> {
	private final GuiView view;
	private final List<T> source;
	private final int startSlot;
	private final int maxItems;
	private final BiConsumer<T, Integer> renderer;
	private int currentPage;

	public PaginatedGui(int size, Component title, List<T> source, int startSlot, int maxItems, BiConsumer<T, Integer> renderer) {
		this.view = new GuiView(size, title);
		this.source = source;
		this.startSlot = startSlot;
		this.maxItems = maxItems;
		this.renderer = renderer;
		this.currentPage = 0;
	}

	public GuiView view() {
		return view;
	}

	public void page(int page) {
		this.currentPage = Math.max(0, page);
		render();
	}

	public void next() {
		page(currentPage + 1);
	}

	public void previous() {
		page(Math.max(0, currentPage - 1));
	}

	public void render() {
		clearItems();
		int from = currentPage * maxItems;
		int to = Math.min(source.size(), from + maxItems);
		int slot = startSlot;
		for (int index = from; index < to; index++) {
			renderer.accept(source.get(index), slot++);
		}
	}

	private void clearItems() {
		for (int slot = startSlot; slot < startSlot + maxItems; slot++) {
			view.setItem(slot, new ItemStack(org.bukkit.Material.AIR));
		}
	}
}
