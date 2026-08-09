//! luna's colour names and its command-usage wording.
//!
//! Both are `LunaPalette` and `CommandStrings` from the JVM `luna-core-api`,
//! kept here for the same reason every other wire format is: a player moving
//! between a Paper backend and a Pumpkin one should not be able to tell which
//! drew the message. The values are hex strings rather than parsed colours
//! because that is how they are consumed - interpolated into MiniMessage and
//! handed to [`crate::text::parse`], exactly as the JVM interpolates them into
//! a MiniMessage string and hands it to Adventure.

/// The palette, spelled as `LunaPalette` spells it.
pub mod color {
	pub const NEUTRAL_50: &str = "#f9fafb";
	pub const NEUTRAL_300: &str = "#d1d5db";
	pub const NEUTRAL_500: &str = "#6b7280";
	pub const NEUTRAL_700: &str = "#374151";

	pub const SUCCESS_500: &str = "#22c55e";
	pub const WARNING_300: &str = "#fcd34d";
	pub const WARNING_500: &str = "#f59e0b";
	pub const DANGER_500: &str = "#ef4444";
	pub const INFO_500: &str = "#06b6d4";

	pub const VIOLET_300: &str = "#c4b5fd";
	pub const VIOLET_500: &str = "#8b5cf6";
	pub const TEAL_500: &str = "#14b8a6";
	pub const PINK_500: &str = "#ec4899";
	pub const AMBER_500: &str = "#f59e0b";
	pub const SKY_300: &str = "#7dd3fc";
	pub const SKY_500: &str = "#0ea5e9";
	pub const LIME_500: &str = "#84cc16";
	pub const GOLD_500: &str = "#eab308";

	/// A GUI title is drawn on the chest's own light background, so it takes
	/// the dark end of the neutrals rather than a foreground colour.
	pub const GUI_TITLE_PRIMARY: &str = "#111827";
	pub const GUI_TITLE_SECONDARY: &str = "#374151";
	pub const GUI_TITLE_TERTIARY: &str = "#6b7280";
}

/// One argument in a usage line.
pub struct Argument<'a> {
	pub name: &'a str,
	/// What kind of value it is, which is what picks its colour.
	pub kind: &'a str,
	pub optional: bool,
}

impl<'a> Argument<'a> {
	#[must_use]
	pub fn required(name: &'a str, kind: &'a str) -> Self {
		Self {
			name,
			kind,
			optional: false,
		}
	}

	#[must_use]
	pub fn optional(name: &'a str, kind: &'a str) -> Self {
		Self {
			name,
			kind,
			optional: true,
		}
	}
}

/// The colour an argument of this kind is drawn in, as `typeColor` picks it.
#[must_use]
pub fn type_color(kind: &str) -> &'static str {
	let normalized = kind.to_ascii_lowercase();
	let has = |needle: &str| normalized.contains(needle);

	if has("number") || has("int") || has("double") || has("float") || has("long") {
		return color::GOLD_500;
	}

	if has("bool") {
		return color::LIME_500;
	}

	if has("mini") || has("json") || has("component") {
		return color::VIOLET_500;
	}

	if has("|") || has("enum") || has("choice") {
		return color::PINK_500;
	}

	color::NEUTRAL_300
}

/// `ℹ Dùng: /login <mật_khẩu>`, as MiniMessage, exactly as `CommandStrings`
/// builds it.
///
/// The click and hover wrappers are written even though this platform's
/// renderer drops them: they cost nothing, they keep the string identical to
/// the JVM's, and the day [`crate::text`] learns to carry an action the line
/// starts working rather than needing to be found and changed.
#[must_use]
pub fn usage(root: &str, arguments: &[Argument<'_>]) -> String {
	format!(
		"<color:{amber}>ℹ Dùng: </color>{syntax}",
		amber = color::AMBER_500,
		syntax = syntax(root, arguments),
	)
}

/// The command and its arguments, clickable, without the `ℹ Dùng:` prefix.
#[must_use]
pub fn syntax(root: &str, arguments: &[Argument<'_>]) -> String {
	let normalized = normalize(root);
	let mut visible = format!(
		"<color:{violet}>{normalized}</color>",
		violet = color::VIOLET_300,
	);
	let mut suggest = normalized.clone();

	for argument in arguments {
		visible.push(' ');
		visible.push_str(&render(argument));
		suggest.push(' ');
		suggest.push_str(argument.name);
	}

	clickable(&visible, &suggest)
}

/// One argument, bracketed and coloured by its kind.
fn render(argument: &Argument<'_>) -> String {
	let tint = type_color(argument.kind);
	let (open, close) = if argument.optional {
		("[", "]")
	} else {
		// escaped, because a bare `<` would open a tag in the string this is
		// interpolated into
		("\\<", ">")
	};

	format!(
		"<color:{tint}>{open}</color><color:{tint}>{name}</color><color:{tint}>{close}</color>",
		name = argument.name,
	)
}

/// Wrap a rendered line in the suggest-command click and its hover.
fn clickable(visible: &str, suggest: &str) -> String {
	let command = normalize(suggest);

	if command.trim().is_empty() {
		return visible.to_owned();
	}

	let escaped = command.replace('\\', "\\\\").replace('\'', "\\'");

	format!(
		"<click:suggest_command:'{escaped}'>\
		 <hover:show_text:'<color:{sky}>Nhấn để chèn lệnh vào ô chat</color>'>\
		 {visible}</hover></click>",
		sky = color::SKY_500,
	)
}

/// A command name always carries its slash, however it was written.
fn normalize(value: &str) -> String {
	let trimmed = value.trim();

	if trimmed.is_empty() || trimmed.starts_with('/') {
		return trimmed.to_owned();
	}

	format!("/{trimmed}")
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::text::plain_text;

	#[test]
	fn a_usage_line_reads_the_way_the_jvm_writes_it() {
		let line = usage("/login", &[Argument::required("mat_khau", "text")]);

		assert_eq!(plain_text(&line), "ℹ Dùng: /login <mat_khau>");
	}

	#[test]
	fn two_arguments_are_separated_by_one_space() {
		let line = usage(
			"/register",
			&[
				Argument::required("mat_khau", "text"),
				Argument::required("nhap_lai", "text"),
			],
		);

		assert_eq!(plain_text(&line), "ℹ Dùng: /register <mat_khau> <nhap_lai>");
	}

	#[test]
	fn an_optional_argument_is_bracketed_instead() {
		let line = syntax("/msg", &[Argument::optional("message", "text")]);

		assert_eq!(plain_text(&line), "/msg [message]");
	}

	#[test]
	fn a_missing_slash_is_added() {
		assert_eq!(plain_text(&syntax("login", &[])), "/login");
	}

	#[test]
	fn a_kind_picks_its_colour() {
		assert_eq!(type_color("text"), color::NEUTRAL_300);
		assert_eq!(type_color("int"), color::GOLD_500);
		assert_eq!(type_color("boolean"), color::LIME_500);
		assert_eq!(type_color("minimessage"), color::VIOLET_500);
		assert_eq!(type_color("on|off"), color::PINK_500);
	}

	/// The click wrapper carries the command without its markup, so the text it
	/// inserts is something a player could have typed.
	#[test]
	fn the_click_suggests_the_plain_command() {
		let line = usage("/login", &[Argument::required("mat_khau", "text")]);

		assert!(line.contains("<click:suggest_command:'/login mat_khau'>"));
	}

	#[test]
	fn a_quote_in_a_command_cannot_break_out_of_the_click_tag() {
		let line = syntax("/say", &[Argument::required("it's", "text")]);

		assert!(line.contains("\\'"));
	}
}
