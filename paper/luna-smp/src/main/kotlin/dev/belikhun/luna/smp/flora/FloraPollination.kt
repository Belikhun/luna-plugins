package dev.belikhun.luna.smp.flora

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.entity.Bee
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.util.nmsEntity
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.NovaBlock
import java.util.EnumSet
import net.minecraft.core.BlockPos as MojangBlockPos
import net.minecraft.world.entity.animal.bee.Bee as MojangBee

/**
 * Bees and our flowers.
 *
 * A bee only ever goes to a block state in `minecraft:bee_attractive`, and a
 * Nova block is never one of those: what actually sits in the world is the
 * backing the catalog picked - a note block, a structure void, a resin clump -
 * so a garden of ours reads to a bee as bare ground. The tag cannot answer
 * this either, because it names block *types*, and those three types carry
 * every other Nova block too, the sofas included.
 *
 * So every bee is handed a second pollinate goal that knows our catalog. It is
 * vanilla's own goal restated, one priority below vanilla's, so a real flower
 * still wins when both are in reach; it holds the bee for at most the same six
 * hundred ticks, and leaves it carrying nectar exactly as a dandelion would.
 */
@Init(stage = InitStage.POST_WORLD)
object FloraPollination : Listener {

	/** Where the goal sits; vanilla's own pollinate goal is 4. */
	private const val GOAL_PRIORITY = 5

	/**
	 * The flowers a bee will come to.
	 *
	 * Everything in the catalog blooms, so everything in it attracts bees -
	 * the harmful ones included, since vanilla's own wither rose is attractive
	 * and lethal at once. The blast flower is the single exception: it is the
	 * one effect nothing else throttles, and inviting bees onto it would crater
	 * the garden it stands in every forty ticks.
	 */
	private val ATTRACTIVE: Set<NovaBlock> = FloraCatalog.BLOCKS
		.filterKeys { FloraCatalog.BY_ID.getValue(it).effect != FloraCatalog.Effect.BLAST }
		.values
		.toSet()

	/** The vanilla blocks those flowers wear, which is what a scan filters on first. */
	private val BACKINGS: Set<Block> = FloraCatalog.BY_ID.values
		.filter { FloraCatalog.BLOCKS.getValue(it.id) in ATTRACTIVE }
		.mapTo(HashSet()) { backingOf(it.backing) }

	@InitFun
	private fun init() {
		val owner = Bukkit.getPluginManager().getPlugin("LunaSmp")

		if (owner == null || !owner.isEnabled) {
			return
		}

		Bukkit.getPluginManager().registerEvents(this, owner)

		// the worlds are already up by this stage, so the bees standing in
		// them before the addon loaded are never announced and have to be
		// collected by hand
		for (world in Bukkit.getWorlds()) {
			for (bee in world.getEntitiesByClass(Bee::class.java)) {
				teach(bee)
			}
		}
	}

	@EventHandler
	private fun onEntityAdded(event: EntityAddToWorldEvent) {
		val bee = event.entity as? Bee ?: return

		teach(bee)
	}

	/**
	 * The block a flower of that shape is really made of.
	 *
	 * This restates what `FloraCatalog.configure` writes, because a registered
	 * block does not carry its backing anywhere an addon can read it. Kept as
	 * an exhaustive `when` on purpose: a new backing stops compiling here
	 * rather than quietly going unpollinated.
	 */
	private fun backingOf(backing: FloraCatalog.Backing): Block = when (backing) {
		FloraCatalog.Backing.CUBE -> Blocks.NOTE_BLOCK
		FloraCatalog.Backing.PLANT -> Blocks.STRUCTURE_VOID
		FloraCatalog.Backing.WALL -> Blocks.RESIN_CLUMP
	}

