package dev.belikhun.luna.smp.flora

import org.bukkit.Difficulty
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.behavior.BlockBehavior
import xyz.xenondevs.nova.world.block.state.NovaBlockState

/**
 * What a flower does to whoever walks into it.
 *
 * The game calls this every tick an entity's box overlaps the flower, which is
 * what the effects are written for: a status effect is simply refreshed, and
 * damage is already rate-limited by the victim's own invulnerability frames.
 * The blast is the exception and keeps its own cooldown, since nothing throttles
 * an explosion and one per tick would level the garden it stands in.
 */
class StepEffect(private val effect: FloraCatalog.Effect) : BlockBehavior {

	private val blasted = HashMap<BlockPos, Long>()

	override fun handleEntityInside(pos: BlockPos, state: NovaBlockState, entity: Entity) {
		if (entity !is LivingEntity) {
			return
		}

		// the harmful flowers are as inert on peaceful as a cactus is
		if (effect.harmful && pos.world.difficulty == Difficulty.PEACEFUL) {
			return
		}

		when (effect) {
			FloraCatalog.Effect.BURN -> burn(entity)
			FloraCatalog.Effect.BLAST -> blast(pos, entity)
			FloraCatalog.Effect.POISON -> give(entity, PotionEffectType.POISON, 50, 3)
			FloraCatalog.Effect.RUE -> entity.damage(1.0, DamageSource.builder(DamageType.MAGIC).build())
			FloraCatalog.Effect.MEND -> give(entity, PotionEffectType.REGENERATION, 20, 1)
			FloraCatalog.Effect.GUARD -> give(entity, PotionEffectType.RESISTANCE, 150, 0)
			FloraCatalog.Effect.FORTUNE -> give(entity, PotionEffectType.LUCK, 150, 1)
			else -> {}
		}
	}

	/**
	 * Frost walker boots walk over it unburnt, and so does anything already at
	 * home in fire: the damage is dealt as fire rather than out of nowhere, so
	 * vanilla's own immunities answer that second half without being asked.
	 */
	private fun burn(entity: LivingEntity) {
		val boots = entity.equipment?.getItem(EquipmentSlot.FEET)

		if (boots != null && boots.containsEnchantment(Enchantment.FROST_WALKER)) {
			return
		}

		entity.fireTicks = maxOf(entity.fireTicks, 20)
		entity.damage(1.0, DamageSource.builder(DamageType.IN_FIRE).build())
	}

	private fun blast(pos: BlockPos, entity: LivingEntity) {
		val now = pos.world.gameTime
		val last = blasted[pos]

		if (last != null && now - last < BLAST_COOLDOWN) {
			return
		}

		blasted[pos] = now
		pos.world.createExplosion(pos.location.add(0.5, 0.5, 0.5), 1f, false, true, entity)
		entity.damage(3.0)
	}

	private fun give(entity: LivingEntity, type: PotionEffectType, ticks: Int, amplifier: Int) {
		entity.addPotionEffect(PotionEffect(type, ticks, amplifier, true, true))
	}

	private companion object {
		/** Ticks between two blasts from the same flower. */
		const val BLAST_COOLDOWN = 40L
	}
}
