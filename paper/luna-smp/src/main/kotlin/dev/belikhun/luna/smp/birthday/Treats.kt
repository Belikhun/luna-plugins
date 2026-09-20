package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.LunaSmp
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.registry.keys.SoundEventKeys
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Consumable
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior

/**
 * The party treats: soda cans, candies, ice creams and snacks. All flat
 * sprites drawn in tools/furniture-gen/treats.ts, all consumable, none sold.
 *
 * A treat is a table row: what kind it is decides how it is eaten (a can is
 * drunk), how filling it is, and the little effect it leaves. Effects are
 * named only at eating time, never while the items register, because the
 * effect registry is not there yet at that stage.
 */
object Treats {

	enum class Kind {
		SODA, DRINK, CANDY, ICE_CREAM, SNACK,
	}

	data class Treat(
		val id: String,
		val kind: Kind,
		val nutrition: Int,
		val saturation: Float,
		/** What the treat leaves behind, if anything; built when eaten. */
		val effect: (() -> List<PotionEffect>)? = null,
	)

	private val sugarRush: () -> List<PotionEffect> = {
		listOf(PotionEffect(PotionEffectType.SPEED, 20 * 10, 0, false, true, true))
	}

	private val fizz: () -> List<PotionEffect> = {
		listOf(PotionEffect(PotionEffectType.SPEED, 20 * 20, 0, false, true, true))
	}

	/** Red Bull: it gives you wings, or at least a faster pair of legs and arms. */
	private val wings: () -> List<PotionEffect> = {
		listOf(
			PotionEffect(PotionEffectType.SPEED, 20 * 30, 1, false, true, true),
			PotionEffect(PotionEffectType.HASTE, 20 * 30, 0, false, true, true),
		)
	}

	/** Something warm and sweet to sip: a little regeneration. */
	private val comfort: () -> List<PotionEffect> = {
		listOf(PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 0, false, true, true))
	}

	/** Ice cream: cold enough to shrug off fire for a while. */
	private val chill: () -> List<PotionEffect> = {
		listOf(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 15, 0, false, true, true))
	}

	val ALL: List<Treat> = listOf(
		Treat("soda_cola", Kind.SODA, 2, 1.0f, fizz),
		Treat("soda_pepsi", Kind.SODA, 2, 1.0f, fizz),
		Treat("soda_redbull", Kind.SODA, 2, 1.0f, wings),
		Treat("soda_sprite", Kind.SODA, 2, 1.0f, fizz),
		Treat("soda_fanta", Kind.SODA, 2, 1.0f, fizz),
		Treat("lollipop", Kind.CANDY, 1, 0.6f, sugarRush),
		Treat("wrapped_candy", Kind.CANDY, 1, 0.6f, sugarRush),
		Treat("gummy_bear", Kind.CANDY, 1, 0.6f, sugarRush),
		Treat("cotton_candy", Kind.CANDY, 2, 0.8f, sugarRush),
		Treat("ice_cream_cone", Kind.ICE_CREAM, 3, 1.2f, chill),
		Treat("strawberry_ice_cream", Kind.ICE_CREAM, 3, 1.2f, chill),
		Treat("chocolate_ice_cream", Kind.ICE_CREAM, 3, 1.2f, chill),
		Treat("popsicle", Kind.ICE_CREAM, 2, 0.8f, chill),
		Treat("potato_chips", Kind.SNACK, 3, 1.8f),
		Treat("popcorn", Kind.SNACK, 3, 1.8f),
		Treat("french_fries", Kind.SNACK, 5, 3.0f),
		Treat("pizza_slice", Kind.SNACK, 6, 3.6f),
		Treat("donut", Kind.SNACK, 4, 2.4f),
		Treat("cupcake", Kind.SNACK, 4, 2.4f),
		Treat("bubble_tea", Kind.DRINK, 4, 2.0f, sugarRush),
		Treat("mango_smoothie", Kind.DRINK, 3, 1.6f, comfort),
		Treat("chocolate_milk", Kind.DRINK, 3, 1.6f, comfort),
		Treat("macaron", Kind.CANDY, 2, 0.8f, sugarRush),
		Treat("candy_cane", Kind.CANDY, 1, 0.6f, sugarRush),
		Treat("marshmallow", Kind.CANDY, 1, 0.6f, sugarRush),
		Treat("mochi", Kind.CANDY, 2, 0.8f, sugarRush),
		Treat("strawberry", Kind.SNACK, 2, 1.2f),
		Treat("flan", Kind.SNACK, 4, 2.4f),
		Treat("waffle", Kind.SNACK, 5, 3.0f),
	)

	/** Registers one treat as a Nova item with its sprite and its consumable behaviour. */
	fun register(treat: Treat, lore: List<Component>): NovaItem = LunaSmp.item(treat.id) {
		maxStackSize(16)

		val consumable = when (treat.kind) {
			Kind.SODA, Kind.DRINK -> Consumable(
				nutrition = treat.nutrition,
				saturation = treat.saturation,
				canAlwaysEat = true,
				consumeTime = 24,
				animation = ItemUseAnimation.DRINK,
				sound = SoundEventKeys.ENTITY_GENERIC_DRINK,
				particles = false,
			)

			Kind.CANDY -> Consumable(
				nutrition = treat.nutrition,
				saturation = treat.saturation,
				canAlwaysEat = true,
				consumeTime = 14,
			)

			else -> Consumable(
				nutrition = treat.nutrition,
				saturation = treat.saturation,
				canAlwaysEat = true,
				consumeTime = 26,
			)
		}

		val behaviours = if (treat.effect != null) {
			arrayOf(consumable, TreatEffect(treat.effect))
		} else {
			arrayOf(consumable)
		}

		behaviors(*behaviours)
		lore(*lore.toTypedArray())

		modelDefinition {
			model = buildModel { createLayeredModel("lunasmp:item/${treat.id}") }
		}
	}
}

/** The little extra a treat leaves behind, applied when it is finished. */
class TreatEffect(private val effects: () -> List<PotionEffect>) : ItemBehavior {

	override fun handleConsume(player: Player, itemStack: ItemStack, event: PlayerItemConsumeEvent) {
		for (effect in effects()) {
			player.addPotionEffect(effect)
		}
	}
}
