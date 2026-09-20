package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.resources.builder.layout.item.DisplayContext
import xyz.xenondevs.nova.resources.builder.layout.item.SelectItemModelProperty
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Consumable
import xyz.xenondevs.nova.world.item.behavior.Equippable

/**
 * The birthday set's items: the cake and the gift box that place their
 * blocks, the plate a slice comes off the cake on, and the small gifts that
 * go into the box. None of these is sold; they are given.
 *
 * This object is also what pulls [BirthdayCatalog] in: a catalog nobody
 * touches registers no blocks at all.
 */
@Init(stage = InitStage.PRE_PACK)
object BirthdayItems {

	private val ROSE = TextColor.color(0xff6fa5)
	private val SOFT = TextColor.color(0xf3c6d8)

	private fun line(text: String, colour: TextColor = SOFT): Component =
		Component.text(text, colour).decoration(TextDecoration.ITALIC, false)

	/**
	 * The cake tower. Its item shows the whole tower rather than the lower
	 * block alone, drawn at half size so both tiers fit the slot.
	 */
	val CAKE: NovaItem = LunaSmp.item(BirthdayCatalog.CAKE) {
		maxStackSize(1)
		lore(
			line("Ba tầng sô-cô-la và dâu tây, nến và pháo bông."),
			line("Chuột phải để thắp nến, chuột phải lần nữa để ước."),
			line("Dành riêng cho bé ne ❤", ROSE),
		)

		modelDefinition {
			model = buildModel { getModel("lunasmp:block/${BirthdayCatalog.CAKE_ID}_full") }
		}
	}

	/** The gift box: keeps what is inside it when picked back up, like a shulker box. */
	val GIFT_BOX: NovaItem = LunaSmp.item(BirthdayCatalog.GIFT_BOX) {
		maxStackSize(1)
		lore(
			line("Đặt xuống và mở ra như một chiếc rương nhỏ."),
			line("Đập lấy lại vẫn giữ nguyên quà bên trong."),
		)
	}

	/** The empty plate a slice is cut onto, and what eating the slice gives back. */
	val CAKE_PLATE: NovaItem = LunaSmp.item("cake_plate") {
		maxStackSize(16)
		lore(
			line("Cầm đĩa chuột phải vào bánh sinh nhật để lấy một miếng."),
		)

		modelDefinition {
			model = buildModel { createLayeredModel("lunasmp:item/cake_plate") }
		}
	}

	/**
	 * A plate with a slice of the cake: eaten from the hand, or set down on
	 * a block with a sneak-click and eaten off the plate there. The plate
	 * comes back either way. One to a slot: a stack of plates is not a thing
	 * anybody carries, and eating off one must never touch the others.
	 */
	val CAKE_SLICE: NovaItem = LunaSmp.item("cake_slice") {
		maxStackSize(1)
		behaviors(
			Consumable(nutrition = 4, saturation = 2.4f, canAlwaysEat = true, consumeTime = 24),
			CakeSliceBehavior,
		)
		lore(
			line("Ăn ngay, hoặc Shift + chuột phải để đặt lên bàn."),
		)

		modelDefinition {
			model = buildModel { getModel("lunasmp:block/${BirthdayCatalog.PLATED_CAKE_ID}") }
		}
	}

	/** A bar of dark chocolate. */
	val CHOCOLATE_BAR: NovaItem = LunaSmp.item("chocolate_bar") {
		maxStackSize(16)
		behaviors(
			Consumable(nutrition = 3, saturation = 1.2f, canAlwaysEat = true, consumeTime = 20),
			ChocolateBehavior.DARK,
		)
		lore(
			line("Đắng một chút, ngọt thật lâu."),
			line("Ăn xong nhanh chân hơn một lúc.", NamedTextColor.GRAY),
		)

		modelDefinition {
			model = buildModel { createLayeredModel("lunasmp:item/chocolate_bar") }
		}
	}

	/** A bar of milk chocolate. */
	val MILK_CHOCOLATE_BAR: NovaItem = LunaSmp.item("milk_chocolate_bar") {
		maxStackSize(16)
		behaviors(
			Consumable(nutrition = 3, saturation = 1.6f, canAlwaysEat = true, consumeTime = 20),
			ChocolateBehavior.MILK,
		)
		lore(
			line("Ngọt và mềm, tan ngay trong miệng."),
			line("Ăn xong hồi máu một lúc.", NamedTextColor.GRAY),
		)

		modelDefinition {
			model = buildModel { createLayeredModel("lunasmp:item/milk_chocolate_bar") }
		}
	}

