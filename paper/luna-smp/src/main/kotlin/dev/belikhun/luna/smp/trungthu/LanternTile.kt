package dev.belikhun.luna.smp.trungthu

import dev.belikhun.luna.smp.LightSource
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.serverTick
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity

/**
 * A lantern set down: always lit, and a toy again when clicked.
 *
 * Its light is a companion light block in the space the lantern opens onto:
 * above one standing on a floor, in front of one hung on a wall, under one
 * hung from a ceiling. That space is not reserved, so the tick puts the
 * light back once a second if something took it and the space cleared.
 *
 * A bare click moves its tune on, the last step switching the music off;
 * a placed lantern starts silent, since a room of them all playing at once
 * is nobody's idea of decoration.
 */
class LanternTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private var song: Int
		get() = retrieveDataOrNull<Int>("song") ?: ToyTunes.OFF
		set(value) = storeData("song", value)

	/** The tune it is playing, or [ToyTunes.OFF]. */
	val currentSong: Int
		get() = song

	/** How many sounds it has played since it woke up; the self-test reads it. */
	var notesPlayed = 0
		private set

	private var started = serverTick

	private val lightBlock: Block
		get() {
			val face = when (blockState[TrungThuCatalog.MOUNT]) {
				TrungThuCatalog.WALL -> blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH
				TrungThuCatalog.CEILING -> BlockFace.DOWN
				else -> BlockFace.UP
			}

			return pos.block.getRelative(face)
		}

	override fun handlePlace(ctx: Context<BlockPlace>) {
		LightSource.place(lightBlock, LEVEL)
	}

	override fun handleEnable() {
		started = serverTick

		// the chunk may still be settling when the tile wakes up
		runTask {
			if (isEnabled) {
				LightSource.place(lightBlock, LEVEL)
			}
		}
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		ToyTunes.stop(song, pos.location.add(0.5, 0.5, 0.5))
		LightSource.clear(lightBlock)
	}

	override fun handleTick() {
		val now = serverTick

		if (now % 20 == 0) {
			LightSource.place(lightBlock, LEVEL)
		}

		val tune = ToyTunes.SONGS.getOrNull(song) ?: return
		notesPlayed += ToyTunes.play(tune, now - started, pos.location.add(0.5, 0.5, 0.5), VOLUME)
	}

	/** Moves the tune on a step, from the top; the step after the last switches it off. */
	fun cycleSong(): Int {
		ToyTunes.stop(song, pos.location.add(0.5, 0.5, 0.5))
		song = ToyTunes.next(song)
		started = serverTick

		// start it now: this tick's handleTick has usually already run, and a
		// track only begins on position 0 of its loop
		ToyTunes.SONGS.getOrNull(song)?.let {
			notesPlayed += ToyTunes.play(it, 0, pos.location.add(0.5, 0.5, 0.5), VOLUME)
		}
		started = serverTick - 1

		return song
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// nova offers the click once per hand; answering both would step twice
		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		// holding a block means building against the lantern, not playing it
		if (player.inventory.itemInMainHand.type.isBlock && !player.inventory.itemInMainHand.type.isAir) {
			return false
		}

		val next = cycleSong()

		pos.world.playSound(pos.location.add(0.5, 0.5, 0.5), "block.lever.click", SoundCategory.BLOCKS, 0.4f, 1.8f)
		player.sendActionBar(Component.text("♪ " + ToyTunes.nameOf(next), TUNE_COLOUR))

		return true
	}

	private companion object {

		/** A lantern on a wall lights like a torch. */
		const val LEVEL = 14

		const val VOLUME = 0.7f

		val TUNE_COLOUR: TextColor = TextColor.color(0xffc94d)
	}
}
