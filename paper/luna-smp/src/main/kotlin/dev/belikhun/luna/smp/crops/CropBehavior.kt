package dev.belikhun.luna.smp.crops

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.behavior.BlockBehavior
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import kotlin.random.Random

/**
 * How a crop lives: where it may be planted, how it grows, what picking it
 * gives, and when it dies.
 *
 * One object for the whole roster. Everything that differs between a parsnip
 * and an ancient fruit is in that crop's [CropCatalog.Spec], so this is the
 * rules rather than the data, and adding a crop never comes back here.
 *
 * Growth is driven by the random tick, not by a tile entity. Nova ticks its own
 * blocks at the vanilla rate off `randomTickSpeed`, so a field of these costs
 * exactly what a field of wheat costs and nothing is scheduled per plant; the
 * price is that a crop in an unloaded chunk does not grow, which is what a crop
 * in an unloaded chunk has always done.
 *
 * The Stardew day is translated rather than simulated. A day of a crop's life
 * is [TICKS_PER_DAY] successful random ticks, so the roster keeps the *relative*
 * pace the game published - a four-day parsnip against a twenty-eight-day
 * ancient fruit - at a length that suits a Minecraft evening instead of a
 * Stardew season.
 */
object CropBehavior : BlockBehavior {

	/**
	 * Random ticks one Stardew day costs.
	 *
	 * A block gets a random tick about once a minute at the default
	 * `randomTickSpeed`, which puts a four-day crop at roughly three minutes
	 * and the twenty-eight-day ancient fruit at most of an hour. Vanilla wheat
	 * sits around twenty minutes, so the roster straddles it rather than
	 * undercutting everything the server already grows.
	 */
	private const val TICKS_PER_DAY = 3

	/** The light a crop needs to grow at all, which is vanilla's own number. */
	private const val MIN_LIGHT = 9

	/** How many days one bone meal is worth. */
	private const val BONE_MEAL_DAYS = 2

	// ---- planting ---------------------------------------------------------

	/**
	 * A crop goes on farmland, in its own season, and nowhere else.
	 *
	 * Both refusals tell the player why. A seed that simply does not place is
	 * indistinguishable from a seed that is broken, and the season rule is the
	 * one thing about this roster that a Minecraft player has no reason to
	 * expect.
	 */
	override suspend fun canPlace(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockPlace>): Boolean {
		val spec = specOf(state) ?: return false
		val player = ctx[DefaultContextParamTypes.SOURCE_PLAYER]

		if (pos.below.block.type != Material.FARMLAND) {
			player?.sendActionBar(Component.text("Hạt giống chỉ gieo được trên đất đã cày.", NamedTextColor.RED))

			return false
		}

		if (!Seasons.inSeason(spec, pos.world)) {
			val seasons = spec.seasons.joinToString(", ") { Seasons.label(it) }

			player?.sendActionBar(Component.text("Cây này chỉ trồng được vào mùa $seasons.", NamedTextColor.RED))

			return false
		}

		return true
	}

	// ---- growing ----------------------------------------------------------

	override fun ticksRandomly(state: NovaBlockState): Boolean = true

	/**
	 * One roll of the crop's life.
	 *
	 * Order matters: out of season kills the plant whatever else is true, since
	 * a crop that has already ripened is exactly the one the game withers at
	 * the turn of the season. Only then does anything grow.
	 */
	override fun handleRandomTick(pos: BlockPos, state: NovaBlockState) {
		val spec = specOf(state) ?: return

		if (!Seasons.inSeason(spec, pos.world)) {
			wither(pos)

			return
		}

		// the soil goes first: a crop whose farmland was dug or trampled out
		// from under it pops off rather than hanging in the air
		if (pos.below.block.type != Material.FARMLAND) {
			popOff(pos)

			return
		}

		val age = state[CropCatalog.AGE] ?: 0

		if (age >= spec.maturity) {
			return
		}

		if (pos.block.lightLevel < MIN_LIGHT) {
			return
		}

		if (Random.nextInt(chanceDivisor(pos)) != 0) {
			return
		}

		BlockUtils.updateBlockState(pos, state.with(CropCatalog.AGE, age + 1))
	}

	/**
	 * How many random ticks one day costs here.
	 *
	 * Dry farmland doubles it. That is vanilla's own bargain rather than
	 * Stardew's - the game will not let an unwatered crop grow at all, and a
	 * server where a broken water block quietly stops a farm is a server where
	 * somebody loses a season to a bug they cannot see.
	 */
	private fun chanceDivisor(pos: BlockPos): Int {
		val farmland = pos.below.block.blockData as? Farmland ?: return TICKS_PER_DAY * 2

		return if (farmland.moisture > 0) TICKS_PER_DAY else TICKS_PER_DAY * 2
	}

	/**
	 * Turns the plant into the shared dead crop, keeping the soil it stood on.
	 *
	 * The crop is taken out first rather than placed over: `breakBlock` only
	 * returns what would have dropped and never spawns it, which is exactly the
	 * withering the game does - the season takes the crop, it does not hand it
	 * back as items.
	 */
	private fun wither(pos: BlockPos) {
		BlockUtils.breakBlock(
			Context.intention(BlockBreak)
				.param(DefaultContextParamTypes.BLOCK_POS, pos)
				.param(DefaultContextParamTypes.BLOCK_DROPS, false)
				.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
				.build(),
		)

		BlockUtils.placeBlock(
			Context.intention(BlockPlace)
				.param(DefaultContextParamTypes.BLOCK_POS, pos)
				.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, CropCatalog.DEAD.defaultBlockState)
				.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
				.build(),
		)

