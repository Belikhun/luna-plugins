package dev.belikhun.luna.deathswap;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One private sidebar per player. Lines are score entries with a custom name
 * and a blank number, so nothing but the text shows; only lines whose text
 * changed are sent again.
 */
public final class DeathSwapSidebar {
	private static final int MAX_LINES = 15;

	private final Map<UUID, Board> boards = new HashMap<>();

	private static final class Board {
		private final Scoreboard scoreboard;
		private final Objective objective;
		private String title = "";
		private final List<String> lines = new ArrayList<>();

		private Board(Scoreboard scoreboard, Objective objective) {
			this.scoreboard = scoreboard;
			this.objective = objective;
		}
	}

	public void render(Player player, String title, List<String> lines) {
		Board board = boards.get(player.getUniqueId());
		if (board == null || player.getScoreboard() != board.scoreboard) {
			Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
			Objective objective = scoreboard.registerNewObjective("lunadeathswap", Criteria.DUMMY, DeathSwapText.mm(title));
			objective.setDisplaySlot(DisplaySlot.SIDEBAR);
			objective.numberFormat(NumberFormat.blank());
			board = new Board(scoreboard, objective);
			boards.put(player.getUniqueId(), board);
			player.setScoreboard(scoreboard);
			board.title = title;
		}

		if (!Objects.equals(board.title, title)) {
			board.objective.displayName(DeathSwapText.mm(title));
			board.title = title;
		}

		List<String> wanted = lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
		int count = wanted.size();
		for (int index = 0; index < count; index++) {
			String text = wanted.get(index);
			boolean sameSlot = index < board.lines.size();
			boolean sameText = sameSlot && Objects.equals(board.lines.get(index), text);
			boolean sameScore = sameSlot && board.lines.size() == count;
			if (sameText && sameScore) {
				continue;
			}

			Score score = board.objective.getScore(entry(index));
			score.setScore(count - index);
			score.customName(DeathSwapText.mm(text.isEmpty() ? " " : text));
		}

		for (int index = count; index < board.lines.size(); index++) {
			board.scoreboard.resetScores(entry(index));
		}

		board.lines.clear();
		board.lines.addAll(wanted);
	}

	public void remove(Player player) {
		Board board = boards.remove(player.getUniqueId());
		if (board != null && player.isOnline() && player.getScoreboard() == board.scoreboard) {
			player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
		}
	}

	public void forget(UUID playerId) {
		boards.remove(playerId);
	}

	public void clear() {
		for (UUID playerId : new ArrayList<>(boards.keySet())) {
			Player player = Bukkit.getPlayer(playerId);
			if (player != null) {
				remove(player);
			}
		}
		boards.clear();
	}

	private static String entry(int index) {
		return "ds" + index;
	}
}