	/** Gives one bee the goal, unless it is already carrying it. */
	private fun teach(bee: Bee) {
		val handle = bee.nmsEntity as? MojangBee ?: return

		if (handle.goalSelector.availableGoals.any { it.goal is Pollinate }) {
			return
		}

		handle.goalSelector.addGoal(GOAL_PRIORITY, Pollinate(handle))
	}

	/**
	 * Vanilla's pollinate goal, told to look for our flowers instead.
	 *
	 * The shape is `Bee.BeePollinateGoal`'s and the numbers are its numbers:
	 * the same five-block search, the same hover, the same four hundred ticks
	 * of work before the bee has earned its nectar. Two things differ. The
	 * flower test is ours, and the position is held here rather than in the
	 * bee's own `savedFlowerPos`, because vanilla's ValidateFlowerGoal walks
	 * that field every twenty to forty ticks and would throw ours away: as far
	 * as it can tell, there is no flower at that block.
	 */
	private class Pollinate(private val bee: MojangBee) : Goal() {

		/** The flower being worked, in world coordinates. */
		private var flower: MojangBlockPos? = null

		/** The exact point it hovers at, which drifts a little as it works. */
		private var hoverPos: Vec3? = null

		/** Ticks before it looks for a flower again. */
		private var cooldown = 0

		/** Ticks since it set off, whether or not it has arrived. */
		private var elapsed = 0

		/** Ticks spent actually on the flower. */
		private var worked = 0

		private var lastSoundTick = 0

		/** Set when the bee gives up, which is what ends the goal. */
		private var exhausted = false

		/**
		 * Positions it could not fly to, and the tick each may be tried
		 * again at. Vanilla keeps this because a flower behind a wall is
		 * found by the scan every time and costs a path search every time.
		 */
		private var unreachable = HashMap<Long, Long>()

		init {
			setFlags(EnumSet.of(Goal.Flag.MOVE))
		}

		override fun canUse(): Boolean {
			// the cooldown only runs down while the bee is free to move, since
			// that is the only time a goal is asked whether it wants to start -
			// which is also the only time the scan below would be worth running
			if (cooldown > 0) {
				cooldown--
				return false
			}

			if (bee.hasNectar() || bee.level().isRaining) {
				return false
			}

			val found = findFlower()

			if (found == null) {
				cooldown = Mth.nextInt(bee.random, MIN_RETRY, MAX_RETRY)
				return false
			}

			flower = found
			bee.navigation.moveTo(found.x + 0.5, found.y + 0.5, found.z + 0.5, TRAVEL_SPEED)

			return true
		}

		override fun canContinueToUse(): Boolean {
			val target = flower

			if (exhausted || target == null || bee.level().isRaining) {
				return false
			}

			// somebody may have broken it while the bee was on its way
			if (elapsed % RECHECK_INTERVAL == 0 && !isFlower(target)) {
				return false
			}

			return worked <= MIN_WORK || bee.random.nextFloat() < 0.2f
		}

		override fun requiresUpdateEveryTick(): Boolean = true

		override fun start() {
			elapsed = 0
			worked = 0
			lastSoundTick = 0
			exhausted = false

			bee.resetTicksWithoutNectarSinceExitingHive()
		}

		override fun stop() {
			if (worked > MIN_WORK) {
				bee.setHasNectar(true)
			}

			flower = null
			hoverPos = null
			exhausted = false
			cooldown = REST

			bee.navigation.stop()
		}

