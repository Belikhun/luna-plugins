package dev.belikhun.luna.smp.signs

import xyz.xenondevs.cbf.Cbf
import xyz.xenondevs.cbf.io.ByteReader
import xyz.xenondevs.cbf.io.ByteWriter
import xyz.xenondevs.cbf.serializer.UnversionedBinarySerializer
import xyz.xenondevs.nova.initialize.Init
import xyz.xenondevs.nova.initialize.InitFun
import xyz.xenondevs.nova.initialize.InitStage

/**
 * The words on a board, wrapped so they survive the disk.
 *
 * CBF's own string serializer (1.0.0-alpha.3) writes a varint of the string's
 * CHARACTER count and then its UTF-8 BYTES: for ASCII the two are equal and
 * nothing shows, but every Vietnamese line is longer in bytes than in
 * characters, so reading it back stops short and the leftover bytes fail the
 * whole entry ("Byte array contains more data than expected"). A board kept
 * its text only until the chunk was saved and reloaded, which is exactly
 * "does not survive a restart".
 *
 * This wrapper owns its bytes: the length prefix is the BYTE count, so the
 * round trip is exact. Reading ignores the prefix entirely and decodes
 * everything to the end of the entry, which is what recovers the boards the
 * broken writer already saved: their full UTF-8 payload is intact on disk,
 * only their prefix lies.
 */
data class BoardWords(val value: String)

/** Reads and writes [BoardWords] with a byte-count prefix; see the class doc. */
object BoardWordsSerializer : UnversionedBinarySerializer<BoardWords>() {

	override fun writeUnversioned(obj: BoardWords, writer: ByteWriter) {
		val bytes = obj.value.toByteArray(Charsets.UTF_8)

		writer.writeVarInt(bytes.size)
		writer.writeBytes(bytes)
	}

	override fun readUnversioned(reader: ByteReader): BoardWords {
		// the prefix is untrusted: entries the broken string writer saved
		// carry a character count here, so the payload is simply the rest
		reader.readVarInt()

		val bytes = reader.asInputStream().readBytes()

		return BoardWords(String(bytes, Charsets.UTF_8))
	}

	override fun copyNonNull(obj: BoardWords): BoardWords = obj
}

/**
 * Registers the serializer before any world data is read, so a chunk loading
 * a board can decode its words, and a chunk saving one can encode them.
 */
@Init(stage = InitStage.PRE_WORLD)
object SignStorage {

	@InitFun
	private fun init() {
		Cbf.registerSerializer(BoardWordsSerializer)
	}
}
