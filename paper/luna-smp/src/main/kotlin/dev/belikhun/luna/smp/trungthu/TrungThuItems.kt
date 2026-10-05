package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LunaSmp
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.registry.keys.SoundEventKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.resources.builder.layout.item.DisplayContext
import xyz.xenondevs.nova.resources.builder.layout.item.SelectItemModelProperty
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Consumable
import xyz.xenondevs.nova.world.item.behavior.Equippable
import xyz.xenondevs.nova.world.item.behavior.ItemBehavior
import xyz.xenondevs.nova.world.item.behavior.ItemBehaviorHolder

/**
 * The Mid-Autumn event items (the mooncakes are in [MooncakeItems]): the lanterns that place their blocks
 * and play in the hand, the masks, the drum, the pomelo dog, the fruit and
 * the tea. Everything here is crafted (the recipes ship in the addon's
 * resources) and sold in the shop, save the fruit, which only the shop has.
 *
 * This object is also what pulls [TrungThuCatalog] in: a catalog nobody
 * touches registers no blocks at all.
 */
@Init(stage = InitStage.PRE_PACK)
object TrungThuItems {

	private val GOLDEN = TextColor.color(0xffc94d)
	private val SOFT = TextColor.color(0xf3dcb0)

	init {
		// the tunes are parsed here rather than the first time a child picks up
		// a lantern, so a slip in the notation fails a test boot, not a player
		check(ToyTunes.SONGS.size == TrungThuTable.SONG_COUNT) {
			"ToyTunes has ${ToyTunes.SONGS.size} tunes, the lantern table expects ${TrungThuTable.SONG_COUNT}"
		}
	}

	private fun line(text: String, colour: TextColor = SOFT): Component =
		Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