		override fun tick() {
			val target = flower ?: return

			elapsed++

			if (elapsed > MAX_TICKS) {
				exhausted = true
				return
			}

			val centre = Vec3.atBottomCenterOf(target).add(0.0, HOVER_HEIGHT, 0.0)

			if (centre.distanceTo(bee.position()) > 1.0) {
				hoverPos = centre
				hoverToward()
				return
			}

			val hover = hoverPos ?: centre.also { hoverPos = it }
			var steer = true

			if (bee.position().distanceTo(hover) <= ARRIVAL) {
				// a working bee shifts its footing now and then; the odds are
				// vanilla's, and they are what makes the hovering read as work
				// rather than as a stuck entity
				if (bee.random.nextInt(SHUFFLE_CHANCE) == 0) {
					hoverPos = Vec3(centre.x + drift(), centre.y, centre.z + drift())
					bee.navigation.stop()
				} else {
					steer = false
				}

				bee.lookControl.setLookAt(centre.x, centre.y, centre.z)
			}

			if (steer) {
				hoverToward()
			}

			worked++

			if (bee.random.nextFloat() < 0.05f && worked > lastSoundTick + SOUND_GAP) {
				lastSoundTick = worked
				bee.playSound(SoundEvents.BEE_POLLINATE, 1f, 1f)
			}
		}

		private fun hoverToward() {
			val hover = hoverPos ?: return

			bee.moveControl.setWantedPosition(hover.x, hover.y, hover.z, HOVER_SPEED)
		}

		private fun drift(): Double = ((bee.random.nextFloat() * 2f - 1f) * HOVER_OFFSET).toDouble()

		/**
		 * The nearest flower of ours the bee can actually fly to.
		 *
		 * `withinManhattan` hands out positions nearest first and reuses one
		 * mutable cursor, so the winner is copied before it leaves.
		 */
		private fun findFlower(): MojangBlockPos? {
			val now = bee.level().gameTime
			val kept = HashMap<Long, Long>()

			for (pos in MojangBlockPos.withinManhattan(bee.blockPosition(), RADIUS, RADIUS, RADIUS)) {
				val key = pos.asLong()
				val until = unreachable[key]

				if (until != null && now < until) {
					kept[key] = until
					continue
				}

				if (!isFlower(pos)) {
					continue
				}

				val path = bee.navigation.createPath(pos, 1)

				if (path != null && path.canReach()) {
					return pos.immutable()
				}

				kept[key] = now + UNREACHABLE_MEMORY
			}

			// only what this sweep looked at again is carried over, so the
			// memory cannot grow as the bee travels
			unreachable = kept

			return null
		}

		private fun isFlower(pos: MojangBlockPos): Boolean {
			val level = bee.level()

			if (!level.hasChunkAt(pos)) {
				return false
			}

			// the vanilla state is an array read and the Nova lookup is not, so
			// the backing block is what the scan is allowed to spend its time
			// on: in ordinary terrain nothing at all gets past this line
			if (level.getBlockState(pos).block !in BACKINGS) {
				return false
			}

			val state = BlockPos(level.world, pos.x, pos.y, pos.z).novaBlockState ?: return false

			return state.block in ATTRACTIVE
		}

		private companion object {

			/** Vanilla's own search radius for a flower, in blocks. */
			const val RADIUS = 5

			/** Ticks on the flower before the bee has earned its nectar. */
			const val MIN_WORK = 400

			/** Ticks before it gives the flower up, arrived or not. */
			const val MAX_TICKS = 600

			/** Ticks of rest after a visit, and the window it waits after a fruitless look. */
			const val REST = 200
			const val MIN_RETRY = 20
			const val MAX_RETRY = 60

			/** How close to the hover point counts as standing on it. */
			const val ARRIVAL = 0.1

			/** One in this many ticks it shifts its footing while working. */
			const val SHUFFLE_CHANCE = 25

			/** How far above the flower's floor it hovers, and how far it drifts. */
			const val HOVER_HEIGHT = 0.6
			const val HOVER_OFFSET = 0.33333334f

			const val TRAVEL_SPEED = 1.2
			const val HOVER_SPEED = 0.35

			/** Ticks between two pollinating sounds. */
			const val SOUND_GAP = 60

			/** Ticks between two checks that the flower is still there. */
			const val RECHECK_INTERVAL = 20

			/** Ticks a position stays remembered as one the bee could not reach. */
			const val UNREACHABLE_MEMORY = 600L
		}
	}
}
