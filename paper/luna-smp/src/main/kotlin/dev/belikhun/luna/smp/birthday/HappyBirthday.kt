package dev.belikhun.luna.smp.birthday

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.scheduler.BukkitTask
import xyz.xenondevs.nova.util.runTaskTimer
import kotlin.math.pow

/**
 * "Happy Birthday" for note blocks, with the words that go with it.
 *
 * The melody is written in note-block notes (0 is F#3, 24 two octaves up),
 * which is how a builder would lay it on note blocks; the harp carries it,
 * a bell doubles it quietly for sparkle, and a bass note falls on each
 * downbeat. Time is in server ticks at a crotchet of ten, so the whole song
 * is twelve seconds: four phrases of three bars, each phrase starting on the
 * two pick-up notes of "Happy".
 *
 * The words are printed as each phrase begins. The first two lines are the
 * ones asked for; the third fits the long "dear ..." phrase syllable for
 * syllable, and the last wishes the new year well without laying it on thick.
 */
object HappyBirthday {

	/** One note: when it sounds, which note-block note, and which line of the words it starts. */
	data class Note(val tick: Int, val note: Int, val phrase: Int = -1)

	/** The words, one line per phrase. */
	val LYRICS: List<String> = listOf(
		"Mừng ngày sinh nhật của nhi",
		"Mừng ngày nhi thật đáng yêu",
		"Chúc bé ne luôn xinh, luôn vui",
		"Tuổi mới thật nhiều niềm vui",
	)

	private const val PHRASE = 60

	/**
	 * The melody, phrase by phrase. Each phrase is "pick-up, pick-up, then
	 * four beats", the last beat held; the third phrase runs one note longer
	 * for the two notes the name is sung on.
	 */
	val MELODY: List<Note> = buildList {
		fun phrase(index: Int, notes: List<Pair<Int, Int>>) {
			val start = index * PHRASE

			for ((offset, note) in notes) {
				add(Note(start + offset, note, if (offset == 0) index else -1))
			}
		}

		// so so la so do' ti
		phrase(0, listOf(0 to 6, 6 to 6, 10 to 8, 20 to 6, 30 to 11, 40 to 10))
		// so so la so re' do'
		phrase(1, listOf(0 to 6, 6 to 6, 10 to 8, 20 to 6, 30 to 13, 40 to 11))
		// so so so' mi' do' ti la
		phrase(2, listOf(0 to 6, 6 to 6, 10 to 18, 20 to 15, 30 to 11, 40 to 10, 50 to 8))
		// fa' fa' mi' do' re' do'
		phrase(3, listOf(0 to 16, 6 to 16, 10 to 15, 20 to 11, 30 to 13, 40 to 11))
	}

	/** The bass on each downbeat: I, V, I, V, I, IV, IV, V and home. */
	val BASS: List<Note> = listOf(
		Note(10, 1), Note(40, 8), Note(70, 1), Note(100, 8),
		Note(130, 1), Note(160, 6), Note(190, 6), Note(220, 8), Note(230, 1),
	)

	/** How long the song runs, in ticks, including the last held note. */
	const val LENGTH = 4 * PHRASE

	/** A note block's pitch for a note: twelve steps to the octave around note 12. */
	fun pitch(note: Int): Float = 2.0.pow((note - 12) / 12.0).toFloat()

	private val mini = MiniMessage.miniMessage()

	/** A line of the words as it is printed in chat, with the heart the words end on. */
	fun lyric(index: Int): Component {
		val text = LYRICS.getOrNull(index) ?: return Component.empty()

		return mini.deserialize("<gradient:#ff9ecf:#ff4f8b><bold>♪ $text</bold></gradient> <color:#ff4f8b>❤</color>")
	}
}

/**
 * Plays [HappyBirthday] once from a point in the world, calling back as each
 * phrase starts and when the song ends. Sound reaches about two chunks; the
 * words are the caller's to deliver.
 */
class TunePlayer(
	private val at: Location,
	private val onPhrase: (Int) -> Unit,
	private val onDone: () -> Unit,
) {

	private var task: BukkitTask? = null
	private var tick = 0

	/** Whether the song is still going. */
	val playing: Boolean
		get() = task != null

	fun start() {
		if (task != null) {
			return
		}

		tick = 0
		task = runTaskTimer(0, 1) { step() }
	}

	fun stop() {
		task?.cancel()
		task = null
	}

	private fun step() {
		val world = at.world

		for (note in HappyBirthday.MELODY) {
			if (note.tick != tick) {
				continue
			}

			val pitch = HappyBirthday.pitch(note.note)
			world.playSound(at, "block.note_block.harp", SoundCategory.RECORDS, 2f, pitch)
			world.playSound(at, "block.note_block.bell", SoundCategory.RECORDS, 0.35f, pitch)

			// a note particle in the note's own colour, floating up off the candles
			world.spawnParticle(Particle.NOTE, at.clone().add(0.0, 1.9, 0.0), 0, note.note / 24.0, 0.0, 0.0, 1.0)

			if (note.phrase >= 0) {
				onPhrase(note.phrase)
			}
		}

		for (note in HappyBirthday.BASS) {
			if (note.tick == tick) {
				world.playSound(at, "block.note_block.bass", SoundCategory.RECORDS, 1.2f, HappyBirthday.pitch(note.note))
			}
		}

		tick++

		if (tick > HappyBirthday.LENGTH) {
			stop()
			onDone()
		}
	}
}
