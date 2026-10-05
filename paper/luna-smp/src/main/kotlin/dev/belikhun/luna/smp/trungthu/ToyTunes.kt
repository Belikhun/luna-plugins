package dev.belikhun.luna.smp.trungthu

import org.bukkit.Location
import org.bukkit.SoundCategory
import kotlin.math.pow

/**
 * The tunes a toy lantern plays: the battery lanterns sold at every Trung Thu
 * market carry a piezo buzzer and a chip of melodies, and they sound exactly
 * like the note block's square wave, so that is the voice. The same chips
 * carry the noises every cheap plastic toy makes - sirens, a laser gun, a
 * machine gun, a toy phone ringing with its dog barking, an animal parade -
 * and those come after the songs, built out of vanilla sounds.
 *
 * A melody is written the way Vietnamese "cảm âm" notation writes it:
 * solfège names, `#` after a name for a sharp, `2` for the octave above and
 * `0` for the one below, each with a length in sixteenths after the colon,
 * and `-` for a rest. The number
 * after a tune's name says which note-block note `do` falls on; every tune is
 * placed so its whole range sits inside the note block's two octaves.
 */
object ToyTunes {

	/** One sound: the tick into the loop it starts on, what plays, how high and how loud. */
	data class Note(val tick: Int, val sound: String, val pitch: Float, val volume: Float = 1f)

	class Song(val name: String, val notes: List<Note>, val length: Int) {

		/**
		 * A melody in cảm âm: solfège with its length in sixteenths, played on
		 * the buzzer, with a breath at the end before it goes round again.
		 */
		constructor(name: String, base: Int, score: String, unitTicks: Int = 2) : this(name, parse(base, score, unitTicks), 0)

		private val loop: Int = if (length > 0) length else (notes.maxOfOrNull { it.tick } ?: 0) + REST_BETWEEN

		/** How long one pass takes, including the breath before it starts again. */
		val period: Int
			get() = loop
	}

	private const val BUZZER = "block.note_block.bit"

	private fun parse(base: Int, score: String, unitTicks: Int): List<Note> {
		var tick = 0
		val out = ArrayList<Note>()

		for (token in score.trim().split(Regex("\\s+"))) {
			val (name, units) = token.split(":")

			if (name != "-") {
				out.add(Note(tick, BUZZER, pitch(base + semitones(name))))
			}

			tick += units.toInt() * unitTicks
		}

		return out
	}

	/**
	 * A real recorded arrangement of a song, shipped in the trungthu sky pack as a streamed sound
	 * `tt:lantern.<file>`; it starts on tick 0 of the loop and the loop is the track's own length.
	 */
	private fun track(name: String, file: String, ticks: Int): Song =
		Song(name, listOf(Note(0, "tt:lantern.$file", 1f)), ticks)

	/** Every streamed track, for stopping whatever a toy was playing. */
	val TRACKS: List<String> by lazy { SONGS.flatMap { s -> s.notes.map { it.sound } }.filter { it.startsWith("tt:lantern.") }.distinct() }

	/** Stops [song]'s streamed track for everyone within [radius] of [at] (a tune switched, a toy put away). */
	fun stop(song: Int, at: Location, radius: Double = 48.0) {
		val s = SONGS.getOrNull(song) ?: return
		val key = s.notes.firstOrNull()?.sound ?: return
		if (!key.startsWith("tt:lantern.")) return
		for (p in at.world.players) {
			if (p.location.distanceSquared(at) <= radius * radius) {
				p.stopSound(key, SoundCategory.RECORDS)
			}
		}
	}

	/** Builds a noise out of timed sounds; `length` is the whole loop, pause included. */
	private fun noise(name: String, length: Int, build: MutableList<Note>.() -> Unit): Song =
		Song(name, buildList(build), length)

	private val STEPS = mapOf("do" to 0, "re" to 2, "mi" to 4, "fa" to 5, "sol" to 7, "la" to 9, "si" to 11)

	/** How long a toy waits before it plays the tune again, in ticks. */
	private const val REST_BETWEEN = 30

	private fun semitones(name: String): Int {
		val octave = when {
			name.endsWith("2") -> 12
			name.endsWith("0") -> -12
			else -> 0
		}

		val step = name.trimEnd('0', '2')

		// a sharp is written after the name and before the octave: re#2
		if (step.endsWith("#")) {
			return (STEPS[step.dropLast(1)] ?: error("unknown note $name")) + 1 + octave
		}

		return (STEPS[step] ?: error("unknown note $name")) + octave
	}

