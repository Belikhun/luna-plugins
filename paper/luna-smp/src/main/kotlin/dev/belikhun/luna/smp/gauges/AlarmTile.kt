package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.LightSource
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform
import org.bukkit.entity.Player
import org.joml.Quaternionf
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.invui.Click
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.AbstractItem
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.item.ItemProvider
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.block.tileentity.menu.TileEntityMenuClass
import xyz.xenondevs.nova.world.block.tileentity.network.NetworkManager
import xyz.xenondevs.nova.world.block.tileentity.network.node.NetworkNode
import xyz.xenondevs.nova.world.block.tileentity.network.type.DefaultNetworkTypes
import xyz.xenondevs.nova.world.block.tileentity.network.type.NetworkType
import xyz.xenondevs.nova.world.block.tileentity.network.type.energy.EnergyBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.fluid.FluidBridge
import xyz.xenondevs.nova.world.block.tileentity.network.type.item.ItemBridge
import xyz.xenondevs.nova.world.fakeentity.impl.FakeItemDisplay
import xyz.xenondevs.nova.world.format.NetworkState
import xyz.xenondevs.nova.world.model.FixedMultiModel
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.inventory.ClickType

/**
 * The alarm beacon: a red dome that sweeps and wails while its condition
 * holds.
 *
 * The condition is the operator's own: a quantity (stored energy, tank level
 * or item fill, read from the networks touching the beacon exactly the way a
 * gauge reads them), a direction, and a threshold percent. Below 15% power,
 * above 90% warehouse fill - whatever the base needs shouted about. While it
 * holds, a beam display spins over the dome and a two-tone horn sounds; the
 * moment it stops holding, the beacon goes quiet and dark.
 *
 * Right-click opens the panel where the condition is set. The settings ride
 * in the tile data, so a beacon keeps its orders across restarts.
 */
class AlarmTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data), EnergyBridge, ItemBridge, FluidBridge {

	/**
	 * The full-block form: a housing cube with the dome riding the face the
	 * placer clicked, and a base that genuinely glows while the alarm holds
	 * (a companion light block thrown the way the dome points).
	 */
	private val cube = blockState.block.id.value() == "alarm_light_block"

	private val facing: BlockFace
		get() = blockState[DefaultBlockStateProperties.FACING] ?: BlockFace.UP

	/** The block the dome floodlights: the one it points into. */
	private val lightTarget: Block
		get() = pos.advance(facing, 1).block

	/**
	 * Keeps the floodlight in step with the glowing base. Idempotent, and
	 * called every tick, so a light something built over comes back.
	 */
	private fun syncLight() {
		if (cube && blockState[GaugeCatalog.ON] == true) {
			LightSource.place(lightTarget, LIGHT_LEVEL)
		} else {
			LightSource.clear(lightTarget)
		}
	}

	// ---- the wire side -------------------------------------------------------
	//
	// Both forms are transparent pieces of the cable line they sit in, like
	// the gauges: real bridges wearing the neighbouring line's own type id,
	// so a cable visibly connects to the beacon and the run passes through.
	// The beacon then reads the very line it is spliced into.

	private var valid = false

	override val isValid: Boolean
		get() = valid

	override var typeId: Key = Wiring.ownId(blockState.block.id.value())
		private set

	override val linkedNodes: Set<NetworkNode>
		get() = emptySet()

	override val energyTransferRate: Long
		get() = 1_000_000_000L

	override val itemTransferRate: Int
		get() = 1_024

	override val fluidTransferRate: Long
		get() = 100_000_000L

	/** Every face but the dome's own. */
	private fun wireFaces(): Set<BlockFace> = FACES.toSet() - facing

	private var nextAudit = 0L
	private var auditStrikes = 0

	/** The couplings grown toward connected wires; small form only. */
	private val joints = FixedMultiModel()

	private var metric = 0
	private var above = false
	private var threshold = 15
	private var horn = true

	/** The load trigger's threshold, in J/s, walked along the 1-2-5 ladder. */
	private var loadThreshold = 1_000L

