package dev.belikhun.luna.smp.signs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.EquipmentSlot
import org.joml.Vector3f
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.fakeentity.impl.FakeTextDisplay
import xyz.xenondevs.nova.world.format.WorldDataManager
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A sign somebody writes on: a street name plate, a notice board, a blackboard.
 *
 * The words are a text display entity parked a hair in front of the board's own
 * face, not part of the texture - which is the only way a player can put their
 * own words on one. The display is a Nova fake entity, so it exists as packets
 * to whoever is nearby and costs the world no real entity at all.
 *
 * A blackboard also **merges**: lay a solid rectangle of them in the same plane,
 * all facing the same way, and the whole rectangle becomes one board. Exactly
 * one block of it - the corner - carries the display, sized and wrapped to the
 * whole rectangle; the others carry nothing. That is the entire "dynamic size":
 * the board is however large you built it, and the writing area grows with it,
 * while the letters stay the size they always were.
 */
class SignTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	private val spec = SignCatalog.BY_ID.getValue(blockState.block.id.value())
	private val area = spec.text!!

	private var display: FakeTextDisplay? = null

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.NORTH

	private val posted: Boolean
		get() = blockState[SignCatalog.POSTED] == true

	// ---- lifecycle -------------------------------------------------------

	override fun handleEnable() {
		// a board can straddle a chunk border, so the half that loads first
		// sees a smaller rectangle than there really is; nudging the whole
		// board on every enable is what settles it once both halves are in
		nudge(board().members)
	}

	override fun handleDisable() {
		super.handleDisable()
		clear()
	}

	override fun handlePlace(ctx: Context<BlockPlace>) {
		nudge(board().members)
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		// the members are read before the block goes: afterwards this tile is
		// no longer there to find its own board from
		val members = board().members

		clear()
		nudge(members)
	}

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false

		// nova offers the interaction once per hand; acting on both would open
		// the prompt and then immediately answer it with the off-hand call
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		if (hand != EquipmentSlot.HAND) {
			return false
		}

		// a full hand means they meant to place what they are holding
		if (!player.inventory.getItem(hand).type.isAir) {
			return false
		}

		if (player.isSneaking) {
			write(null)
			pos.playSound("block.wool.break", 0.7f, 1.2f)
			SignEditor.wiped(player)

			return true
		}

		SignEditor.toggle(player, this)

		return true
	}

	// ---- the words -------------------------------------------------------

	/**
	 * Writes the board, from any block of it.
	 *
	 * The text lives on the board's corner block, and every other block of the
	 * same board is emptied: after any write there is one place the words are,
	 * whatever shape the board is later rebuilt into.
	 */
	fun write(raw: String?) {
		val board = board()

		for (member in board.members) {
			val tile = tileAt(member) ?: continue

			if (member == board.anchor && raw != null) {
				tile.storeData(TEXT, BoardWords(raw))
			} else {
				tile.removeData(TEXT)
			}
		}

		nudge(board.members)
	}

	/**
	 * What this block itself holds, which is only ever set on a corner.
	 *
	 * Read as [BoardWords], whose serializer also decodes the entries the
	 * broken CBF string writer saved; the guard is for anything older still,
	 * which reads as blank rather than taking the whole board down with it.
	 */
	private fun stored(): String? =
		runCatching { retrieveDataOrNull<BoardWords>(TEXT) }
			.getOrNull()
			?.value
			?.takeIf { it.isNotBlank() }

	/**
	 * What the board shows.
	 *
	 * The corner's own words normally, but a board that has just grown has a
	 * new corner, and the words are still sitting on the old one; rather than
	 * blanking somebody's sign because they widened it, the first block of the
	 * board that has anything wins.
	 */
	private fun textOf(board: Board): String? =
		tileAt(board.anchor)?.stored() ?: board.members.firstNotNullOfOrNull { tileAt(it)?.stored() }

	// ---- rendering -------------------------------------------------------

	private fun refresh() {
		val board = board()

		if (board.anchor != pos) {
			clear()
			return
		}

		val raw = textOf(board)

		if (raw == null) {
			clear()
			return
		}

		val scale = area.width / (area.across * CHAR_WIDTH / TEXT_PIXELS_PER_BLOCK)
		val budget = area.across * CHAR_WIDTH * board.wide
		val lineHeight = LINE_HEIGHT / TEXT_PIXELS_PER_BLOCK * scale
		val room = max(1, floor(board.high * area.height / lineHeight).toInt())
		val lines = wrap(raw, budget).take(room)

		// the anchor of a text display is the bottom of its text, and the text
		// grows upwards from there, so the whole block is centred on the board
		// by dropping the entity half its own height
		val centre = board.centre.clone()
		centre.y -= lines.size * lineHeight / 2

		val component = Component.text()
			.color(TextColor.color(area.colour))
			.append(Component.join(NEWLINE, lines.map { LEGACY.deserialize(it) }))
			.build()

		val existing = display

		if (existing != null) {
			existing.updateEntityData(true) {
				text = component
				lineWidth = budget
			}
			existing.teleport(centre)

			return
		}

		display = FakeTextDisplay(centre) { _, meta ->
			meta.text = component
			meta.lineWidth = budget
			meta.alignment = TextDisplay.TextAlignment.CENTER
			meta.scale = Vector3f(scale.toFloat(), scale.toFloat(), scale.toFloat())
			meta.billboardConstraints = Display.Billboard.FIXED
			meta.hasShadow = area.shadow

			// no plate behind the words: the board is the plate
			meta.defaultBackground = false
			meta.backgroundColor = 0
			meta.isSeeTrough = false

			// chalk on a slate in an unlit room is still chalk on a slate
			meta.brightness = Display.Brightness(15, 15)
		}
	}

	private fun clear() {
		display?.remove()
		display = null
	}

	private fun nudge(members: List<BlockPos>) {
		// a tick later: on a place the block is not in the world yet when this
		// runs, and on a break it still is
		runTask {
			for (member in members) {
				tileAt(member)?.refresh()
			}
		}
	}

	private fun tileAt(at: BlockPos): SignTile? = WorldDataManager.getTileEntity(at) as? SignTile

	// ---- the board this block belongs to ---------------------------------

	/** A rectangle of boards acting as one, measured in blocks. */
	private class Board(
		val members: List<BlockPos>,
		val anchor: BlockPos,
		val wide: Int,
		val high: Int,
		val centre: Location,
	)

	/**
	 * The rectangle this block is part of.
	 *
	 * Boards merge only when they are the same sign, in the same plane, facing
	 * the same way and mounted the same way - and only when the blocks that
	 * touch each other fill their own bounding box exactly. A ragged group is
	 * not a board: it stays as many boards, which is both easier to explain
	 * and the only reading that has one obvious corner.
	 */
	private fun board(): Board {
		if (!area.merges) {
			return single()
		}

		val across = rightOf(facing)
		val steps = listOf(across, across.oppositeFace, BlockFace.UP, BlockFace.DOWN)

		val found = LinkedHashSet<BlockPos>()
		val queue = ArrayDeque<BlockPos>()

		found += pos
		queue += pos

		while (queue.isNotEmpty() && found.size < MAX_MEMBERS) {
			val at = queue.removeFirst()

			for (step in steps) {
				val next = at.add(step.modX, step.modY, step.modZ)

				if (next in found || !joins(next)) {
					continue
				}

				found += next
				queue += next
			}
		}

		var minU = Int.MAX_VALUE
		var maxU = Int.MIN_VALUE
		var minY = Int.MAX_VALUE
		var maxY = Int.MIN_VALUE

		for (member in found) {
			val u = alongOf(member, across)

			minU = min(minU, u)
			maxU = max(maxU, u)
			minY = min(minY, member.y)
			maxY = max(maxY, member.y)
		}

		val wide = maxU - minU + 1
		val high = maxY - minY + 1

		// a group with a hole in it, or an L, is not one board
		if (found.size != wide * high) {
			return single()
		}

		val anchor = found.first { alongOf(it, across) == minU && it.y == minY }
		val far = found.first { alongOf(it, across) == maxU && it.y == maxY }

		return Board(found.toList(), anchor, wide, high, faceOf(anchor, far))
	}

	private fun single(): Board = Board(listOf(pos), pos, 1, 1, faceOf(pos, pos))

	/** Whether the block at a position is part of this same board. */
	private fun joins(at: BlockPos): Boolean {
		val other = tileAt(at) ?: return false

		return other.spec.id == spec.id && other.facing == facing && other.posted == posted
	}

	/**
	 * Where the board's text hangs: the middle of the rectangle, a hair in
	 * front of the face the model paints.
	 */
	private fun faceOf(low: BlockPos, high: BlockPos): Location {
		val depth = (if (posted) SignCatalog.POST_FACE else SignCatalog.WALL_FACE) - CLEARANCE
		val (x, z) = turn(0.5, depth, facing)

		// the two corners span the rectangle; on the facing axis they are the
		// same block, so averaging them there simply gives that block back
		return Location(
			pos.world,
			(min(low.x, high.x) + max(low.x, high.x) + 1) / 2.0 + (x - 0.5),
			(min(low.y, high.y) + max(low.y, high.y) + 1) / 2.0,
			(min(low.z, high.z) + max(low.z, high.z) + 1) / 2.0 + (z - 0.5),
			yawOf(facing),
			0f,
		)
	}

	/** How far along the board's own left-to-right axis a block sits. */
	private fun alongOf(at: BlockPos, across: BlockFace): Int =
		if (across.modX != 0) at.x else at.z

	private companion object {

		/** The key the board's words are stored under, on the corner block. */
		const val TEXT = "text"

		/** How far in front of the face the words float, in blocks. */
		const val CLEARANCE = 0.02

		/**
		 * A text display renders text at forty pixels to the block, with a
		 * six-pixel advance per character and nine pixels between baselines.
		 * Everything the board measures comes off those three numbers.
		 */
		const val TEXT_PIXELS_PER_BLOCK = 40.0
		const val CHAR_WIDTH = 6
		const val LINE_HEIGHT = 9.0

		/** How far a merge is allowed to spread before it stops looking. */
		const val MAX_MEMBERS = 96

		val NEWLINE: Component = Component.newline()

		/** `&` colour codes, the ones a player already knows how to type. */
		val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.builder()
			.character('&')
			.hexColors()
			.build()

		/** The horizontal axis a board runs along, given the way it faces. */
		fun rightOf(facing: BlockFace): BlockFace = when (facing) {
			BlockFace.EAST, BlockFace.WEST -> BlockFace.SOUTH
			else -> BlockFace.EAST
		}

		/**
		 * Turns a point authored for a north-facing model to wherever the block
		 * actually faces, matching the turn Nova applies to the model itself.
		 */
		// Nova's rotated() turns the north-authored model counterclockwise
		// seen from above (WEST is +90), so a point (dx, dz) from the block
		// centre maps to (dz, -dx) for west and (-dz, dx) for east. The
		// first version had east and west swapped; centred sign text hid it,
		// because a mirrored centre is still the centre.
		fun turn(x: Double, z: Double, facing: BlockFace): Pair<Double, Double> = when (facing) {
			BlockFace.WEST -> z to 1.0 - x
			BlockFace.SOUTH -> 1.0 - x to 1.0 - z
			BlockFace.EAST -> 1.0 - z to x
			else -> x to z
		}

		fun yawOf(facing: BlockFace): Float = when (facing) {
			BlockFace.NORTH -> 180f
			BlockFace.EAST -> -90f
			BlockFace.WEST -> 90f
			else -> 0f
		}

		/**
		 * Breaks a written line into the lines the board will show.
		 *
		 * The client would wrap it too, but then the server would not know how
		 * many lines it ended up with - and the line count is what the vertical
		 * centring is worked out from. So the wrap happens here, on the widths
		 * Minecraft's own font uses, and `|` breaks a line wherever the writer
		 * wants one.
		 */
		fun wrap(raw: String, budget: Int): List<String> {
			val out = ArrayList<String>()
			val style = StyleState()

			for (paragraph in raw.split('|', '\n')) {
				val words = paragraph.trim().split(' ').filter { it.isNotEmpty() }

				if (words.isEmpty()) {
					out += ""
					continue
				}

				// each output line opens with the codes still in force, the
				// way every legacy sign reader expects: a colour runs until
				// something changes it, across '|' breaks and soft wraps
				// alike. Without this, a line whose codes repeat the previous
				// line's exactly can arrive without them (a chat pipeline
				// that pre-colours the text collapses the repeats away) and
				// used to fall back to the board's plain default.
				var prefix = style.codes()
				var line = StringBuilder()
				var width = 0

				for (word in words) {
					val before = style.codes()
					val wordWidth = measure(word, style)
					val spacing = if (line.isEmpty()) 0 else CHAR_WIDTH

					if (line.isNotEmpty() && width + spacing + wordWidth > budget) {
						out += prefix + line
						prefix = before
						line = StringBuilder()
						width = 0
					}

					if (line.isNotEmpty()) {
						line.append(' ')
						width += CHAR_WIDTH
					}

					line.append(word)
					width += wordWidth
				}

				out += prefix + line
			}

			return out
		}

		/**
		 * How wide a word is in font pixels. The colour codes in it are never
		 * drawn but do change [style] as they pass, and bold glyphs run one
		 * pixel wider - the width the client actually uses, so the server's
		 * wrap and the client's agree about where a line ends.
		 */
		fun measure(word: String, style: StyleState): Int {
			var total = 0
			var index = 0

			while (index < word.length) {
				val character = word[index]

				if (character == '&' && index + 1 < word.length) {
					// `&#rrggbb` is seven characters past the ampersand, a
					// plain code is one
					val hex = word[index + 1] == '#'

					style.feed(word, index)
					index += if (hex) 8 else 2
					continue
				}

				total += advance(character) + if (style.bold) 1 else 0
				index++
			}

			return total
		}

		/**
		 * The `&` codes currently in force, legacy-fashion: a colour resets
		 * the formats, `&r` resets everything, formats stack.
		 */
		class StyleState {

			private var colour = ""
			private val formats = LinkedHashSet<Char>()

			val bold: Boolean
				get() = 'l' in formats

			/** Applies the code starting at [index] (which points at '&'). */
			fun feed(text: String, index: Int) {
				val code = text.getOrNull(index + 1)?.lowercaseChar() ?: return

				when {
					code == '#' && index + 7 < text.length -> {
						colour = text.substring(index, index + 8)
						formats.clear()
					}

					code in "0123456789abcdef" -> {
						colour = "&" + code
						formats.clear()
					}

					code in "klmno" -> {
						formats += code
					}

					code == 'r' -> {
						colour = ""
						formats.clear()
					}
				}
			}

			fun codes(): String = colour + formats.joinToString("") { "&" + it }
		}

		/** One character's advance in Minecraft's default font, gap included. */
		fun advance(character: Char): Int = when (character) {
			'i', '!', ',', '.', ':', ';', '|', '\'', '¡' -> 2
			'l', '`' -> 3
			' ', 't', 'I', '[', ']' -> 4
			'f', 'k', '(', ')', '{', '}', '<', '>', '"', '*' -> 5
			'@', '~' -> 7
			else -> 6
		}
	}
}