	/** The tunes, in the order a click cycles them; the lantern table names its first one by index. */
	val SONGS: List<Song> = listOf(
		track("Rước Đèn Tháng Tám", "ruoc_den_thang_tam", 562),
		track("Chiếc Đèn Ông Sao", "chiec_den_ong_sao", 710),
		track("Kìa Con Bướm Vàng", "kia_con_buom_vang", 704),
		track("Lấp Lánh Ngôi Sao", "lap_lanh_ngoi_sao", 618),
		track("Jingle Bells", "jingle_bells", 682),
		track("Chúc Mừng Sinh Nhật", "chuc_mung_sinh_nhat", 342),
		track("Đêm Trung Thu", "dem_trung_thu", 715),
		track("Một Con Vịt", "mot_con_vit", 754),
		track("Für Elise", "fur_elise", 497),

		// the police siren: one long glide up and back down
		noise("Còi Cảnh Sát", 48) {
			for (t in 0 until 48 step 2) {
				val rise = if (t < 24) t / 24.0 else (48 - t) / 24.0
				add(Note(t, BUZZER, (0.8 + rise * 1.0).toFloat(), 0.8f))
			}
		},

		// the fire engine: two tones, hee-haw
		noise("Còi Cứu Hỏa", 40) {
			for (t in 0 until 40 step 2) {
				add(Note(t, BUZZER, if (t < 20) 1.26f else 0.94f, 0.8f))
			}
		},

		// pew pew pew: three shots, each a fast drop in pitch, then a pause
		noise("Súng Laser", 44) {
			for (shot in listOf(0, 9, 18)) {
				for (i in 0 until 6) {
					add(Note(shot + i, BUZZER, (2.0 - i * 0.26).toFloat(), 0.8f))
				}
			}
		},

		// the machine gun with the flashing light: a rattle, then the whoop
		noise("Súng Máy Đồ Chơi", 50) {
			for (t in 0 until 20 step 2) {
				add(Note(t, "block.note_block.snare", 1.3f, 0.9f))
				add(Note(t, "block.note_block.hat", 0.8f, 0.6f))
			}

			for (t in 24 until 40 step 2) {
				add(Note(t, BUZZER, (0.9 + (t - 24) / 16.0).toFloat(), 0.7f))
			}
		},

		// the toy phone: it rings twice, a dog barks, somebody answers, and
		// it beeps its number back, the way every knock-off phone did
		noise("Điện Thoại Đồ Chơi", 110) {
			for (burst in listOf(0, 26)) {
				for (i in 0 until 16) {
					add(Note(burst + i, "block.note_block.bell", if (i % 2 == 0) 1.6f else 1.35f, 0.5f))
				}
			}

			add(Note(52, "entity.wolf.ambient", 1.4f, 0.7f))
			add(Note(62, "entity.wolf.ambient", 1.5f, 0.7f))
			add(Note(76, "entity.villager.ambient", 1.3f, 0.8f))

			listOf(1.5f, 1.2f, 1.8f, 1.35f, 1.6f).forEachIndexed { i, p ->
				add(Note(90 + i * 3, BUZZER, p, 0.6f))
			}
		},

		// the animal button board: a cat, a dog, a rooster, a cow, a pig, a sheep
		noise("Đồ Chơi Tiếng Thú", 130) {
			val animals = listOf(
				"entity.cat.ambient" to 1.2f,
				"entity.wolf.ambient" to 1.3f,
				"entity.chicken.ambient" to 0.9f,
				"entity.cow.ambient" to 1.1f,
				"entity.pig.ambient" to 1.2f,
				"entity.sheep.ambient" to 1.2f,
			)

			animals.forEachIndexed { i, (sound, p) ->
				add(Note(i * 20, BUZZER, 1.8f, 0.4f))
				add(Note(i * 20 + 3, sound, p, 0.8f))
			}
		},
	)

	/** The name shown when a toy is switched to [song], or switched off. */
	fun nameOf(song: Int): String = SONGS.getOrNull(song)?.name ?: "Tắt nhạc"

	/** A note block's pitch for a note: twelve steps to the octave around note 12. */
	fun pitch(note: Int): Float = 2.0.pow((note.coerceIn(0, 24) - 12) / 12.0).toFloat()

	/**
	 * Sounds whatever [song] has on [position] ticks into its loop, at [at],
	 * for everyone in earshot, and says how many sounds that was.
	 */
	fun play(song: Song, position: Int, at: Location, volume: Float, emitter: org.bukkit.entity.Entity? = null): Int {
		val tick = position % song.period
		var played = 0

		for (note in song.notes) {
			if (note.tick == tick) {
				if (emitter != null) {
					at.world.playSound(emitter, note.sound, SoundCategory.RECORDS, volume * note.volume, note.pitch)
				} else {
					at.world.playSound(at, note.sound, SoundCategory.RECORDS, volume * note.volume, note.pitch)
				}
				played++
			}
		}

		return played
	}

	/** The tune after [song] in a toy's cycle; the step after the last switches it off. */
	fun next(song: Int): Int = if (song >= SONGS.size - 1) OFF else song + 1

	/** A toy switched off. */
	const val OFF = -1
}
