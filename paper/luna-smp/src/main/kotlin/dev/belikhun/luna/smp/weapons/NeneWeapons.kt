package dev.belikhun.luna.smp.weapons

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Enchantable
import xyz.xenondevs.nova.world.item.behavior.Extinguishing
import xyz.xenondevs.nova.world.item.behavior.Flattening
import xyz.xenondevs.nova.world.item.behavior.ItemBehaviorHolder
import xyz.xenondevs.nova.world.item.behavior.Stripping
import xyz.xenondevs.nova.world.item.behavior.Tilling
import xyz.xenondevs.nova.world.item.behavior.Tool
import xyz.xenondevs.nova.world.item.tool.ToolCategory
import xyz.xenondevs.nova.world.item.tool.VanillaToolCategories
import xyz.xenondevs.nova.world.item.tool.VanillaToolTiers

/**
 * The Hoàng đế Nene collection: the end-game weapons, sold in the shop.
 *
 * Registered as real Nova items, so they show up in `/nova items` and carry
 * their own art out of our pack rather than repainting every vanilla tool the
 * way the source pack does. The art is a recolour by dudung (credit:
 * Spryzeen) and the swing is a friend's recording; both were given to us for
 * this server.
 *
 * Every number is passed to the behaviour rather than left to a config file:
 * a tool reads whatever it was not given out of `configs/<id>.yml`, and that
 * file is rewritten by the furniture generator on every run.
 */
@Init(stage = InitStage.PRE_PACK)
object NeneWeapons {

	/** Netherite's own enchantability, so an anvil and a table price them like the real thing. */
	private const val NETHERITE_ENCHANTABILITY = 15

	/**
	 * What each weapon is enchanted with the moment it is handed over.
	 *
	 * Lazy, and that is not a detail: naming an Enchantment constant while
	 * this object initialises loads the enchantment registry, which does not
	 * exist yet at the stage Nova builds its items - the server dies at boot
	 * with "points to a registry that is not available yet". The same trap the
	 * sound constants sprang in FurnitureCatalog.
	 */
	private val ENCHANTS: Map<String, Map<Enchantment, Int>> by lazy {
		mapOf(
			"nene_sword" to mapOf(
				Enchantment.SHARPNESS to 10,
				Enchantment.FIRE_ASPECT to 3,
				Enchantment.LOOTING to 5,
				Enchantment.SWEEPING_EDGE to 5,
				Enchantment.UNBREAKING to 10,
				Enchantment.MENDING to 1,
			),
			"nene_axe" to mapOf(
				Enchantment.SHARPNESS to 12,
				Enchantment.EFFICIENCY to 8,
				Enchantment.LOOTING to 4,
				Enchantment.UNBREAKING to 10,
				Enchantment.MENDING to 1,
			),
			"nene_pickaxe" to mapOf(
				Enchantment.EFFICIENCY to 10,
				Enchantment.FORTUNE to 5,
				Enchantment.UNBREAKING to 10,
				Enchantment.MENDING to 1,
			),
			"nene_shovel" to mapOf(
				Enchantment.EFFICIENCY to 10,
				Enchantment.FORTUNE to 5,
				Enchantment.UNBREAKING to 10,
				Enchantment.MENDING to 1,
			),
			"nene_hoe" to mapOf(
				Enchantment.EFFICIENCY to 10,
				Enchantment.FORTUNE to 5,
				Enchantment.UNBREAKING to 10,
				Enchantment.MENDING to 1,
			),
		)
	}