	/**
	 * The party hat, worn on the head. No equipment asset on purpose, like
	 * the crowns: a head item without one renders its own model on the head.
	 * The slot and the ground get the drawn sprite, since the cone seen from
	 * the slot's camera is mostly its own underside.
	 */
	val BIRTHDAY_HAT: NovaItem = LunaSmp.item("birthday_hat") {
		maxStackSize(1)
		behaviors(Equippable(null, EquipmentSlot.HEAD))
		lore(
			line("Đội lên đầu là thành nhân vật chính của buổi tiệc."),
		)

		modelDefinition {
			model = select(SelectItemModelProperty.DisplayContext) {
				case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
					createLayeredModel("lunasmp:item/birthday_hat")
				}

				fallback = buildModel { getModel("lunasmp:item/birthday_hat") }
			}
		}
	}

	/** The flower ring: a keepsake, held in the hand and kept. */
	val FLOWER_RING: NovaItem = LunaSmp.item("flower_ring") {
		maxStackSize(1)
		lore(
			line("Một chiếc nhẫn vàng với bông hoa nhỏ ở trên."),
			line("Có một không hai, như bé được tặng.", ROSE),
		)

		modelDefinition {
			model = select(SelectItemModelProperty.DisplayContext) {
				case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
					createLayeredModel("lunasmp:item/flower_ring")
				}

				fallback = buildModel { getModel("lunasmp:item/flower_ring") }
			}
		}
	}

	/**
	 * The hand-held sparkler: two items, unlit and burning, swapped in the
	 * hand by a click. The burning one throws sparks off its tip while it is
	 * held and goes out by itself after a minute; a click lights it again.
	 * The slot and the ground get drawn sprites, the hands the 3D wire.
	 */
	private fun sparkler(id: String, lit: Boolean): NovaItem = LunaSmp.item(id) {
		maxStackSize(1)
		behaviors(SparklerBehavior(lit))
		lore(
			line(if (lit) "Đang cháy sáng; giơ lên cao cho mọi người cùng thấy." else "Chuột phải để đốt, cầm trên tay là có pháo bông."),
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

	val SPARKLER: NovaItem = sparkler("sparkler", false)
	val SPARKLER_LIT: NovaItem = sparkler("sparkler_lit", true)

	/** The empty stand the cake is set on. */
	val CAKE_STAND: NovaItem = LunaSmp.item(BirthdayCatalog.CAKE_STAND) {
		lore(
			line("Chỗ dành sẵn cho chiếc bánh. Cầm bánh chuột phải vào đế để đặt lên."),
		)
	}

	/** The party treats, by id: sodas, candies, ice creams and snacks. */
	val TREATS: Map<String, NovaItem> = Treats.ALL.associate { treat ->
		val note = when (treat.kind) {
			Treats.Kind.SODA -> "Một ngụm mát lạnh, có ga."
			Treats.Kind.DRINK -> "Một ly ngọt mát, uống là vui."
			Treats.Kind.CANDY -> "Ngọt lịm, dành cho bé thích đồ ngọt."
			Treats.Kind.ICE_CREAM -> "Lạnh tê răng, ăn nhanh kẻo chảy."
			Treats.Kind.SNACK -> "Đồ ăn vặt cho buổi tiệc."
		}

		val effect = when (treat.id) {
			"soda_redbull" -> "Uống xong nhanh và khỏe hẳn lên một lúc."
			else -> when (treat.kind) {
				Treats.Kind.SODA, Treats.Kind.CANDY -> "Ăn xong nhanh chân hơn một lúc."
				Treats.Kind.DRINK -> if (treat.id == "bubble_tea") "Uống xong nhanh chân hơn một lúc." else "Uống xong hồi máu một lúc."
				Treats.Kind.ICE_CREAM -> "Mát đến mức lửa cũng không nóng một lúc."
				Treats.Kind.SNACK -> null
			}
		}

		val lore = listOfNotNull(line(note), effect?.let { line(it, NamedTextColor.GRAY) })

		treat.id to Treats.register(treat, lore)
	}

	/** The keepsakes: given, kept, never eaten. */
	val GIFTS: Map<String, NovaItem> = linkedMapOf(
		"teddy_bear" to listOf("Mềm, ôm được, và không bao giờ giận."),
		"bouquet" to listOf("Hoa hồng, hoa cúc và hoa hướng dương, gói bằng giấy kraft."),
		"love_letter" to listOf("Viết tay, có một trái tim ở giữa.", "Đọc một mình thôi nhé."),
		"perfume" to listOf("Mùi hoa đào và một chút vani."),
		"heart_balloon" to listOf("Bóng bay hình trái tim, buộc dây cho khỏi bay mất."),
	).mapValues { (id, lines) ->
		LunaSmp.item(id) {
			maxStackSize(1)
			lore(*lines.map { line(it) }.toTypedArray())

			modelDefinition {
				model = buildModel { createLayeredModel("lunasmp:item/$id") }
			}
		}
	}

	/**
	 * The plushie of her: her own skin on a sitting doll, placed on a shelf
	 * or a bed. Squeaks when patted.
	 */
	val PLUSHIE: NovaItem = LunaSmp.item(BirthdayCatalog.PLUSHIE) {
		maxStackSize(1)
		lore(
			line("Một bản nhỏ xíu của bé ne, mềm và ôm được."),
			line("Chuột phải để vỗ nhẹ.", NamedTextColor.GRAY),
		)
	}

	/** Every item of the set, by id. */
	val ITEMS: Map<String, NovaItem> = linkedMapOf(
		BirthdayCatalog.CAKE_ID to CAKE,
		BirthdayCatalog.GIFT_BOX_ID to GIFT_BOX,
		BirthdayCatalog.CAKE_STAND_ID to CAKE_STAND,
		BirthdayCatalog.PLUSHIE_ID to PLUSHIE,
		"cake_plate" to CAKE_PLATE,
		"cake_slice" to CAKE_SLICE,
		"chocolate_bar" to CHOCOLATE_BAR,
		"milk_chocolate_bar" to MILK_CHOCOLATE_BAR,
		"birthday_hat" to BIRTHDAY_HAT,
		"flower_ring" to FLOWER_RING,
		"sparkler" to SPARKLER,
		"sparkler_lit" to SPARKLER_LIT,
	) + TREATS + GIFTS + Balloons.ITEMS
}
