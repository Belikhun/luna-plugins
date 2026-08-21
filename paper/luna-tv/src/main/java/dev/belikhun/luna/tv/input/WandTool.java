package dev.belikhun.luna.tv.input;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;

import dev.belikhun.luna.core.api.ui.LunaUi;

/**
 * The screen-selection wand: left click marks one corner, right click the
 * other, and the selection is outlined in particles while the wand is held.
 *
 * A selection is only a pair of corners; turning it into a screen is the
 * create command's job, which also derives the facing from where the player
 * stands. Corners live per player and die with the session.
 */
public final class WandTool implements Listener {

	/** Dust used to outline the current selection. */
	private static final Particle.DustOptions OUTLINE_DUST =
		new Particle.DustOptions(Color.fromRGB(0xFF, 0xD0, 0x4D), 0.8f);

	/** How often the outline redraws, in ticks. */
	private static final long OUTLINE_PERIOD_TICKS = 10L;

	private final JavaPlugin plugin;
	private final NamespacedKey wandKey;
	private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

	private BukkitTask outlineTask;

	/**
	 * One player's two corners; either may still be missing.
	 *
	 * {@code face} is the block face that was clicked, which is both the side
	 * the screen should appear on and the direction it should look.
	 */
	public record Selection(World world, BlockVector cornerA, BlockVector cornerB, BlockFace face) {

		public boolean complete() {
			return world != null && cornerA != null && cornerB != null;
		}
	}

	public WandTool(JavaPlugin plugin) {
		this.plugin = plugin;
		this.wandKey = new NamespacedKey(plugin, "wand");
	}

	/** Starts the outline redraw loop; call once on enable. */
	public void start() {
		outlineTask = plugin.getServer().getScheduler().runTaskTimer(
			plugin, this::drawOutlines, OUTLINE_PERIOD_TICKS, OUTLINE_PERIOD_TICKS);
	}

	public void stop() {
		if (outlineTask != null) {
			outlineTask.cancel();
			outlineTask = null;
		}

		selections.clear();
	}

	/**
	 * Hands the player the wand item.
	 *
	 * @param player who gets it
	 */
	public void give(Player player) {
		ItemStack wand = new ItemStack(Material.BLAZE_ROD);
		ItemMeta meta = wand.getItemMeta();

		meta.displayName(LunaUi.mini("<gradient:#FFD04D:#FF9D2E><b>Đũa Luna TV</b></gradient>"));
		meta.lore(java.util.List.of(
			LunaUi.mini("<gray>Chuột trái: chọn góc thứ nhất</gray>"),
			LunaUi.mini("<gray>Chuột phải: chọn góc thứ hai</gray>"),
			LunaUi.mini("<gray>Bấm vào MẶT tường muốn hiện hình;</gray>"),
			LunaUi.mini("<gray>màn hình sẽ nằm sát mặt đó, quay ra ngoài.</gray>"),
			LunaUi.mini("<gray>Sau đó:</gray> <white>/lunatv create <tên></white>")));
		meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
		wand.setItemMeta(meta);

		player.getInventory().addItem(wand);
	}

	/**
	 * The player's current selection, if any.
	 *
	 * @param player whose selection
	 * @return the selection, or null when nothing is marked
	 */
	public Selection selection(Player player) {
		return selections.get(player.getUniqueId());
	}

	/** Clears a player's marked corners. */
	public void clear(Player player) {
		selections.remove(player.getUniqueId());
	}

	private boolean holdsWand(Player player) {
		ItemStack held = player.getInventory().getItemInMainHand();

		if (held.getType() != Material.BLAZE_ROD || !held.hasItemMeta()) {
			return false;
		}

		return held.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
	}

	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		Player player = event.getPlayer();
		Block block = event.getClickedBlock();

		if (block == null || !holdsWand(player)) {
			return;
		}

		if (!player.hasPermission(MapClickListener.CONTROL_PERMISSION)) {
			return;
		}

		Action action = event.getAction();
		boolean first = action == Action.LEFT_CLICK_BLOCK;
		boolean second = action == Action.RIGHT_CLICK_BLOCK;

		if (!first && !second) {
			return;
		}

		// the wand never breaks or uses the block it touches
		event.setCancelled(true);

		// The corner is the air block against the face that was clicked, not the
		// block itself. MapEngine puts the display plane on the far edge of the
		// selected column, so selecting the wall itself would bury the screen
		// behind it; selecting the space in front lands the plane exactly flush
		// with the face the player is looking at.
		BlockFace clicked = event.getBlockFace();
		Block front = block.getRelative(clicked);
		BlockVector corner = new BlockVector(front.getX(), front.getY(), front.getZ());
		UUID id = player.getUniqueId();
		Selection current = selections.get(id);

		// a selection is restarted rather than mixed when the world changed
		if (current != null && !block.getWorld().equals(current.world())) {
			current = null;
		}

		Selection next = first
			? new Selection(block.getWorld(), corner, current == null ? null : current.cornerB(), clicked)
			: new Selection(block.getWorld(), current == null ? null : current.cornerA(), corner, clicked);

		selections.put(id, next);

		String which = first ? "thứ nhất" : "thứ hai";

