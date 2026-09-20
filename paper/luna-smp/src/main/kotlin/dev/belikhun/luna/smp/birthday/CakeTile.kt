package dev.belikhun.luna.smp.birthday

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockBreak
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockInteract
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.util.BlockUtils
import xyz.xenondevs.nova.util.item.novaItem
import xyz.xenondevs.nova.util.runTask
import xyz.xenondevs.nova.util.runTaskLater
import xyz.xenondevs.nova.util.runTaskTimer
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.state.NovaBlockState
import xyz.xenondevs.nova.world.block.tileentity.TileEntity
import xyz.xenondevs.nova.world.format.WorldDataManager
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cake: the lower block of the tower, and the one that owns the occasion.
 *
 * It goes through three phases. Placed, it waits. A click lights the candles
 * and starts the song, with the words printed as each line comes round. A
 * click while the candles burn blows them out - the wish is made - and the
 * party starts: applause, a run of fireworks around the cake, a title for
 * everyone near, and from then on stars twinkling in the sky above it, with
 * the odd shooting star, until the cake is broken. A bowl held against the
 * cake at any point cuts a slice onto it.
 *
 * The phase and nothing else is what persists; the song and the fireworks
 * are runtime tasks, cancelled with the tile. The stars need no task at all:
 * they are drawn by this block's own tick while the phase says so, which is
 * also what keeps them going through a restart.
 */
