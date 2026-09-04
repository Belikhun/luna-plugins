package dev.belikhun.luna.smp.signs

import dev.belikhun.luna.smp.LunaSmp
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitStage
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * The items that place the signs.
 *
 * As with the furniture and the flora, nothing here is written per sign: a new
 * sign is an entry in `tools/furniture-gen/signs.ts` and nothing else. This
 * object is also what pulls [SignCatalog] in - a catalog nobody touches is a
 * catalog that never registers its blocks.
 *
 * Each sign shows its own board in the menu. The model is a flat plane and its
 * display transforms turn it face-on rather than into the three-quarter view a
 * cube wants, so what the slot shows is the sign, not a hairline seen on edge.
 */
@Init(stage = InitStage.PRE_PACK)
object SignItems {

	/** The item that places each sign, keyed by block id. */
	val BLOCK_ITEMS: Map<String, NovaItem> = SignCatalog.BLOCKS.mapValues { (_, block) ->
		LunaSmp.item(block) {}
	}
}