	private var active = false
	private var lastReading: String? = null

	/** Whether the last second's draw was met; a dead siren judges nothing. */
	private var powered = true

	private var beam: FakeItemDisplay? = null
	private var beamAngle = 0.0
	private var tickCount = 0
	private var sirenClock = 0

	override fun handleEnable() {
		super.handleEnable()

		metric = retrieveDataOrNull<Int>(METRIC) ?: 0
		above = retrieveDataOrNull<Boolean>(ABOVE) ?: false
		threshold = retrieveDataOrNull<Int>(THRESHOLD) ?: 15
		horn = retrieveDataOrNull<Boolean>(HORN) ?: true
		loadThreshold = retrieveDataOrNull<Long>(LOAD_THRESHOLD) ?: 1_000L

		typeId = Wiring.lineIdAt(pos, wireFaces()) ?: typeId

		// remove first, always: an add for a known node is a silent no-op,
		// so a stale registration can only be repaired this way
		NetworkManager.queueRemoveBridge(this)
		NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())

		valid = true

		LightSource.retireBulbBacking(pos.block)
		syncLight()
	}

	override fun handleDisable() {
		super.handleDisable()
		valid = false
		persist()
		joints.clear()
		quiet()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		NetworkManager.queueRemoveBridge(this)
		valid = false
		joints.clear()
		quiet()
		LightSource.clear(lightTarget)
	}

	override suspend fun handleNetworkLoaded(state: NetworkState) {
		updateJoints(state)
	}

	override suspend fun handleNetworkUpdate(state: NetworkState) {
		updateJoints(state)
	}

	/** The cube fills its block; the small beacon grows couplings instead. */
	private suspend fun updateJoints(state: NetworkState) {
		if (cube) {
			return
		}

		val faces = state.getConnectedNodes(this).columnKeySet()
		val models = Wiring.jointModels(pos, faces, GaugeItems.JOINTS)

		runTask {
			if (isEnabled) {
				joints.replaceModels(models)
			}
		}
	}

	private fun persist() {
		storeData(METRIC, metric)
		storeData(ABOVE, above)
		storeData(THRESHOLD, threshold)
		storeData(HORN, horn)
		storeData(LOAD_THRESHOLD, loadThreshold)
	}

	private fun quiet() {
		beam?.remove()
		beam = null
		active = false

		// the recording is seconds long: cutting it off with the beam is what
		// makes the beacon read as switched rather than winding down
		for (player in pos.world.getNearbyPlayers(pos.location, 64.0)) {
			player.stopSound(SoundStop.named(SIREN_KEY))
		}
	}

	override fun handleTick() {
		tickCount++

		LightSource.retireBulbBacking(pos.block)
		syncLight()

		// once a second, re-check which cable line the beacon sits in and
		// audit the registration, exactly as the gauges do
		if (tickCount % 20 == 0) {
			val sampled = Wiring.lineIdAt(pos, wireFaces())

			if (sampled != null && sampled != typeId) {
				NetworkManager.queueRemoveBridge(this)
				typeId = sampled
				NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
			}

			auditWire()
		}

		// the siren runs on the wire it sits in: a trickle while it watches,
		// a real draw while it wails, and with neither met it is dead - no
		// judging, no beam, no horn, until the power comes back
		if (tickCount % 20 == 0) {
			val need = if (active) ALERT_DRAIN else PASSIVE_DRAIN

			powered = GaugeSources.drainEnergy(pos, need) >= need

			if (!powered) {
				lastReading = null

				if (active) {
					quiet()
				}

				if (cube && blockState[GaugeCatalog.ON] == true) {
					updateBlockState(blockState.with(GaugeCatalog.ON, false))
				}
			}
		}

		if (!powered) {
			return
		}

		if (tickCount % 20 == 0) {
			judge()
		}

		if (!active) {
			return
		}

		// the sweep: a quarter turn scheduled every eight ticks, and the
		// client interpolates between them into one continuous rotation
		if (tickCount % 8 == 0) {
			beamAngle += 90.0
			beam?.updateEntityData(true) {
				leftRotation = beamRotation(beamAngle)
				transformationInterpolationDelay = 0
			}
		}

		// the wail: a real motor-siren recording, replayed just as its own
		// last cycle fades so the loop reads as one continuous siren
		if (horn) {
			if (sirenClock % SIREN_TICKS == 0) {
				pos.playSound(SIREN, 3.0f, 1.0f)
			}

			sirenClock++
		}
	}

	/** The percent the condition watches, read fresh; null when unreadable. */
	private fun percent(): Int? {
		val anchors = listOf(pos) + FACES.map { face -> pos.advance(face, 1) }

		val fraction = when (metric) {
			0 -> GaugeSources.energy(anchors)?.let { stats ->
				if (stats.capacity <= 0L) null else stats.stored.toDouble() / stats.capacity
			}

			1 -> GaugeSources.fluid(anchors)?.let { stats ->
				if (stats.capacity <= 0L) null else stats.amount.toDouble() / stats.capacity
			}

			else -> GaugeSources.itemsStored(anchors)?.let { stats ->
				if (stats.capacity <= 0L) null else stats.count.toDouble() / stats.capacity
			}
		} ?: return null

		return (fraction * 100).toInt().coerceIn(0, 100)
	}

	private fun judge() {
		val holds: Boolean

		if (metric == 3) {
			// the overload trigger: consumption in J/s against the ladder
			// threshold - "shout when the base draws more than it should"
			val anchors = listOf(pos) + FACES.map { face -> pos.advance(face, 1) }
			val load = GaugeSources.energy(anchors)?.load

			lastReading = load?.let { "đang tải " + GaugeSources.fmt(it.toDouble()) + " J/s" }
			holds = load != null && if (above) {
				load > loadThreshold
			} else {
				load < loadThreshold
			}
		} else {
			val value = percent()

			lastReading = value?.let { "hiện tại $it%" }
			holds = value != null && if (above) {
				value > threshold
			} else {
				value < threshold
			}
		}

		// the glowing base follows the condition, checked before the early
		// return so a stale LIT from a restart heals within a second
		if (cube && (blockState[GaugeCatalog.ON] == true) != holds) {
			updateBlockState(blockState.with(GaugeCatalog.ON, holds))
		}

		if (holds == active) {
			return
		}

		active = holds

		if (!holds) {
			quiet()
			return
		}

		beamAngle = 0.0
		sirenClock = 0
		beam = FakeItemDisplay(lampSpot()) { _, meta ->
			meta.itemStack = GaugeItems.ALARM_BEAM.createItemStack()
			meta.itemDisplay = ItemDisplayTransform.NONE
			meta.leftRotation = beamRotation(0.0)
			meta.transformationInterpolationDelay = 0
			meta.transformationInterpolationDuration = 8
			meta.brightness = Display.Brightness(15, 15)
		}

		menuContainer.forEachMenu<AlarmMenu> { menu ->
			menu.refresh()
		}
	}

	/** Where the lamp sits: along the mounting face, both forms. */
	private fun lampSpot(): Location {
		val out = facing.direction.multiply(if (cube) LAMP_REACH else LAMP_RISE)

		return pos.location.add(0.5 + out.x, 0.5 + out.y, 0.5 + out.z)
	}

	/** The beam spun to an angle about the dome's own axis. */
	private fun beamRotation(angle: Double): Quaternionf =
		orient().rotateY(Math.toRadians(angle).toFloat())

	/**
	 * Turns the beam's authored spin axis (y) onto the dome's axis, the
	 * mounting face on either form - a wall-mounted dome sweeps its cones
	 * through the vertical.
	 */
	private fun orient(): Quaternionf = when (facing) {
		BlockFace.DOWN -> Quaternionf().rotationX(Math.PI.toFloat())
		BlockFace.NORTH -> Quaternionf().rotationX((-Math.PI / 2).toFloat())
		BlockFace.SOUTH -> Quaternionf().rotationX((Math.PI / 2).toFloat())
		BlockFace.EAST -> Quaternionf().rotationZ((-Math.PI / 2).toFloat())
		BlockFace.WEST -> Quaternionf().rotationZ((Math.PI / 2).toFloat())
		else -> Quaternionf()
	}

	/**
	 * Every few seconds, repairs a bridge registration the network state no
	 * longer reflects; three fruitless repairs stop the attempts.
	 */
	private fun auditWire() {
		val now = System.currentTimeMillis()

		if (now < nextAudit || auditStrikes >= MAX_AUDIT_STRIKES) {
			return
		}

		nextAudit = now + AUDIT_MS

		NetworkManager.queueRead(pos.chunkPos) { state ->
			val missing = Wiring.unlinkedFaces(state, this, pos, wireFaces())

			if (missing.isEmpty()) {
				auditStrikes = 0
				return@queueRead
			}

			auditStrikes++
			LunaSmp.logger.warn(
				"alarm at {} has unlinked line faces {} (typeId {}, strike {}); re-adding its bridge",
				pos, missing, typeId, auditStrikes,
			)

			NetworkManager.queueRemoveBridge(this)
			NetworkManager.queueAddBridge(this, WIRE_TYPES, wireFaces())
		}
	}

	private fun metricName(): String = when (metric) {
		0 -> "Điện tích trữ"
		1 -> "Mức chất lỏng"
		2 -> "Kho vật phẩm"
		else -> "Tải điện (J/s)"
	}

	/** What the threshold reads as, in the metric's own unit. */
	private fun thresholdLabel(): String = if (metric == 3) {
		GaugeSources.fmt(loadThreshold.toDouble()) + " J/s"
	} else {
		"$threshold%"
	}

	/** Steps the active threshold: ladder steps for J/s, points for percent. */
	private fun stepThreshold(big: Boolean, up: Boolean) {
		if (metric == 3) {
			loadThreshold = if (up) {
				val stepped = if (big) loadThreshold * 10.0 else GaugeSources.stepUp(loadThreshold.toDouble())

				stepped.toLong().coerceAtMost(1_000_000_000L)
			} else {
				val stepped = if (big) loadThreshold / 10.0 else GaugeSources.stepDown(loadThreshold.toDouble())

				stepped.toLong().coerceAtLeast(20L)
			}

			return
		}

		val step = (if (big) 10 else 1) * (if (up) 1 else -1)

		threshold = (threshold + step).coerceIn(0, 100)
	}

	// ---- the panel -----------------------------------------------------------

	@TileEntityMenuClass
	inner class AlarmMenu : GlobalTileEntityMenu() {

		private val buttons = mutableListOf<AbstractItem>()

		private fun button(build: () -> ItemProvider, click: () -> Unit): AbstractItem {
			val item = object : AbstractItem() {
				override fun getItemProvider(player: Player): ItemProvider = build()

				override fun handleClick(clickType: ClickType, player: Player, click: Click) {
					click()
					persist()
					judge()
					refresh()
				}
			}

			buttons += item

			return item
		}

		fun refresh() {
			for (item in buttons) {
				item.notifyWindows()
			}
		}

		override val gui: Gui = Gui.builder()
			.setStructure(
				"1 - - - - - - - 2",
				"| # m o t s # d |",
				"| # # q w e r # |",
				"3 - - - - - - - 4",
			)
			.addIngredient('m', button({
				ItemBuilder(Material.COMPARATOR)
					.setName("<yellow>Đại lượng: <white>${metricName()}")
					.addLoreLines(Component.text("Bấm để đổi: điện, chất lỏng, vật phẩm, tải điện", NamedTextColor.GRAY))
			}) { metric = (metric + 1) % 4 })
			.addIngredient('o', button({
				ItemBuilder(if (above) Material.RED_CANDLE else Material.SOUL_LANTERN)
					.setName(if (above) "<red>Báo khi CAO hơn ngưỡng" else "<aqua>Báo khi THẤP hơn ngưỡng")
			}) { above = !above })
			.addIngredient('t', button({
				ItemBuilder(Material.PAPER)
					.setName("<white>Ngưỡng: <gold>${thresholdLabel()}")
					.addLoreLines(Component.text("Chỉnh bằng các nút bên dưới", NamedTextColor.GRAY))
			}) { })
			.addIngredient('s', button({
				ItemBuilder(if (horn) Material.GOAT_HORN else Material.BARRIER)
					.setName(if (horn) "<green>Còi: BẬT" else "<gray>Còi: tắt")
			}) { horn = !horn })
			.addIngredient('d', button({
				val reading = lastReading

				ItemBuilder(
					when {
						!powered -> Material.GRAY_DYE
						active -> Material.REDSTONE_TORCH
						else -> Material.LEVER
					},
				)
					.setName(
						when {
							!powered -> "<dark_red>MẤT ĐIỆN - còi không hoạt động"
							reading == null -> "<dark_gray>Không đọc được mạng nào quanh đèn"
							else -> (if (active) "<red>ĐANG BÁO ĐỘNG" else "<green>Yên tĩnh") + " <gray>· <white>$reading"
						},
					)
					.addLoreLines(
						Component.text(
							"Tiêu thụ: $PASSIVE_DRAIN J/s canh gác · $ALERT_DRAIN J/s khi báo động",
							NamedTextColor.GRAY,
						),
					)
			}) { })
			.addIngredient('q', button({ ItemBuilder(Material.RED_STAINED_GLASS_PANE).setName("<red>--") }) {
				stepThreshold(big = true, up = false)
			})
			.addIngredient('w', button({ ItemBuilder(Material.PINK_STAINED_GLASS_PANE).setName("<red>-") }) {
				stepThreshold(big = false, up = false)
			})
			.addIngredient('e', button({ ItemBuilder(Material.LIME_STAINED_GLASS_PANE).setName("<green>+") }) {
				stepThreshold(big = false, up = true)
			})
			.addIngredient('r', button({ ItemBuilder(Material.GREEN_STAINED_GLASS_PANE).setName("<green>++") }) {
				stepThreshold(big = true, up = true)
			})
			.build()
	}

	private companion object {

		const val METRIC = "alarmMetric"
		const val ABOVE = "alarmAbove"
		const val THRESHOLD = "alarmThreshold"
		const val HORN = "alarmHorn"
		const val LOAD_THRESHOLD = "alarmLoadThreshold"

		/** What the alarming base throws, matching the bulb backing it replaced. */
		const val LIGHT_LEVEL = 15

		/** The wail: freesound #470504 (onderwish, CC0), cut to three cycles. */
		const val SIREN = "lunasmp:block.alarm_siren"

		/** The recording is 5.76s of wail; replay as the tail fade begins. */
		const val SIREN_TICKS = 115

		/** Block centre to the block-form dome's lamp, along FACING. */
		const val LAMP_REACH = 0.734

		/** Block centre to the small beacon's lamp: the dome sits low. */
		const val LAMP_RISE = -0.203

		/** What watching costs the wire, and what wailing costs it. */
		const val PASSIVE_DRAIN = 10L
		const val ALERT_DRAIN = 200L

		/** How often and how stubbornly the wire audit repairs a dead link. */
		const val AUDIT_MS = 5_000L
		const val MAX_AUDIT_STRIKES = 3

		/** Everything a cable carries: the beacon passes all of it through. */
		val WIRE_TYPES: Set<NetworkType<*>> = setOf(
			DefaultNetworkTypes.ENERGY,
			DefaultNetworkTypes.ITEM,
			DefaultNetworkTypes.FLUID,
		)

		val SIREN_KEY: Key = Key.key("lunasmp", "block.alarm_siren")

		val FACES = listOf(
			BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
			BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
		)
	}
}
