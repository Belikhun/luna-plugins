package dev.belikhun.luna.smp.flora

import dev.belikhun.luna.smp.LunaSmp
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.resources.builder.layout.item.DisplayContext
import xyz.xenondevs.nova.resources.builder.layout.item.SelectItemModelProperty
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The items that place the flora blocks.
 *
 * As with the furniture, nothing here is written per block: a new flower is an
 * entry in the flora catalog and nothing else.
 *
 * A flower whose spec carries an `icon` shows that flat sprite in the menu, on
 * the ground and in an item frame, exactly as vanilla draws its own flower
 * items; the 3D model stays for the hand and the head, where it earns its
 * keep. Everything else (the cubes, the vines, the bushes) shows its model.
 */
@Init(stage = InitStage.PRE_PACK)
object FloraItems {

	/** The item that places each flora block, keyed by block id. */
	val BLOCK_ITEMS: Map<String, NovaItem> = FloraCatalog.BLOCKS.mapValues { (id, block) ->
		val icon = FloraCatalog.BY_ID.getValue(id).icon

		LunaSmp.item(block) {
			if (icon != null) {
				modelDefinition {
					model = select(SelectItemModelProperty.DisplayContext) {
						case[DisplayContext.GUI, DisplayContext.GROUND, DisplayContext.FIXED] = {
							createLayeredModel(icon)
						}

						// The block's own model, named outright.
						//
						// `defaultModel` is NOT it: on an item scope that is
						// the item-sprite convention, `lunasmp:item/<id>`,
						// which a block item has no texture at. Every flower
						// carrying an icon therefore drew the missing-texture
						// cube in hand and on the head - the two places this
						// fallback exists to serve.
						fallback = buildModel { getModel("lunasmp:block/$id") }
					}
				}
			}
		}
	}
}
