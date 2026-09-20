package dev.belikhun.luna.smp.power

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties

/**
 * The generated power-line table: the three pole parts and the wire ladder.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/poles.ts
 * and re-run the generator; the models, the textures and this table come from
 * the same pass, so editing one of them alone drifts the set.
 *
 * A tier's rate is the matching Logistics cable's own transfer rate turned
 * into joules a second, and its span is how far one length of it may reach.
 * The tier's own colour lives in its texture rather than here: nothing in
 * Kotlin draws a wire, it only says which item a display should wear.
 */
@Suppress("unused")
object PowerCatalog {

	/** What a pole part does. */
	enum class Role {
		BASE, MAST, HEAD,
	}

	data class PartSpec(val id: String, val role: Role)

	data class TierSpec(
		val id: String,
		val en: String,
		val vi: String,
		/** Joules a second one span of it carries. */
		val rate: Long,
		/** How far it may reach, head to head, in blocks. */
		val span: Double,
	)

	/**
	 * How far over its own block a head's terminal sits: the insulator caps
	 * top out at the block's ceiling, so a wire leaves exactly one block up.
	 */
	const val TERMINAL_HEIGHT = 0.72

	val TIERS: List<TierSpec> = listOf(
		TierSpec(id = "basic", en = "Basic Power Line", vi = "Dây Điện Cơ Bản", rate = 10000L, span = 12.0),
		TierSpec(id = "advanced", en = "Advanced Power Line", vi = "Dây Điện Nâng Cao", rate = 40000L, span = 20.0),
		TierSpec(id = "elite", en = "Elite Power Line", vi = "Dây Điện Ưu Việt", rate = 400000L, span = 28.0),
		TierSpec(id = "ultimate", en = "Ultimate Power Line", vi = "Dây Điện Tối Thượng", rate = 2000000L, span = 32.0),
	)

	/** Every wire tier, by the id its spool item carries. */
	val TIER_BY_ID: Map<String, TierSpec> = TIERS.associateBy { it.id }

	private val PARTS = listOf(
		PartSpec(id = "power_pole_base", role = Role.BASE),
		PartSpec(id = "power_pole", role = Role.MAST),
		PartSpec(id = "power_pole_head", role = Role.HEAD),
	)

	/** Every pole part's spec, by block id. */
	val PART_BY_ID: Map<String, PartSpec> = PARTS.associateBy { it.id }

	/** Every pole part's block, by block id. */
	val BLOCKS: Map<String, NovaBlock> = PARTS.associate { it.id to register(it) }

	/** A wire cover, and the cable tier it conducts as. */
	data class CoverSpec(val id: String, val tier: String)

	private val COVERS = listOf(
		CoverSpec(id = "wire_cover_basic", tier = "basic"),
		CoverSpec(id = "wire_cover_advanced", tier = "advanced"),
		CoverSpec(id = "wire_cover_elite", tier = "elite"),
		CoverSpec(id = "wire_cover_ultimate", tier = "ultimate"),
	)

	/** Every wire cover's spec, by block id. */
	val COVER_BY_ID: Map<String, CoverSpec> = COVERS.associateBy { it.id }

	/** Every wire cover's block, by block id. */
	val COVER_BLOCKS: Map<String, NovaBlock> = COVERS.associate { it.id to registerCover(it) }

	/**
	 * The pole parts.
	 *
	 * The base and the mast are plain posts: nothing about them is
	 * directional, because a cast pole looks the same from every side and
	 * their whole job is to conduct upward. The head carries the crossarm, so
	 * it takes the placer's facing and the arm lands across the line.
	 *
	 * All three back onto the line post - a bare wall's centred column, which
	 * is the 8-pixel post the models actually draw. A base or a mast ticks
	 * once a second, because its only work is keeping its bridge
	 * registration honest; the head ticks every tick, because that is the
	 * clock a span is paid out on.
	 */
	private fun register(spec: PartSpec): NovaBlock =
		if (spec.role == Role.HEAD) {
			LunaSmp.tileEntity(spec.id, ::PoleHeadTile) {
				stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)

				// every tick: a tier's rate is joules a SECOND, and a
				// second's worth of the fastest one would not fit in the
				// head's buffer, so the line is paid out a twentieth at a
				// time. The once-a-second housekeeping is counted inside.
				tickrate(20)

				entityBacked(stateSelector = { Hitboxes.linePost() }) {
					defaultModel.rotated()
				}

				behaviors(
					Breakable(hardness = 1.5),
					BlockSounds(SoundGroup.STONE),
					TileEntityDrops,
					TileEntityInteractive,
				)
			}
		} else {
			LunaSmp.tileEntity(spec.id, ::PoleTile) {
				tickrate(1)

				entityBacked(stateSelector = { Hitboxes.linePost() }) {
					defaultModel
				}

				behaviors(
					Breakable(hardness = 1.5),
					BlockSounds(SoundGroup.STONE),
					TileEntityDrops,
				)
			}
		}

	/**
	 * A wire cover: a cable of its tier as a solid block.
	 *
	 * A reserved note block state rather than a display entity, so a covered
	 * run costs the client nothing a stone wall does not, and occludes and
	 * lights like one. It never ticks: its type id is fixed to its tier's
	 * cable, so it has no line to impersonate and no registration to audit.
	 */
	private fun registerCover(spec: CoverSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::WireCoverTile) {
			tickrate(0)

			stateBacked(BackingStateCategory.NOTE_BLOCK) {
				defaultModel
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.METAL),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}
}
