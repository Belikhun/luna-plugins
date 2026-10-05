package dev.belikhun.luna.core.velocity.tablist;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.UUID;

/**
 * The tab list one player currently sees, as the proxy holds it.
 *
 * Entries are what Velocity's tab list API reports for that viewer; the header and
 * footer are what the proxy last sent them. Both are the truth the client has,
 * which is what makes this a render and not a guess.
 *
 * @param viewerId             the viewer's UUID
 * @param viewerName           the viewer's name
 * @param viewerServer         the backend the viewer is on, or {@code null} while switching
 * @param header               the header last sent, or {@code null} when none was sent
 * @param footer               the footer last sent, or {@code null} when none was sent
 * @param headerFooterReadable false when the proxy build hid the accessors and the
 *                             header and footer could not be read at all
 * @param entries              the entries in the order the client sorts them
 * @param capturedAtEpochMillis when the capture was taken
 */
public record TabListCapture(
	UUID viewerId,
	String viewerName,
	String viewerServer,
	Component header,
	Component footer,
	boolean headerFooterReadable,
	List<Entry> entries,
	long capturedAtEpochMillis
) {
	/**
	 * One row of the tab list.
	 *
	 * @param profileId   the entry's UUID
	 * @param profileName the entry's profile name, which is what sorts it when no
	 *                    list order is set
	 * @param displayName the text the client draws, or {@code null} when it draws
	 *                    the profile name
	 * @param latencyMillis the latency the client draws bars for
	 * @param gameMode    the entry's game mode id
	 * @param listed      false for entries the client keeps but hides
	 * @param listOrder   the 1.21.2+ explicit sort key, higher first
	 */
	public record Entry(
		UUID profileId,
		String profileName,
		Component displayName,
		int latencyMillis,
		int gameMode,
		boolean listed,
		int listOrder
	) {
	}
}
