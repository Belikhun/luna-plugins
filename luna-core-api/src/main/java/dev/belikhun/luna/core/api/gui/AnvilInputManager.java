package dev.belikhun.luna.core.api.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AnvilInputManager implements Listener {
	private final JavaPlugin plugin;
	private final Map<UUID, Session> sessions;
	private final Set<UUID> submitting;

	public AnvilInputManager(JavaPlugin plugin) {
		this.plugin = plugin;
		this.sessions = new ConcurrentHashMap<>();
		this.submitting = ConcurrentHashMap.newKeySet();
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void open(Player player, Request request) {
		Inventory inventory = plugin.getServer().createInventory(player, InventoryType.ANVIL, request.title());
		ItemStack input = new ItemStack(request.inputMaterial());
		ItemMeta inputMeta = input.getItemMeta();
		inputMeta.displayName(Component.text(request.initialText()));
		input.setItemMeta(inputMeta);
		inventory.setItem(0, input);

		sessions.put(player.getUniqueId(), new Session(inventory, request, null));
		player.openInventory(inventory);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepare(PrepareAnvilEvent event) {
		if (!(event.getView().getPlayer() instanceof Player player)) {
			return;
		}

		Session session = sessions.get(player.getUniqueId());
		if (session == null || !event.getInventory().equals(session.inventory)) {
			return;
		}

		String value = readInput(event.getView(), event.getResult(), session.inputText, session.request.initialText());
		session.inputText = value;

		ItemStack baseInput = event.getInventory().getItem(0);
		if (baseInput == null || baseInput.getType().isAir()) {
			event.setResult(null);
			return;
		}

		ItemStack prepared = event.getResult();
		if (prepared == null || prepared.getType().isAir()) {
			ItemStack result = baseInput.clone();
			ItemMeta meta = result.getItemMeta();
			meta.displayName(Component.text(value));
			result.setItemMeta(meta);
			event.setResult(result);
		}

		AnvilView anvilView = event.getView();
		anvilView.setRepairCost(0);
		anvilView.setRepairItemCountCost(0);
		anvilView.setMaximumRepairCost(40);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		Session session = sessions.get(playerId);
		if (session == null || !event.getView().getTopInventory().equals(session.inventory)) {
			return;
		}

		event.setCancelled(true);
		if (event.getRawSlot() != 2) {
			return;
		}

		ItemStack outputSlotItem = event.getView().getItem(2);
		String input = readInput(event.getView(), outputSlotItem == null ? event.getCurrentItem() : outputSlotItem, session.inputText, session.request.initialText()).trim();
		if (input.isBlank() && !session.request.allowEmpty()) {
			player.sendMessage(session.request.emptyMessage());
			return;
		}

		sessions.remove(playerId);
		submitting.add(playerId);
		plugin.getServer().getScheduler().runTask(plugin, () -> {
			try {
				session.request.onSubmit().accept(player, input);
			} finally {
				submitting.remove(playerId);
			}
		});
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		Session session = sessions.get(playerId);
		if (session == null || !event.getInventory().equals(session.inventory)) {
			return;
		}

		sessions.remove(playerId);
		if (submitting.contains(playerId)) {
			return;
		}

		session.request.onCloseWithoutSubmit().accept(player);
	}

	private String readInput(InventoryView view, ItemStack result, String trackedValue, String initialValue) {
		if (view instanceof AnvilView anvilView) {
			String renameText = anvilView.getRenameText();
			if (renameText != null) {
				return renameText;
			}

			ItemStack output = anvilView.getTopInventory().getItem(2);
			if (output != null && output.hasItemMeta()) {
				String outputText = plain(output.getItemMeta());
				if (!outputText.isEmpty()) {
					return outputText;
				}
			}
		}

		if (result != null && result.hasItemMeta()) {
			String resultText = plain(result.getItemMeta());
			if (!resultText.isEmpty()) {
				return resultText;
			}
		}

		if (trackedValue != null) {
			return trackedValue;
		}

		return initialValue == null ? "" : initialValue;
	}

	private String plain(ItemMeta meta) {
		if (meta.hasDisplayName()) {
			String legacy = meta.displayName().toString();
			if (legacy != null) {
				return legacy;
			}
		}

		if (meta.hasCustomName() && meta.customName() != null) {
			return plain(meta.customName());
		}

		if (meta.displayName() != null) {
			return plain(meta.displayName());
		}

		return "";
	}

	private String plain(Component component) {
		return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
	}

	public static Request request(Component title, BiConsumer<Player, String> onSubmit, Consumer<Player> onCloseWithoutSubmit) {
		return new Request(title, "", Material.PAPER, false, Component.text("Value cannot be empty"), onSubmit, onCloseWithoutSubmit);
	}

	public record Request(
		Component title,
		String initialText,
		Material inputMaterial,
		boolean allowEmpty,
		Component emptyMessage,
		BiConsumer<Player, String> onSubmit,
		Consumer<Player> onCloseWithoutSubmit
	) {
		public Request {
			if (title == null) {
				title = Component.text("Input");
			}
			if (initialText == null) {
				initialText = "";
			}
			if (inputMaterial == null || inputMaterial.isAir()) {
				inputMaterial = Material.PAPER;
			}
			if (emptyMessage == null) {
				emptyMessage = Component.text("Value cannot be empty");
			}
			if (onSubmit == null) {
				throw new IllegalArgumentException("onSubmit cannot be null");
			}
			if (onCloseWithoutSubmit == null) {
				onCloseWithoutSubmit = player -> {};
			}
		}

		public Request withInitialText(String value) {
			return new Request(title, value, inputMaterial, allowEmpty, emptyMessage, onSubmit, onCloseWithoutSubmit);
		}

		public Request withInputMaterial(Material material) {
			return new Request(title, initialText, material, allowEmpty, emptyMessage, onSubmit, onCloseWithoutSubmit);
		}

		public Request withAllowEmpty(boolean value) {
			return new Request(title, initialText, inputMaterial, value, emptyMessage, onSubmit, onCloseWithoutSubmit);
		}

		public Request withEmptyMessage(Component message) {
			return new Request(title, initialText, inputMaterial, allowEmpty, message, onSubmit, onCloseWithoutSubmit);
		}
	}

	private static final class Session {
		private final Inventory inventory;
		private final Request request;
		private String inputText;

		private Session(Inventory inventory, Request request, String inputText) {
			this.inventory = inventory;
			this.request = request;
			this.inputText = inputText;
		}
	}
}

