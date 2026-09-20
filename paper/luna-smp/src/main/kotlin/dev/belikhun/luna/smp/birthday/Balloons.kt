package dev.belikhun.luna.smp.birthday

import dev.belikhun.luna.smp.LunaSmp
import net.minecraft.world.level.block.Blocks
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.item.NovaItem

/**
 * Balloon bunches: five balloons tied to one point on the floor, three
 * blocks tall, one bunch per colour and a mixed one. Each is a plain block
 * standing on whatever it is placed on; the generator draws every bunch
 * from one table in birthday.ts, and this is the same table.
 *
 * The block model is three blocks tall, which Nova scales for the display
 * entity by itself; the item shows the same bunch at a third, drawn to a
 * separate model, since a slot cannot hold the real one.
 */
object Balloons {

	val COLOURS: List<String> = listOf("pink", "red", "gold", "blue", "purple", "white", "mix")

	/** Every bunch block, by id. The anchor sits in the eight-pixel heavy core. */
	val BLOCKS: Map<String, NovaBlock> = COLOURS.map { colour -> "balloon_bunch_$colour" }
		.associateWith { id ->
			LunaSmp.block(id) {
				entityBacked(stateSelector = { Blocks.HEAVY_CORE.defaultBlockState() })

				behaviors(
					Breakable(hardness = 0.1),
					BlockSounds(SoundGroup.WOOL),
					BlockDrops,
				)
			}
		}

	/** The matching items, wearing the slot-sized model. */
	val ITEMS: Map<String, NovaItem> = BLOCKS.mapValues { (id, block) ->
		LunaSmp.item(block) {
			maxStackSize(16)

			modelDefinition {
				model = buildModel { getModel("lunasmp:block/${id}_item") }
			}
		}
	}
}