class CakeTile(
	pos: BlockPos,
	blockState: NovaBlockState,
	data: Compound,
) : TileEntity(pos, blockState, data) {

	/** Set while the upper block breaks this one, so this one does not break back. */
	var breakingPartner = false

	private var phase: Int
		get() = retrieveDataOrNull<Int>("phase") ?: WAITING
		set(value) = storeData("phase", value)

	private val bites: Int
		get() = blockState[BirthdayCatalog.BITES] ?: 0

	private val top: CakeTopTile?
		get() = WorldDataManager.getTileEntity(pos.add(0, 1, 0)) as? CakeTopTile

	private var tune: TunePlayer? = null
	private val tasks = ArrayList<BukkitTask>()

	private val random = ThreadLocalRandom.current()

	// ---- the tower --------------------------------------------------------------

	override fun handlePlace(ctx: Context<BlockPlace>) {
		super.handlePlace(ctx)

		val above = pos.add(0, 1, 0)

		// the player places the lower block and the upper one is built here,
		// the way a door is. No headroom means no cake: it pops straight back
		// off as its item. Both a tick later, since placing or breaking from
		// inside the placement still in flight is asking for re-entrancy.
		runTask {
			if (!isEnabled) {
				return@runTask
			}

			if (!above.block.type.isAir) {
				BlockUtils.breakBlockNaturally(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, true)
						.build(),
				)

				return@runTask
			}

			BlockUtils.placeBlock(
				Context.intention(BlockPlace)
					.param(DefaultContextParamTypes.BLOCK_POS, above)
					.param(DefaultContextParamTypes.BLOCK_STATE_NOVA, BirthdayCatalog.CAKE_TOP.defaultBlockState)
					.param(DefaultContextParamTypes.BLOCK_PLACE_EFFECTS, false)
					.build(),
			)
		}
	}

	override fun handleEnable() {
		if (phase == LIT) {
			placeLight()
		}
	}

	override fun handleDisable() {
		super.handleDisable()
		stopEverything()
	}

	override fun handleBreak(ctx: Context<BlockBreak>) {
		super.handleBreak(ctx)
		stopEverything()
		clearLight()

		if (breakingPartner) {
			return
		}

		val other = top ?: return
		other.breakingPartner = true

		// the upper block drops nothing, so its break carries no verdict; a
		// tick later for the same re-entrancy reason as the placement
		runTask {
			if (other.isEnabled) {
				BlockUtils.breakBlockNaturally(
					Context.intention(BlockBreak)
						.param(DefaultContextParamTypes.BLOCK_POS, other.pos)
						.param(DefaultContextParamTypes.BLOCK_DROPS, false)
						.param(DefaultContextParamTypes.BLOCK_BREAK_EFFECTS, false)
						.build(),
				)
			}
		}
	}

	private fun stopEverything() {
		tune?.stop()
		tune = null

		for (task in tasks) {
			task.cancel()
		}

		tasks.clear()
	}

	// ---- clicks --------------------------------------------------------------------

	override fun handleRightClick(ctx: Context<BlockInteract>): Boolean {
		val player = ctx[DefaultContextParamTypes.SOURCE_ENTITY] as? Player ?: return false
		val hand = ctx[DefaultContextParamTypes.INTERACTION_HAND] ?: EquipmentSlot.HAND

		// once per hand: acting on both would light and blow out in one click
		if (hand != EquipmentSlot.HAND || player.isSneaking) {
			return false
		}

		val held = player.inventory.getItem(hand)

		if (held.type == Material.BOWL || held.novaItem == BirthdayItems.CAKE_PLATE) {
			return slice(player, held)
		}

		if (held.type == Material.FLINT_AND_STEEL && phase != LIT) {
			light()
			return true
		}

		when (phase) {
			WAITING -> light()
			LIT -> blowOut(player)
			else -> replay()
		}

		return true
	}

	// ---- slicing --------------------------------------------------------------------

	private fun slice(player: Player, vessel: ItemStack): Boolean {
		if (bites >= BirthdayCatalog.SLICES) {
			player.sendActionBar(Component.text("Bánh chỉ còn lại phần lõi thôi, hết miếng để cắt rồi."))
			return true
		}

		updateBlockState(blockState.with(BirthdayCatalog.BITES, bites + 1))

		if (player.gameMode != GameMode.CREATIVE) {
			vessel.amount -= 1
		}

		val plate = BirthdayItems.CAKE_SLICE.createItemStack()
		val leftover = player.inventory.addItem(plate)

		for (stack in leftover.values) {
			pos.world.dropItemNaturally(centre(), stack)
		}

		val at = centre()
		pos.world.playSound(at, "block.honey_block.break", SoundCategory.BLOCKS, 0.8f, 1.4f)
		pos.world.playSound(at, "entity.item.pickup", SoundCategory.PLAYERS, 0.5f, 1.1f)
		pos.world.spawnParticle(Particle.ITEM, at.add(0.0, 0.3, 0.0), 8, 0.3, 0.2, 0.3, 0.05, ItemStack(Material.CAKE))

		return true
	}

	// ---- candles and the song ---------------------------------------------------------

	private fun light() {
		phase = LIT
		top?.setLit(true)
		placeLight()

		val at = centre()
		pos.world.playSound(at, "item.flintandsteel.use", SoundCategory.BLOCKS, 1f, 1f)
		pos.world.playSound(at, "block.amethyst_block.chime", SoundCategory.BLOCKS, 0.8f, 1.2f)

		startTune()
	}

	/** After the wish, a click plays the song again, candles out; a flint relights them. */
	private fun replay() {
		if (tune?.playing == true) {
			return
		}

		startTune()
	}

	private fun startTune() {
		tune?.stop()

		tune = TunePlayer(
			candleLevel(),
			onPhrase = { index -> sing(index) },
			onDone = {
				tune = null
				remind()
			},
		)

		tune?.start()
	}

	/** When the song ends with the candles still burning, tells whoever is near what comes next. */
	private fun remind() {
		if (phase != LIT) {
			return
		}

		val message = mini.deserialize(
			"<color:#f3c6d8>Hãy ước một điều ước và phải chuột vào bánh để thổi nến nhaa</color> <color:#ffd94a>★</color>",
		)

		for (near in pos.world.getNearbyPlayers(centre(), 48.0)) {
			near.sendMessage(message)
		}
	}

	/** Prints a line of the words to everyone on the server: a birthday is not a private event. */
	private fun sing(index: Int) {
		val line = HappyBirthday.lyric(index)

		for (player in Bukkit.getOnlinePlayers()) {
			player.sendMessage(line)
		}
	}

	private fun blowOut(player: Player) {
		tune?.stop()
		tune = null

		phase = CELEBRATING
		top?.setLit(false)
		clearLight()

		val world = pos.world

		for (tip in candleTips()) {
			world.playSound(tip, "block.candle.extinguish", SoundCategory.BLOCKS, 1f, 1f)
			world.spawnParticle(Particle.SMOKE, tip, 4, 0.03, 0.05, 0.03, 0.01)
			world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, tip, 1, 0.0, 0.0, 0.0, 0.0)
		}

		val message = mini.deserialize(
			"<color:#f3c6d8>${player.name} đã thổi nến và ước một điều ước</color> <color:#ffd94a>★</color>",
		)

		for (online in Bukkit.getOnlinePlayers()) {
			online.sendMessage(message)
		}

		val title = Title.title(
			mini.deserialize("<gradient:#ff9ecf:#ff4f8b><bold>Chúc mừng sinh nhật bé ne</bold></gradient> <color:#ff4f8b>❤</color>"),
			mini.deserialize("<color:#f3c6d8>Mong mọi điều ước của bé ne đều thành hiện thực</color> <color:#ffd94a>★</color>"),
			Title.Times.times(Duration.ofMillis(600), Duration.ofSeconds(5), Duration.ofMillis(1200)),
		)

		for (near in world.getNearbyPlayers(centre(), 48.0)) {
			near.showTitle(title)
			near.playSound(near.location, "ui.toast.challenge_complete", SoundCategory.PLAYERS, 0.9f, 1f)
		}

		// confetti off the cake, then the applause, then the fireworks
		world.spawnParticle(Particle.TOTEM_OF_UNDYING, candleLevel(), 80, 0.4, 0.3, 0.4, 0.5)

		later(12) {
			world.playSound(centre(), "lunasmp:birthday.applause", SoundCategory.PLAYERS, 1.6f, 1f)
		}

		for (i in 0 until FIREWORKS) {
			later(30L + i * 11L) { launchFirework() }
		}
	}

	private fun later(delay: Long, run: () -> Unit) {
		tasks += runTaskLater(delay) {
			if (isEnabled) {
				run()
			}
		}
	}

	// ---- the party -------------------------------------------------------------------

	private fun launchFirework() {
		val angle = random.nextDouble(0.0, Math.PI * 2)
		val radius = random.nextDouble(2.5, 7.0)
		val at = pos.location.add(0.5 + cos(angle) * radius, 1.0, 0.5 + sin(angle) * radius)

		if (!at.block.type.isAir) {
			at.y = pos.y + 2.0
		}

		pos.world.spawn(at, Firework::class.java) { firework ->
			val meta = firework.fireworkMeta
			meta.power = random.nextInt(1, 3)

			val type = SHAPES[random.nextInt(SHAPES.size)]
			val colour = PALETTE[random.nextInt(PALETTE.size)]
			val second = PALETTE[random.nextInt(PALETTE.size)]

			meta.addEffect(
				FireworkEffect.builder()
					.with(type)
					.withColor(colour, second)
					.withFade(Color.WHITE)
					.trail(random.nextBoolean())
					.flicker(true)
					.build(),
			)

			firework.fireworkMeta = meta
		}
	}

	override fun handleTick() {
		when (phase) {
			LIT -> sparkle()
			CELEBRATING -> starlight()
		}
	}

	/** The sparkler throwing sparks and the candles flickering while they burn. */
	private fun sparkle() {
		val world = pos.world
		val tip = sparklerTip()

		world.spawnParticle(Particle.FIREWORK, tip, 3, 0.05, 0.05, 0.05, 0.06)
		world.spawnParticle(Particle.ELECTRIC_SPARK, tip, 1, 0.1, 0.1, 0.1, 0.0)

		if (random.nextInt(3) == 0) {
			val candle = candleTips()[random.nextInt(4)]
			world.spawnParticle(Particle.SMALL_FLAME, candle, 1, 0.02, 0.02, 0.02, 0.0)
		}
	}

	/**
	 * Stars over the cake: a few new points of light every tick this runs,
	 * spread through a wide column of sky above, and now and then a star
	 * that streaks across it. Nothing is drawn with nobody near enough to
	 * see it.
	 */
	private fun starlight() {
		val world = pos.world
		val centre = centre()

		if (world.getNearbyPlayers(centre, 96.0).isEmpty()) {
			return
		}

		repeat(3) {
			val at = skyPoint(6.0, 26.0, 5.0, 28.0)
			world.spawnParticle(Particle.END_ROD, at, 1, 0.0, 0.0, 0.0, 0.0)
		}

		repeat(2) {
			val at = skyPoint(4.0, 24.0, 4.0, 26.0)
			world.spawnParticle(Particle.FIREWORK, at, 1, 0.0, 0.0, 0.0, 0.0)
		}

		if (random.nextInt(24) == 0) {
			shootingStar()
		}
	}

	/** A point in the sky over the cake: a random distance out, a random height up. */
	private fun skyPoint(minRadius: Double, maxRadius: Double, minHeight: Double, maxHeight: Double): Location {
		val angle = random.nextDouble(0.0, Math.PI * 2)
		val radius = random.nextDouble(minRadius, maxRadius)

		return pos.location.add(
			0.5 + cos(angle) * radius,
			random.nextDouble(minHeight, maxHeight),
			0.5 + sin(angle) * radius,
		)
	}

	/** A streak across the sky: a bright head moving a little over a block a tick, with a fading tail. */
	private fun shootingStar() {
		val world = pos.world
		val head = skyPoint(14.0, 30.0, 22.0, 36.0)
		val heading = random.nextDouble(0.0, Math.PI * 2)
		val step = Location(world, cos(heading) * 1.3, -0.35, sin(heading) * 1.3)
		var left = 16

		lateinit var task: BukkitTask

		task = runTaskTimer(0, 1) {
			if (!isEnabled || left-- <= 0) {
				task.cancel()
				tasks.remove(task)
				return@runTaskTimer
			}

			head.add(step)
			world.spawnParticle(Particle.END_ROD, head, 1, 0.0, 0.0, 0.0, 0.0)
			world.spawnParticle(Particle.FIREWORK, head, 2, 0.08, 0.08, 0.08, 0.0)
		}

		tasks += task
	}

	// ---- geometry -------------------------------------------------------------------------

	private fun centre(): Location = pos.location.add(0.5, 0.5, 0.5)

	/** The height the candles' flames sit at: the middle of the upper block. */
	private fun candleLevel(): Location = pos.location.add(0.5, 1.7, 0.5)

	/** Where the four candle flames are in the world. */
	private fun candleTips(): List<Location> = CANDLES.map { (x, z) ->
		pos.location.add(x, 1.0 + 10.75 / 16.0, z)
	}

	private fun sparklerTip(): Location = pos.location.add(0.5, 1.0 + 13.5 / 16.0, 0.5)

	/** The glow of the candles: a light block in the air above the tower. */
	private fun placeLight() {
		val target = pos.add(0, 2, 0).block

		if (!target.type.isAir) {
			return
		}

		target.type = Material.LIGHT

		val data = target.blockData as Levelled
		data.level = 11
		target.blockData = data
	}

	private fun clearLight() {
		val target = pos.add(0, 2, 0).block

		if (target.type == Material.LIGHT) {
			target.type = Material.AIR
		}
	}

	private companion object {

		const val WAITING = 0
		const val LIT = 1
		const val CELEBRATING = 2

		/** How many rockets go up after the wish. */
		const val FIREWORKS = 18

		/** The candles' positions in the upper block, in blocks, matching birthday.ts. */
		val CANDLES = listOf(
			6.0 / 16 to 6.0 / 16,
			10.0 / 16 to 6.0 / 16,
			6.0 / 16 to 10.0 / 16,
			10.0 / 16 to 10.0 / 16,
		)

		val SHAPES = listOf(
			FireworkEffect.Type.BALL_LARGE,
			FireworkEffect.Type.STAR,
			FireworkEffect.Type.BURST,
			FireworkEffect.Type.BALL,
		)

		val PALETTE = listOf(
			Color.fromRGB(0xff6fa5),
			Color.fromRGB(0xff9ecf),
			Color.fromRGB(0xffffff),
			Color.fromRGB(0xff4f8b),
			Color.fromRGB(0xffd94a),
			Color.fromRGB(0x9ad7ff),
		)

		val mini: MiniMessage = MiniMessage.miniMessage()
	}
}