		player.sendRichMessage("<green>✔ Góc " + which + ":</green> <white>"
			+ corner.getBlockX() + " " + corner.getBlockY() + " " + corner.getBlockZ() + "</white>"
			+ (next.complete()
				? " <gray>· đủ hai góc, dùng</gray> <white>/lunatv create <tên></white>"
				: ""));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		selections.remove(event.getPlayer().getUniqueId());
	}

	/**
	 * Derives the facing a new screen should have: the side of the selection's
	 * plane the player is standing on, so a screen always comes up looking at
	 * its creator.
	 *
	 * @param selection the two corners
	 * @param player who is creating
	 * @return the facing, or null when the corners do not span a flat plane
	 */
	public static BlockFace facingToward(Selection selection, Player player) {
		BlockVector cornerA = selection.cornerA();
		BlockVector cornerB = selection.cornerB();

		// the face the player clicked is exactly the way they want it to look;
		// nothing needs to be inferred from where they happen to stand. It is
		// only trusted when the two corners really are flat along its axis,
		// which is not the case if the second click landed on another face.
		BlockFace clicked = selection.face();

		if (clicked != null && flatAlong(clicked, cornerA, cornerB)) {
			return clicked;
		}

		BlockVector a = selection.cornerA();
		BlockVector b = selection.cornerB();
		Location at = player.getLocation();

		boolean flatX = a.getBlockX() == b.getBlockX();
		boolean flatY = a.getBlockY() == b.getBlockY();
		boolean flatZ = a.getBlockZ() == b.getBlockZ();

		if (flatZ && !flatX && !flatY) {
			return at.getZ() < a.getBlockZ() + 0.5 ? BlockFace.NORTH : BlockFace.SOUTH;
		}

		if (flatX && !flatZ && !flatY) {
			return at.getX() < a.getBlockX() + 0.5 ? BlockFace.WEST : BlockFace.EAST;
		}

		if (flatY && !flatX && !flatZ) {
			return at.getY() < a.getBlockY() + 0.5 ? BlockFace.DOWN : BlockFace.UP;
		}

		// a 1x1 column or single block is ambiguous: fall back to the axis the
		// player is looking along, so it still faces them
		if (flatX && flatZ) {
			BlockFace look = player.getFacing();

			return switch (look) {
				case NORTH -> BlockFace.SOUTH;
				case SOUTH -> BlockFace.NORTH;
				case EAST -> BlockFace.WEST;
				case WEST -> BlockFace.EAST;
				default -> BlockFace.SOUTH;
			};
		}

		return null;
	}

	/** Whether the two corners share a plane perpendicular to this face. */
	private static boolean flatAlong(BlockFace face, BlockVector a, BlockVector b) {
		return switch (face) {
			case NORTH, SOUTH -> a.getBlockZ() == b.getBlockZ();
			case EAST, WEST -> a.getBlockX() == b.getBlockX();
			case UP, DOWN -> a.getBlockY() == b.getBlockY();
			default -> false;
		};
	}

	private void drawOutlines() {
		for (Map.Entry<UUID, Selection> entry : selections.entrySet()) {
			Player player = plugin.getServer().getPlayer(entry.getKey());
			Selection selection = entry.getValue();

			if (player == null || !player.isOnline() || !holdsWand(player)) {
				continue;
			}

			if (!player.getWorld().equals(selection.world())) {
				continue;
			}

			drawCorner(player, selection.cornerA());
			drawCorner(player, selection.cornerB());

			if (selection.complete()) {
				drawBox(player, selection);
			}
		}
	}

	private void drawCorner(Player player, BlockVector corner) {
		if (corner == null) {
			return;
		}

		Location center = new Location(player.getWorld(),
			corner.getX() + 0.5, corner.getY() + 0.5, corner.getZ() + 0.5);

		player.spawnParticle(Particle.DUST, center, 6, 0.3, 0.3, 0.3, 0.0, OUTLINE_DUST);
	}

	private void drawBox(Player player, Selection selection) {
		BlockVector a = selection.cornerA();
		BlockVector b = selection.cornerB();

		double minX = Math.min(a.getBlockX(), b.getBlockX());
		double minY = Math.min(a.getBlockY(), b.getBlockY());
		double minZ = Math.min(a.getBlockZ(), b.getBlockZ());
		double maxX = Math.max(a.getBlockX(), b.getBlockX()) + 1;
		double maxY = Math.max(a.getBlockY(), b.getBlockY()) + 1;
		double maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + 1;

		// edges only: one dust point every half block along the twelve edges
		for (double t = 0; t <= 1.0001; t += 0.5 / Math.max(1, maxX - minX)) {
			double x = minX + (maxX - minX) * Math.min(1, t);

			dust(player, x, minY, minZ);
			dust(player, x, maxY, minZ);
			dust(player, x, minY, maxZ);
			dust(player, x, maxY, maxZ);
		}

		for (double t = 0; t <= 1.0001; t += 0.5 / Math.max(1, maxY - minY)) {
			double y = minY + (maxY - minY) * Math.min(1, t);

			dust(player, minX, y, minZ);
			dust(player, maxX, y, minZ);
			dust(player, minX, y, maxZ);
			dust(player, maxX, y, maxZ);
		}

		for (double t = 0; t <= 1.0001; t += 0.5 / Math.max(1, maxZ - minZ)) {
			double z = minZ + (maxZ - minZ) * Math.min(1, t);

			dust(player, minX, minY, z);
			dust(player, maxX, minY, z);
			dust(player, minX, maxY, z);
			dust(player, maxX, maxY, z);
		}
	}

	private void dust(Player player, double x, double y, double z) {
		player.spawnParticle(Particle.DUST,
			new Location(player.getWorld(), x, y, z), 1, 0, 0, 0, 0.0, OUTLINE_DUST);
	}
}
