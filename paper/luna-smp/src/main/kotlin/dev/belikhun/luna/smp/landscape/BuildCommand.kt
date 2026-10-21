package dev.belikhun.luna.smp.landscape

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.belikhun.luna.smp.furniture.FurniturePot
import dev.belikhun.luna.smp.power.WirelessNodeTile
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.nova.addon.simpleupgrades.registry.UpgradeTypes
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.registry.NovaRegistries
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.BlockStateProperty
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.format.WorldDataManager
import java.io.File
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * `/lunabuild`: places and clears blocks in bulk from a file, for the landscape
 * builds (the heart garden) that are drawn up outside the game and need custom
 * blocks set down by the hundred. `/setblock` and WorldEdit cannot place a
 * Nova block, so the file is walked here and every Nova entry goes through
 * Nova's own placement, which is what gives it its tile entity and model.
 *
 * A build file lives in `plugins/LunaSmp/builds/<name>.json`:
 *
 * ```
 * {"world": "world", "blocks": [
 *   {"x": 1, "y": 2, "z": 3, "id": "lunasmp:petunia"},
 *   {"x": 1, "y": 2, "z": 4, "id": "lunasmp:cherry_chair", "facing": "north"},
 *   {"x": 1, "y": 2, "z": 5, "id": "lunasmp:wireless_power_node", "upgrades": {"range": 10}},
 *   {"x": 1, "y": 2, "z": 6, "id": "minecraft:dirt_path"}
 * ]}
 * ```
 *
 * `facing` sets Nova's FACING property, `props` any other state property by
 * name (int, boolean or facing values), `upgrades` fills a wireless node with
 * Simple Upgrades. A `minecraft:` id is vanilla block data and is set straight
 * into the world without physics. Placement is spread over ticks so a few
 * thousand blocks do not stall the server.
 *
 *   /lunabuild place <name>
 *   /lunabuild clear <x1> <y1> <z1> <x2> <y2> <z2>   removes every Nova block in the box, no drops
 *   /lunabuild count <x1> <y1> <z1> <x2> <y2> <z2>   lists the Nova blocks in the box by id
 */
class BuildCommand : BasicCommand {

	private companion object {
		const val PER_TICK = 120
		const val BUILDS_DIR = "plugins/LunaSmp/builds"
		val LOGGER: Logger = Logger.getLogger("LunaSmp")
	}

	private var running: BukkitTask? = null

	override fun permission(): String = "lunasmp.build"

	override fun execute(source: CommandSourceStack, args: Array<String>) {
		val sender = source.sender

		if (!sender.hasPermission(permission())) {
			reply(sender, "Bạn không có quyền dùng lệnh này.", NamedTextColor.RED)
			return
		}

		when (args.getOrNull(0)) {
			"place" -> place(sender, args.getOrNull(1))
			"clear" -> box(sender, args)?.let { clear(sender, it) }
			"count" -> box(sender, args)?.let { count(sender, it) }
			else -> reply(sender, "/lunabuild place <name> | clear <x1 y1 z1 x2 y2 z2> | count <x1 y1 z1 x2 y2 z2>", NamedTextColor.GRAY)
		}
	}

	override fun suggest(source: CommandSourceStack, args: Array<String>): Collection<String> {
		if (args.size <= 1) {
			return listOf("place", "clear", "count")
		}

		if (args[0] == "place" && args.size == 2) {
			val dir = File(BUILDS_DIR)
			val names = dir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension } ?: emptyList()

			return names.filter { it.startsWith(args[1]) }
		}