	/**
	 * One weapon.
	 *
	 * There is deliberately no Damageable: these never wear out. Nothing at
	 * the end of the game should need an anvil.
	 *
	 * `attackDamage` and `attackSpeed` are the numbers the tooltip shows, not
	 * the vanilla modifiers: Nova subtracts the player's own base (1.0 and
	 * 4.0) before writing the attribute. Passing -2.2 the way a vanilla item
	 * does puts -2.2 on the tooltip.
	 */
	private fun weapon(
		id: String,
		name: String,
		category: ToolCategory,
		story: List<Component>,
		breakSpeed: Double,
		attackDamage: Double,
		attackSpeed: Double,
		sweeps: Boolean = false,
		disablesBlocking: Int = 0,
		vararg interactions: ItemBehaviorHolder,
	): NovaItem = LunaSmp.item(id) {
		maxStackSize(1)
		name(NeneStyle.name(name))
		lore(*NeneStyle.lore(story).toTypedArray())

		behaviors(
			Tool(
				VanillaToolTiers.NETHERITE,
				setOf(category),
				breakSpeed,
				attackDamage,
				attackSpeed,
				0,
				sweeps,
				true,
				disablesBlocking,
			),
			// Tool only sets the attributes and the mining speed. Everything a
			// vanilla tool does on right-click is a separate behaviour, and an
			// axe that cannot strip a log is not an axe.
			Enchantable(NETHERITE_ENCHANTABILITY),
			*interactions,
		)

		// the model is authored beside the texture rather than generated from
		// it: these sprites are animation strips, and a layered model built
		// from a 16x144 image is not a thing that works
		modelDefinition {
			model = buildModel { getModel("lunasmp:item/$id") }
		}
	}

	val SWORD = weapon(
		"nene_sword", "Diệt Thần Kiếm", VanillaToolCategories.SWORD,
		listOf(
			NeneStyle.line("Lưỡi kiếm đã từng chạm tới cổ một vị thần."),
			NeneStyle.line("Vết cắt không lành, và thần cũng biết đau."),
		),
		2.0, 14.0, 1.8, sweeps = true,
	)

	val AXE = weapon(
		"nene_axe", "Đó Có Phải Điều Em Mong Muốn...", VanillaToolCategories.AXE,
		listOf(
			NeneStyle.line("Người cầm nó luôn hỏi một câu, và không ai kịp trả lời."),
			NeneStyle.line("Câu hỏi ở lại lâu hơn nhát chém."),
		),
		14.0, 16.0, 1.2, disablesBlocking = 100,
		// strips logs, scrapes oxidised copper, waxes off
		interactions = arrayOf(Stripping),
	)

	val PICKAXE = weapon(
		"nene_pickaxe", "Phá Thạch Chỉ", VanillaToolCategories.PICKAXE,
		listOf(
			NeneStyle.line("Núi mở ra không phải vì sợ, mà vì nể."),
			NeneStyle.line("Mỗi nhát là một lời xin phép rất ngắn."),
		),
		14.0, 9.0, 1.4,
	)

	val SHOVEL = weapon(
		"nene_shovel", "Khai Địa Giả", VanillaToolCategories.SHOVEL,
		listOf(
			NeneStyle.line("Đất nhường đường cho người biết mình đi đâu."),
			NeneStyle.line("Cái hố đầu tiên của mọi công trình."),
		),
		14.0, 8.0, 1.2,
		// dirt to path, and campfires out
		interactions = arrayOf(Flattening, Extinguishing),
	)

	val HOE = weapon(
		"nene_hoe", "Cày Cuốc Vô Tận", VanillaToolCategories.HOE,
		listOf(
			NeneStyle.line("Vũ khí thật sự của người ở lại lâu nhất."),
			NeneStyle.line("Không giết ai, nhưng nuôi cả server.", NeneStyle.EMBER),
		),
		14.0, 6.0, 3.0,
		// dirt to farmland, grass to dirt path's cousin
		interactions = arrayOf(Tilling),
	)

	/** Every weapon, by id, in the order the shop should list them. */
	val ITEMS: Map<String, NovaItem> = linkedMapOf(
		"nene_sword" to SWORD,
		"nene_axe" to AXE,
		"nene_pickaxe" to PICKAXE,
		"nene_shovel" to SHOVEL,
		"nene_hoe" to HOE,
	)

	/**
	 * A weapon as it is handed to a player: the registered item plus the
	 * enchantments that make it end-game. The registry keeps the plain one so
	 * `/nova give` and the creative menu still work.
	 */
	fun stackOf(id: String): ItemStack {
		val stack = ITEMS.getValue(id).createItemStack(1)

		stack.editMeta { meta ->
			meta.isUnbreakable = true
			meta.setEnchantmentGlintOverride(true)

			for ((enchantment, level) in ENCHANTS[id].orEmpty()) {
				meta.addEnchant(enchantment, level, true)
			}
		}

		return stack
	}
}
