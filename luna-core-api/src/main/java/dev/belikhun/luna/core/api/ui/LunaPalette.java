package dev.belikhun.luna.core.api.ui;

/**
 * Shared color tokens for Luna UI text.
 *
 * Usage guide:
 * - Chat/Scoreboard (dark background): prefer bright/mid shades (*_100, *_300, *_500).
 * - Inventory titles (light background): prefer dark shades (*_700, *_900).
 * - ActionBar/BossBar/Title overlays: prefer neutral/bright shades (*_300, *_500).
 * - Tiering: low=solid basic, mid=solid eye-catching, high=basic gradient, higher=rich gradient.
 * - For inline value highlight, prefer solid shade before gradients.
 */
public final class LunaPalette {
	public static final String PRIMARY_100 = "#dbeafe";
	public static final String PRIMARY_300 = "#93c5fd";
	public static final String PRIMARY_500 = "#3b82f6";
	public static final String PRIMARY_700 = "#1d4ed8";
	public static final String PRIMARY_900 = "#1e3a8a";

	public static final String NEUTRAL_50 = "#f9fafb";
	public static final String NEUTRAL_100 = "#f3f4f6";
	public static final String NEUTRAL_300 = "#d1d5db";
	public static final String NEUTRAL_500 = "#6b7280";
	public static final String NEUTRAL_700 = "#374151";
	public static final String NEUTRAL_900 = "#111827";

	public static final String SUCCESS_300 = "#86efac";
	public static final String SUCCESS_500 = "#22c55e";
	public static final String SUCCESS_700 = "#15803d";

	public static final String WARNING_300 = "#fcd34d";
	public static final String WARNING_500 = "#f59e0b";
	public static final String WARNING_700 = "#b45309";

	public static final String DANGER_300 = "#fca5a5";
	public static final String DANGER_500 = "#ef4444";
	public static final String DANGER_700 = "#b91c1c";

	public static final String INFO_300 = "#67e8f9";
	public static final String INFO_500 = "#06b6d4";
	public static final String INFO_700 = "#0e7490";

	public static final String TEAL_300 = "#5eead4";
	public static final String TEAL_500 = "#14b8a6";
	public static final String TEAL_700 = "#0f766e";

	public static final String VIOLET_300 = "#c4b5fd";
	public static final String VIOLET_500 = "#8b5cf6";
	public static final String VIOLET_700 = "#6d28d9";

	public static final String PINK_300 = "#f9a8d4";
	public static final String PINK_500 = "#ec4899";
	public static final String PINK_700 = "#be185d";

	public static final String AMBER_300 = "#fcd34d";
	public static final String AMBER_500 = "#f59e0b";
	public static final String AMBER_700 = "#b45309";

	public static final String SKY_300 = "#7dd3fc";
	public static final String SKY_500 = "#0ea5e9";
	public static final String SKY_700 = "#0369a1";

	public static final String LIME_300 = "#bef264";
	public static final String LIME_500 = "#84cc16";
	public static final String LIME_700 = "#4d7c0f";

	public static final String GOLD_300 = "#fcd34d";
	public static final String GOLD_500 = "#eab308";
	public static final String GOLD_700 = "#a16207";

	public static final String GUI_TITLE_PRIMARY = "#111827";
	public static final String GUI_TITLE_SECONDARY = "#374151";
	public static final String GUI_TITLE_TERTIARY = "#6b7280";

	private LunaPalette() {
	}
}