		return emptyList()
	}

	// ---- place ----------------------------------------------------------------

	private fun place(sender: CommandSender, name: String?) {
		if (name == null) {
			reply(sender, "Thiếu tên file: /lunabuild place <name>", NamedTextColor.RED)
			return
		}

		if (running != null) {
			reply(sender, "Đang có một build khác chạy, đợi nó xong đã.", NamedTextColor.RED)
			return
		}

		val file = File(BUILDS_DIR, "$name.json")

		if (!file.isFile) {
			reply(sender, "Không thấy $BUILDS_DIR/$name.json", NamedTextColor.RED)
			return
		}

		val root = JsonParser.parseString(file.readText()).asJsonObject
		val world = world(root.get("world")?.asString)

		if (world == null) {
			reply(sender, "Không thấy world trong file build.", NamedTextColor.RED)
			return
		}

		val entries = root.getAsJsonArray("blocks").map { it.asJsonObject }
		var index = 0
		var placed = 0
		var vanilla = 0
		var refused = 0
		val unknown = LinkedHashSet<String>()

		reply(sender, "Đặt ${entries.size} khối từ $name.json ...", NamedTextColor.GRAY)

		running = runTaskTimer(0, 1) {
			val end = min(index + PER_TICK, entries.size)

			while (index < end) {
				val entry = entries[index++]

				when (placeEntry(world, entry, unknown)) {
					Outcome.NOVA -> placed++
					Outcome.VANILLA -> vanilla++
					Outcome.REFUSED -> refused++
				}
			}

			if (index >= entries.size) {
				running?.cancel()
				running = null

				reply(sender, "Xong: $placed khối Nova, $vanilla khối vanilla, $refused bị từ chối.", NamedTextColor.GREEN)

				if (unknown.isNotEmpty()) {
					reply(sender, "Không biết id: ${unknown.joinToString(", ")}", NamedTextColor.YELLOW)
				}
			}
		}
	}

	private enum class Outcome { NOVA, VANILLA, REFUSED }

	private fun placeEntry(world: World, entry: JsonObject, unknown: MutableSet<String>): Outcome {
		val id = entry.get("id").asString
		val x = entry.get("x").asInt
		val y = entry.get("y").asInt
		val z = entry.get("z").asInt

		if (id.startsWith("minecraft:")) {
			val data = try {
				Bukkit.createBlockData(id)
			} catch (error: IllegalArgumentException) {
				unknown += id
				return Outcome.REFUSED
			}

			world.getBlockAt(x, y, z).setBlockData(data, false)
			return Outcome.VANILLA
		}

		if (!NovaRegistries.hasBlock(id)) {
			unknown += id
			return Outcome.REFUSED
		}

		val block = NovaRegistries.getBlockOrThrow(id)
		var state = block.defaultBlockState

		entry.get("facing")?.asString?.let { facing ->
			state = withProperty(state, DefaultBlockStateProperties.FACING, BlockFace.valueOf(facing.uppercase()))
		}

		entry.getAsJsonObject("props")?.entrySet()?.forEach { (name, raw) ->
			state = withNamedProperty(state, name, raw)
		}

		val pos = BlockPos(world, x, y, z)

		// whatever stood there goes first, quietly; a Nova block has to be
		// broken through Nova so its tile entity and model go with it
		if (WorldDataManager.getBlockState(pos) != null) {
			breakQuietly(pos)
		} else if (!world.getBlockAt(x, y, z).type.isAir) {
			world.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR, false)
		}

		val ok = BlockUtils.placeBlock(
			Context.intention(BlockPlace)
				.param(DefaultContextParamTypes.BLOCK_POS, pos)
				.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
				.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
				.build(),
		)

		if (!ok) {
			return Outcome.REFUSED
		}

		// a pot's plants, by item id, filled a tick later for the same reason as the upgrades below
		entry.getAsJsonArray("plants")?.let { plants ->
			val ids = plants.map { it.asString }

			runTaskLater(1) {
				val pot = WorldDataManager.getTileEntity(pos) as? FurniturePot ?: return@runTaskLater
				pot.fill(ids.mapNotNull { stackOf(it) })
			}
		}

		// the tile entity is only there once Nova has finished the placement, so
		// the upgrades go in a tick later; what fitted is logged, since a node
		// that silently kept its base reach once left half a garden dark
		entry.getAsJsonObject("upgrades")?.let { upgrades ->
			runTaskLater(1) {
				val tile = WorldDataManager.getTileEntity(pos) as? WirelessNodeTile

				if (tile == null) {
					LOGGER.warning("[lunabuild] no wireless node at $x $y $z to upgrade")
					return@runTaskLater
				}

				val fitted = ArrayList<String>()
				upgrades.get("range")?.asInt?.let { fitted += "range ${tile.addUpgrades(UpgradeTypes.RANGE, it)}/$it" }
				upgrades.get("energy")?.asInt?.let { fitted += "energy ${tile.addUpgrades(UpgradeTypes.ENERGY, it)}/$it" }
				upgrades.get("efficiency")?.asInt?.let { fitted += "efficiency ${tile.addUpgrades(UpgradeTypes.EFFICIENCY, it)}/$it" }
				LOGGER.info("[lunabuild] node at $x $y $z upgrades: ${fitted.joinToString(", ")}")
			}
		}

		return Outcome.NOVA
	}

	@Suppress("UNCHECKED_CAST")
	private fun <T : Any> withProperty(state: NovaBlockState, property: BlockStateProperty<T>, value: T): NovaBlockState {
		if (state.properties.none { it == property }) {
			return state
		}

		return state.with(property, value)
	}

	/**
	 * Sets a state property by its name (`variant`, `lunasmp:variant`), reading
	 * the value in whatever type the property currently holds.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun withNamedProperty(state: NovaBlockState, name: String, raw: JsonElement): NovaBlockState {
		val property = state.properties.firstOrNull { it.id.value() == name || it.id.toString() == name }
			?: return state

		val current = state[property]

		val value: Any = when (current) {
			is Int -> raw.asInt
			is Boolean -> raw.asBoolean
			is BlockFace -> BlockFace.valueOf(raw.asString.uppercase())
			else -> return state
		}

		return state.with(property as BlockStateProperty<Any>, value)
	}

	// ---- clear / count ----------------------------------------------------------

	private class Box(val world: World, val x1: Int, val y1: Int, val z1: Int, val x2: Int, val y2: Int, val z2: Int)

	private fun box(sender: CommandSender, args: Array<String>): Box? {
		val numbers = args.drop(1).take(6).mapNotNull { it.toIntOrNull() }

		if (numbers.size != 6) {
			reply(sender, "Cần 6 toạ độ: x1 y1 z1 x2 y2 z2", NamedTextColor.RED)
			return null
		}

		val world = (sender as? org.bukkit.entity.Player)?.world ?: Bukkit.getWorlds().first()

		return Box(
			world,
			min(numbers[0], numbers[3]), min(numbers[1], numbers[4]), min(numbers[2], numbers[5]),
			max(numbers[0], numbers[3]), max(numbers[1], numbers[4]), max(numbers[2], numbers[5]),
		)
	}

	private fun novaBlocksIn(box: Box): List<BlockPos> {
		val found = ArrayList<BlockPos>()

		for (y in box.y1..box.y2) {
			for (z in box.z1..box.z2) {
				for (x in box.x1..box.x2) {
					val pos = BlockPos(box.world, x, y, z)

					if (WorldDataManager.getBlockState(pos) != null) {
						found += pos
					}
				}
			}
		}

		return found
	}

	private fun clear(sender: CommandSender, box: Box) {
		if (running != null) {
			reply(sender, "Đang có một build khác chạy, đợi nó xong đã.", NamedTextColor.RED)
			return
		}

		val targets = novaBlocksIn(box)
		var index = 0

		reply(sender, "Dọn ${targets.size} khối Nova ...", NamedTextColor.GRAY)

		running = runTaskTimer(0, 1) {
			val end = min(index + PER_TICK, targets.size)

			while (index < end) {
				breakQuietly(targets[index++])
			}

			if (index >= targets.size) {
				running?.cancel()
				running = null
				reply(sender, "Đã dọn ${targets.size} khối Nova.", NamedTextColor.GREEN)
			}
		}
	}

	private fun count(sender: CommandSender, box: Box) {
		val histogram = LinkedHashMap<String, Int>()

		for (pos in novaBlocksIn(box)) {
			val id = WorldDataManager.getBlockState(pos)?.block?.id?.toString() ?: continue
			histogram[id] = (histogram[id] ?: 0) + 1
		}

		reply(sender, "${histogram.values.sum()} khối Nova trong vùng:", NamedTextColor.GRAY)

		for ((id, n) in histogram.entries.sortedByDescending { it.value }) {
			reply(sender, "  $n × $id", NamedTextColor.GRAY)
		}
	}

	private fun breakQuietly(pos: BlockPos) {
		BlockUtils.breakBlock(
			Context.intention(BlockBreak)
				.param(DefaultContextParamTypes.BLOCK_POS, pos)
				.param(DefaultContextParamTypes.BLOCK_DROPS, false)
				.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
				.build(),
		)
	}

	// ---- helpers ------------------------------------------------------------------

	/** One of an item, by id: a vanilla material, or the item of a Nova block. */
	private fun stackOf(id: String): ItemStack? {
		if (id.startsWith("minecraft:")) {
			val material = Material.matchMaterial(id) ?: return null
			return ItemStack(material)
		}

		if (!NovaRegistries.hasBlock(id)) {
			return null
		}

		return NovaRegistries.getBlockOrThrow(id).item?.createItemStack()
	}

	private fun world(name: String?): World? {
		if (name != null) {
			return Bukkit.getWorld(name)
		}

		return Bukkit.getWorlds().firstOrNull()
	}

	private fun reply(sender: CommandSender, text: String, colour: NamedTextColor) {
		sender.sendMessage(Component.text(text, colour))
	}
}
