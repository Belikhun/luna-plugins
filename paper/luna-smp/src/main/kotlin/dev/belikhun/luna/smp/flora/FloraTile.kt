package dev.belikhun.luna.smp.flora

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.Levelled
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import kotlin.random.Random

/**
 * The flora that has to keep an eye on the world around it.
 *
 * A flower whose whole effect is "whoever steps here" needs nothing per tick and
 * stays a plain block; this is for the two that cannot be answered from a
 * collision - an aura that reaches past its own block, and a bloom that watches
 * the sky. Both are cheap once a second, which is the rate the catalog
 * registers them at.
 */
class FloraTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val spec = FloraCatalog.BY_ID.getValue(blockState.block.id.value())

	private val glowing: Boolean
		get() = blockState[FloraCatalog.GLOWING] == true

	override fun handleBreak(ctx: Context<BlockBreak>) {
		clearLight()
	}

	override fun handleTick() {
		if (spec.nightLight > 0) {
			watchSky()
		}

		when (spec.effect) {
			FloraCatalog.Effect.RALLY -> rally()
			FloraCatalog.Effect.LULL -> lull()
			FloraCatalog.Effect.REPEL -> repel()
			FloraCatalog.Effect.LURE -> lure()
			FloraCatalog.Effect.GLIMMER -> glimmer()
			FloraCatalog.Effect.DREAD -> dread()
			else -> {}
		}
	}

	// ---- the sky ---------------------------------------------------------

	/** Lit between dusk and dawn, and dark the rest of the time. */
	private fun watchSky() {
		// the same window vanilla calls night, read off the clock rather than
		// off a helper, since a world with a fixed time has no dusk to find
		val night = pos.world.time in NIGHT

		if (night == glowing) {
			return
		}

		updateBlockState(blockState.with(FloraCatalog.GLOWING, night))

		if (night) {
			placeLight()
		} else {
			clearLight()
		}
	}

	private fun placeLight() {
		val target = pos.add(0, 1, 0).block

		if (!target.type.isAir) {
			return
		}

		target.type = Material.LIGHT

		val data = target.blockData as Levelled
		data.level = spec.nightLight
		target.blockData = data
	}

	private fun clearLight() {
		val target = pos.add(0, 1, 0).block

		if (target.type == Material.LIGHT) {
			target.type = Material.AIR
		}
	}

	// ---- auras -----------------------------------------------------------

	/** Strength to everyone standing with it, but only while something hunts them. */
	private fun rally() {
		val nearby = around(AURA)
		val players = nearby.filterIsInstance<Player>()

		if (players.isEmpty() || nearby.none { it is Monster }) {
			return
		}

		for (player in players) {
			give(player, PotionEffectType.STRENGTH, 150, 0)
		}
	}

	/** Everything in reach goes slow and weak: the flower calms a fight out of it. */
	private fun lull() {
		for (entity in around(AURA)) {
			if (entity !is LivingEntity) {
				continue
			}

			give(entity, PotionEffectType.SLOWNESS, 150, 0)
			give(entity, PotionEffectType.WEAKNESS, 150, 0)
		}
	}

	/** Hostiles are walked back out of the ring they wandered into. */
	private fun repel() {
		val here = centre()

		for (entity in around(REPEL_RANGE)) {
			if (entity !is Monster) {
				continue
			}

			val away = entity.location.toVector().subtract(here.toVector())

			if (away.lengthSquared() < 0.01) {
				continue
			}

			val target = entity.location.add(away.normalize().multiply(REPEL_RANGE))
			walk(entity, target, 1.25)
		}
	}

	/** Hostiles forget whoever they were chasing and come to the flower instead. */
	private fun lure() {
		val here = centre()

		for (entity in around(LURE_RANGE)) {
			if (entity !is Monster) {
				continue
			}

			entity.target = null
			walk(entity, here, 1.0)
		}
	}

	private fun walk(mob: Mob, to: Location, speed: Double) {
		mob.pathfinder.moveTo(to, speed)
	}

	// ---- ambience --------------------------------------------------------

	private fun glimmer() {
		pos.world.spawnParticle(Particle.END_ROD, centre().add(0.0, 0.2, 0.0), 2, 0.15, 0.1, 0.15, 0.0)
	}

	private fun dread() {
		if (Random.nextInt(DREAD_ODDS) != 0) {
			return
		}

		play(DREAD_SOUNDS.random())
	}

	// ---- shared ----------------------------------------------------------

	private fun around(range: Double) =
		pos.world.getNearbyEntities(centre(), range, range, range)

	private fun centre(): Location = pos.location.add(0.5, 0.5, 0.5)

	private fun give(entity: LivingEntity, type: PotionEffectType, ticks: Int, amplifier: Int) {
		entity.addPotionEffect(PotionEffect(type, ticks, amplifier, true, true))
	}

	private fun play(sound: Sound) {
		pos.world.playSound(centre(), sound, 1.5f, 0.7f)
	}

	private companion object {
		/** One in this many seconds the autumn crocus makes a noise. */
		const val DREAD_ODDS = 25

		const val AURA = 8.0
		const val REPEL_RANGE = 5.0
		const val LURE_RANGE = 16.0

		/** The ticks of a day that count as night. */
		val NIGHT = 13000L..22999L

		val DREAD_SOUNDS = listOf(
			Sound.ENTITY_ZOMBIE_AMBIENT,
			Sound.ENTITY_SKELETON_AMBIENT,
			Sound.ENTITY_ENDERMAN_STARE,
			Sound.ENTITY_CREEPER_PRIMED,
			Sound.AMBIENT_CAVE,
		)
	}
}
