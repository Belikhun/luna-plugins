package dev.belikhun.luna.smp.trungthu

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.registry.NovaRegistries
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.format.WorldDataManager
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import java.io.File
import java.util.logging.Logger

/**
 * Places Nova blocks from a list, for decorating a map from outside the game.
 *
 * Every five seconds it looks for the `.txt` files in `plugins/LunaSmp/decor`. Each line is
 * `<namespace:block> <x> <y> <z> [prop=value,...]` in the world named on a
 * `world <name>` line (default `world`); `#` starts a comment. Properties are
 * matched by name without namespace, so `age=7` picks the state whose age is
 * 7. A file is renamed to `.done` once placed, with a summary in the log, so
 * dropping a new file in runs it once. `remove <x> <y> <z>` breaks the Nova
 * block there without drops. Placement skips the block's own
 * checks and effects: it is for building, not for playing.
 */
@Init(stage = InitStage.POST_WORLD)
object TrungThuDecor {

	private val log: Logger = Logger.getLogger("LunaSmp")

	private val DIR = File("plugins/LunaSmp/decor")

	/** Blocks placed per tick, so a big list does not stall the server. */
	private const val PER_TICK = 400

	private class Job(val file: File, val lines: ArrayDeque<String>) {
		var world: World? = Bukkit.getWorld("world")
		var placed = 0
		var failed = 0
		val unknown = HashMap<String, Int>()
	}

	private var job: Job? = null

	private val stateCache = HashMap<String, NovaBlockState?>()

	@InitFun
	private fun arm() {
		DIR.mkdirs()

		runTaskTimer(100, 1) {
			val current = job

			if (current == null) {
				if (Bukkit.getCurrentTick() % 100 != 0) {
					return@runTaskTimer
				}

				val next = DIR.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.minByOrNull { it.name }
					?: return@runTaskTimer

				job = Job(next, ArrayDeque(next.readLines()))
				log.info("[TrungThu decor] placing ${next.name}")

				return@runTaskTimer
			}

			step(current)
		}
	}

	private fun step(job: Job) {
		var budget = PER_TICK

		while (budget > 0) {
			val line = job.lines.removeFirstOrNull()

			if (line == null) {
				finish(job)

				return
			}

			val text = line.substringBefore('#').trim()

			if (text.isEmpty()) {
				continue
			}

			val parts = text.split(Regex("\\s+"))

			if (parts[0] == "world") {
				job.world = Bukkit.getWorld(parts.getOrElse(1) { "world" })

				continue
			}

			budget--

			try {
				place(job, parts)
			} catch (error: Throwable) {
				job.failed++

				if (job.failed <= 5) {
					log.warning("[TrungThu decor] '$text': $error")
				}
			}
		}
	}

	private fun place(job: Job, parts: List<String>) {
		val world = job.world ?: throw IllegalStateException("no such world")

		if (parts[0] == "remove") {
			val pos = BlockPos(world, parts[1].toInt(), parts[2].toInt(), parts[3].toInt())

			// only ever touch a Nova block: a vanilla block that has since been pasted over this spot stays
			if (WorldDataManager.getBlockState(pos) == null) {
				return
			}
			BlockUtils.breakBlock(
				Context.intention(BlockBreak)
					.param(DefaultContextParamTypes.BLOCK_POS, pos)
					.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
					.param(DefaultContextParamTypes.BLOCK_DROPS, false)
					.build(),
			)

			job.placed++

			return
		}

		val state = state(parts[0], parts.getOrElse(4) { "" })

		if (state == null) {
			job.unknown.merge(parts[0], 1, Int::plus)

			return
		}

		val block = world.getBlockAt(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())

		// whatever vanilla block stands there goes first, so Nova owns the spot
		if (block.type != Material.AIR && block.type != Material.WATER) {
			block.setType(Material.AIR, false)
		}

		val ok = BlockUtils.placeBlock(
			Context.intention(BlockPlace)
				.param(DefaultContextParamTypes.BLOCK_POS, BlockPos(world, block.x, block.y, block.z))
				.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, state)
				.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
				.build(),
		)

		if (ok) {
			job.placed++
		} else {
			job.failed++
		}
	}

	/** The state of [id] whose properties match [spec] (`age=7,facing=north`). */
	private fun state(id: String, spec: String): NovaBlockState? =
		stateCache.getOrPut("$id|$spec") {
			val block: NovaBlock = NovaRegistries.BLOCK.firstOrNull { it.id.asString() == id } ?: return@getOrPut null
			val wanted = spec.split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

			if (wanted.isEmpty()) {
				return@getOrPut block.defaultBlockState
			}

			// every property not asked for keeps its default value, so a crop's
			// hidden constants (max age, budding age) stay what the crop uses
			val def = block.defaultBlockState

			block.blockStates.firstOrNull { st ->
				st.values.all { (prop, v) ->
					val want = wanted[prop.id.value()]

					if (want != null) v.toString().equals(want, true) else v == def.values[prop]
				}
			} ?: block.defaultBlockState
		}

	private fun finish(job: Job) {
		this.job = null
		job.file.renameTo(File(job.file.path.removeSuffix(".txt") + ".done"))
		log.info("[TrungThu decor] ${job.file.name}: placed ${job.placed}, failed ${job.failed}, unknown ${job.unknown}")
	}
}
