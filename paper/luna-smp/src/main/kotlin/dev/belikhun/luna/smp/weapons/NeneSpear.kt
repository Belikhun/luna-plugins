package dev.belikhun.luna.smp.weapons

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack

/**
 * Xuyên Tâm Thương, the collection's spear.
 *
 * This one is deliberately **not** a Nova item, and that is the whole point of
 * it. A Nova item is a shulker shell wearing a model, so whatever numbers it is
 * given it can only ever swing like a sword: the charge attack that runs a line
 * of mobs through, the spear's reach, its own damage type and the Lunge
 * enchantment all live in the vanilla item and cannot be lent to another one.
 *
 * Minecraft 1.21.11 already ships a real spear, so the Nene spear *is*
 * `minecraft:netherite_spear`, wearing our art through the `item_model`
 * component. The model and its two textures ride in the `luna-nene` base pack,
 * since there is no longer a Nova item declaring them.
 *
 * It keeps the vanilla spear sounds rather than the collection's swing
 * recording: a spear that jabs and then makes a sword noise reads as a bug.
 */
object NeneSpear {

	/** The id it is known by in the shop export, matching the Nova items' ids. */
	const val ID = "nene_spear"

	/** The item definition the base pack ships, which is what makes it look like ours. */
	private val MODEL = NamespacedKey("lunasmp", "nene_spear")

	private val STORY = listOf(
		NeneStyle.line("Mũi thương đi trước, lý do đi sau."),
		NeneStyle.line("Khoảng cách là thứ duy nhất nó không cho phép."),
	)

	/**
	 * The spear as it is handed to a player.
	 *
	 * Lunge is the one enchantment that is really the spear's own: it is what
	 * turns a charged jab into a dash, and nothing else in the game can carry
	 * it. The enchantments are looked up inside the function on purpose -
	 * naming one while an object initialises loads the enchantment registry
	 * before it exists and takes the boot down with it.
	 */
	fun stack(): ItemStack {
		val stack = ItemStack(Material.NETHERITE_SPEAR)

		stack.editMeta { meta ->
			meta.setItemModel(MODEL)
			meta.displayName(NeneStyle.name("Xuyên Tâm Thương"))
			meta.lore(NeneStyle.lore(STORY))
			meta.isUnbreakable = true
			meta.setEnchantmentGlintOverride(true)

			meta.addEnchant(Enchantment.SHARPNESS, 9, true)
			meta.addEnchant(Enchantment.LUNGE, 3, true)
			meta.addEnchant(Enchantment.KNOCKBACK, 3, true)
			meta.addEnchant(Enchantment.LOOTING, 4, true)
			meta.addEnchant(Enchantment.UNBREAKING, 10, true)
			meta.addEnchant(Enchantment.MENDING, 1, true)
		}

		return stack
	}
}