	private fun hint(text: String): Component =
		Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)

	/**
	 * The lanterns. The slot, the ground and an item frame show the lantern
	 * alone; the hands carry it hanging off its toy handle.
	 */
	val LANTERNS: Map<String, NovaItem> = TrungThuTable.LANTERNS.associate { lantern ->
		val block = TrungThuCatalog.LANTERNS.getValue(lantern.id)

		lantern.id to LunaSmp.item(block) {
			maxStackSize(16)
			behaviors(LanternBehavior(lantern))
			lore(
				line(lantern.lore),
				line("Cầm trên tay: đèn sáng và phát nhạc cho cả xóm nghe.", GOLDEN),
				hint("Chuột phải lên trời để đổi bài hoặc tắt nhạc."),
				hint("Đặt lên sàn, treo lên tường hoặc dưới trần."),
			)

			modelDefinition {
				model = select(SelectItemModelProperty.DisplayContext) {
					case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
						getModel("lunasmp:block/${lantern.id}")
					}

					fallback = buildModel { getModel("lunasmp:item/${lantern.id}_held") }
				}
			}
		}
	}

	/** Every lantern item, for telling one apart in a hand. */
	val LANTERN_ITEMS: Set<NovaItem> = LANTERNS.values.toSet()

	/**
	 * The masks, worn on the head. No equipment asset, like the party hat: a
	 * head item without one wears its own model.
	 */
	val MASKS: Map<String, NovaItem> = TrungThuTable.MASKS.associate { mask ->
		mask.id to LunaSmp.item(mask.id) {
			maxStackSize(1)
			behaviors(Equippable(null, EquipmentSlot.HEAD))
			lore(line(mask.lore), hint("Đội lên đầu như mũ."))

			modelDefinition {
				model = buildModel { getModel("lunasmp:item/${mask.id}") }
			}
		}
	}

	val LION_DRUM: NovaItem = LunaSmp.item(TrungThuCatalog.LION_DRUM) {
		maxStackSize(16)
		lore(line("Trống đỏ đinh đồng, đánh là lân múa."), hint("Chuột phải để đánh một hồi, lần nữa để dừng."))
	}

	val POMELO_DOG: NovaItem = LunaSmp.item(TrungThuCatalog.POMELO_DOG) {
		maxStackSize(16)
		lore(line("Múi bưởi làm thân, vỏ bưởi làm tai, đinh hương làm mắt."), hint("Chuột phải để vuốt ve."))
	}

	val MAM_CO: NovaItem = LunaSmp.item(TrungThuCatalog.MAM_CO) {
		maxStackSize(16)
		lore(line("Bưởi, bánh nướng, hồng, chuối, nho: đủ cả mâm cỗ phá cỗ."), hint("Đặt lên bàn để trưng."))
	}

	val TO_HE: NovaItem = LunaSmp.item(TrungThuCatalog.TO_HE) {
		maxStackSize(16)
		lore(line("Tò he bột nếp đủ màu, cắm trên cây rơm."), hint("Đặt xuống như một gánh hàng rong."))
	}

	/** The fruit and the tea: each eaten or drunk the way the real one is. */
	val FRUITS: Map<String, NovaItem> = TrungThuTable.FRUITS.associate { fruit ->
		val consumable = when (fruit.id) {
			"coconut" -> Consumable(
				nutrition = 3,
				saturation = 2.0f,
				consumeTime = 32,
				animation = ItemUseAnimation.DRINK,
				sound = SoundEventKeys.ENTITY_GENERIC_DRINK,
				particles = false,
			)

			"tra_nong" -> Consumable(
				nutrition = 1,
				saturation = 1.0f,
				canAlwaysEat = true,
				consumeTime = 32,
				animation = ItemUseAnimation.DRINK,
				sound = SoundEventKeys.ENTITY_GENERIC_DRINK,
				particles = false,
			)

			"durian" -> Consumable(nutrition = 5, saturation = 4.0f, consumeTime = 40)
			else -> Consumable(nutrition = 4, saturation = 2.4f, consumeTime = 32)
		}

		val behaviours: Array<ItemBehaviorHolder> = if (fruit.id == "tra_nong") arrayOf(consumable, TeaBehavior) else arrayOf(consumable)

		fruit.id to LunaSmp.item(fruit.id) {
			maxStackSize(if (fruit.id == "tra_nong") 1 else 16)
			behaviors(*behaviours)
			lore(line(fruit.lore))

			modelDefinition {
				model = buildModel { createLayeredModel("lunasmp:item/${fruit.id}") }
			}
		}
	}

	/**
	 * The light-up swords, by id: the switched-off item and the lit one. The
	 * slot, the ground and a frame show the drawn sprite (the lit one animates
	 * with its blade); the hands hold the 3D sword.
	 */
	val SWORDS: Map<String, Pair<NovaItem, NovaItem>> = TrungThuTable.SWORDS.associate { sword ->
		fun register(lit: Boolean): NovaItem {
			val id = if (lit) "${sword.id}_on" else sword.id

			return LunaSmp.item(id) {
				maxStackSize(1)
				behaviors(SwordBehavior(sword.id, lit))
				lore(
					line(sword.lore),
					hint(if (lit) "Chuột phải để tắt; vung là kêu vù vù." else "Chuột phải để bật đèn."),
					hint("Kiếm nhựa: đánh chỉ đau một chút."),
				)

				modelDefinition {
					model = select(SelectItemModelProperty.DisplayContext) {
						case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
							createLayeredModel("lunasmp:item/$id")
						}

						fallback = buildModel { getModel("lunasmp:item/$id") }
					}
				}
			}
		}

		sword.id to (register(false) to register(true))
	}

	/** Every item of the set, by id. */
	val ITEMS: Map<String, NovaItem> = MooncakeItems.MOONCAKES + LANTERNS + MASKS + FRUITS + mapOf(
		"lion_drum" to LION_DRUM,
		"pomelo_dog" to POMELO_DOG,
		"mam_co" to MAM_CO,
		"to_he" to TO_HE,
	) + SWORDS.flatMap { (id, pair) -> listOf(id to pair.first, "${id}_on" to pair.second) }
}

/** A cup of hot tea warms and mends a little, and the bowl comes back. */
object TeaBehavior : ItemBehavior {

	override fun handleConsume(player: Player, itemStack: ItemStack, event: PlayerItemConsumeEvent) {
		player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 0, false, true, true))

		// into the inventory, never as the event's replacement: a replacement
		// stands in for the whole stack in hand
		for (stack in player.inventory.addItem(ItemStack(Material.BOWL)).values) {
			player.world.dropItemNaturally(player.location, stack)
		}
	}
}