		pos.world.spawnParticle(Particle.SMOKE, pos.location.add(0.5, 0.4, 0.5), 4, 0.2, 0.1, 0.2, 0.0)
	}

	/** Breaks the crop as if it had been picked: whatever it holds falls out. */
	private fun popOff(pos: BlockPos) {
		BlockUtils.breakBlockNaturally(
			Context.intention(BlockBreak)
				.param(DefaultContextParamTypes.BLOCK_POS, pos)
				.param(DefaultContextParamTypes.BLOCK_DROPS, true)
				.build(),
		)
	}

	// ---- the soil under it ------------------------------------------------

	/**
	 * The farmland going away takes the crop with it, rather than leaving it at
	 * the next random tick: a plant standing over a hole is the thing every
	 * player notices first.
	 *
	 * A tick later, though, not here. This runs inside the neighbour's own
	 * block change, and breaking a block from inside one that is still in
	 * flight is the re-entrancy the furniture tiles learned to defer; by then
	 * the crop may also be gone already, so the check is made again.
	 */
	override fun handleNeighborChanged(pos: BlockPos, state: NovaBlockState) {
		if (pos.below.block.type == Material.FARMLAND) {
			return
		}

		runTask {
			if (pos.below.block.type == Material.FARMLAND) {
				return@runTask
			}

			if (CropCatalog.BY_BLOCK[pos.novaBlockState?.block?.id?.value()] == null) {
				return@runTask
			}

			popOff(pos)
		}
	}

	// ---- picking ----------------------------------------------------------

	/**
	 * A click on a crop: bone meal if that is what is in hand, a harvest if the
	 * crop is ready, and nothing at all otherwise.
	 *
	 * A ripe crop that regrows is picked in place and wound back to its regrow
	 * day, which is the game's own rule and the reason [CropCatalog.Spec.age]
	 * counts days. A ripe crop that does not regrow is simply broken, so it
	 * goes through [getDrops] with everything else.
	 */
	override fun handleInteract(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockInteract>): Boolean {
		val spec = specOf(state) ?: return false
		val age = state[CropCatalog.AGE] ?: 0
		val held = ctx[DefaultContextParamTypes.INTERACTION_ITEM_STACK]
		val player = ctx[DefaultContextParamTypes.SOURCE_PLAYER]

		if (held != null && held.type == Material.BONE_MEAL) {
			return boneMeal(pos, state, spec, age, held, player)
		}

		if (age < spec.maturity) {
			return false
		}

		if (!spec.regrows) {
			popOff(pos)

			return true
		}

		for (drop in harvest(spec)) {
			pos.world.dropItemNaturally(pos.location.add(0.5, 0.4, 0.5), drop)
		}

		BlockUtils.updateBlockState(pos, state.with(CropCatalog.AGE, spec.maturity - (spec.regrow ?: 0)))
		pos.playSound(Sound.BLOCK_CROP_BREAK, 0.8f, 1.2f)

		return true
	}

	/** Bone meal buys days, and does nothing at all to a crop already ripe. */
	private fun boneMeal(
		pos: BlockPos,
		state: NovaBlockState,
		spec: CropCatalog.Spec,
		age: Int,
		held: ItemStack,
		player: Player?,
	): Boolean {
		if (age >= spec.maturity) {
			return false
		}

		if (!Seasons.inSeason(spec, pos.world)) {
			return false
		}

		val grown = minOf(spec.maturity, age + BONE_MEAL_DAYS)

		BlockUtils.updateBlockState(pos, state.with(CropCatalog.AGE, grown))
		pos.world.spawnParticle(Particle.HAPPY_VILLAGER, pos.location.add(0.5, 0.4, 0.5), 8, 0.3, 0.3, 0.3, 0.0)

		if (player?.gameMode != GameMode.CREATIVE) {
			held.amount -= 1
		}

		return true
	}

	// ---- what it gives ----------------------------------------------------

	/**
	 * What a broken crop leaves behind.
	 *
	 * Under maturity it is the seed back, exactly as pulling up a vanilla
	 * sapling gives the sapling: a mis-click must not cost the packet. Ripe, it
	 * is the harvest, plus a seed for the crops that do not regrow, because
	 * without that the loop only closes through a shop this roster does not yet
	 * have. The game's own answer there is a seed maker, and this is the
	 * deliberate deviation from it.
	 */
	override fun getDrops(pos: BlockPos, state: NovaBlockState, ctx: Context<BlockBreak>): List<ItemStack> {
		val spec = specOf(state) ?: return emptyList()
		val age = state[CropCatalog.AGE] ?: 0
		val seed = CropItems.seedOf(spec.id)

		if (age < spec.maturity) {
			return listOfNotNull(seed?.createItemStack(1))
		}

		val drops = harvest(spec).toMutableList()

		if (!spec.regrows && seed != null) {
			drops += seed.createItemStack(if (Random.nextDouble() < 0.25) 2 else 1)
		}

		return drops
	}

	/** One harvest's worth of produce, with the crop's own chance of one more. */
	private fun harvest(spec: CropCatalog.Spec): List<ItemStack> {
		val produce = CropItems.produceOf(spec.id) ?: return emptyList()
		val amount = spec.count + if (Random.nextDouble() < spec.extra) 1 else 0

		return listOf(produce.createItemStack(amount))
	}

	private fun specOf(state: NovaBlockState): CropCatalog.Spec? =
		CropCatalog.BY_BLOCK[state.block.id.value()]
}
