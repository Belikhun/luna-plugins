package dev.belikhun.luna.smp.furniture

import dev.belikhun.luna.smp.LunaSmp
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.resources.builder.layout.item.DisplayContext
import xyz.xenondevs.nova.resources.builder.layout.item.SelectItemModelProperty
import xyz.xenondevs.nova.world.item.NovaItem
import xyz.xenondevs.nova.world.item.behavior.Equippable

/**
 * The furniture items.
 *
 * Every block in [FurnitureCatalog] gets the item that places it, so nothing
 * here is written per piece: a new piece is a catalog entry and nothing else.
 * The wreath is the exception, because it places no block.
 */
@Init(stage = InitStage.PRE_PACK)
object FurnitureItems {

	/** The item that places each catalog block, keyed by block id. */
	val BLOCK_ITEMS: Map<String, NovaItem> = FurnitureCatalog.BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}

	/**
	 * The wearable flower crowns.
	 *
	 * Like the wreath below, no equipment asset on purpose: a head item without
	 * one renders its own model on the head, and a crown *is* its model. There
	 * is nothing to configure per crown, so this is a loop over the generated
	 * list rather than seven entries.
	 */
	val CROWNS: Map<String, NovaItem> = FurnitureCatalog.CROWNS.associateWith { id ->
		LunaSmp.item(id) {
			maxStackSize(1)
			behaviors(Equippable(null, EquipmentSlot.HEAD))
		}
	}

	/**
	 * The flower wreath. No equipment asset on purpose: a head item without one
	 * renders its own 3D item model on the head, which is the whole point.
	 *
	 * The contexts that show the item small and face-on get the drawn sprite
	 * instead. A wreath is a ring, and those cameras look straight through its
	 * rim: the garland collapses into a horizontal smear with no hole in it.
	 */
	val VONG_HOA = LunaSmp.item("vong_hoa") {
		maxStackSize(1)
		behaviors(Equippable(null, EquipmentSlot.HEAD))

		modelDefinition {
			model = select(SelectItemModelProperty.DisplayContext) {
				case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
					createLayeredModel("lunasmp:item/vong_hoa")
				}

				fallback = buildModel { defaultModel }
			}
		}
	}
}
