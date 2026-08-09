//! The MiniMessage progress bar every luna GUI draws its metrics with.
//!
//! This is `LunaProgressBar` and `LunaProgressBarPresets` from the JVM
//! `luna-core-api`. A bar is a string, not a widget: the caller interpolates it
//! into an item's lore and [`crate::text::parse`] reads it back, exactly as
//! Adventure reads it on a Paper backend. That is what makes a dashboard drawn
//! here indistinguishable from the one drawn there.
//!
//! Only the four presets luna actually uses are ported. The JVM class is a
//! general builder with renderer hooks and three layouts; reproducing all of it
//! would mean carrying closures for combinations nothing asks for. What matters
//! for parity is the output, and the output of these four is exact.

use crate::palette::color;
use crate::text::{Rgb, hex_color, lerp_colors};

/// How wide every bar is, in glyphs.
const WIDTH: usize = 25;

/// The block the bar is drawn out of.
const GLYPH: &str = "▋";

/// Where the value sits relative to the bar.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Layout {
	/// Label, bar, value: the default.
	Split,
	/// Label, value, bar: used where the number matters more than the fill.
	AllLeft,
}

/// How the filled part of a bar is coloured.
enum Fill {
	Solid(Rgb),
	/// Stops the fill blends through, sliced to however full the bar is.
	Gradient(Vec<Rgb>),
}

/// One bar, mid-build.
struct ProgressBar<'a> {
	min: f64,
	max: f64,
	value: f64,
	label: &'a str,
	value_text: String,
	fill: Fill,
	/// Whether the value takes the fill's colour at the bar's own position.
	value_from_fill: bool,
	layout: Layout,
}

impl<'a> ProgressBar<'a> {
	/// A bar over a range, with the text that stands for its value.
	fn metric(label: &'a str, min: f64, max: f64, value: f64, value_text: String) -> Self {
		Self {
			min,
			max,
			value,
			label,
			value_text,
			fill: Fill::Solid(rgb(color::SUCCESS_500)),
			value_from_fill: false,
			layout: Layout::Split,
		}
	}

	fn gradient(mut self, stops: &[&str]) -> Self {
		self.fill = Fill::Gradient(stops.iter().map(|stop| rgb(stop)).collect());
		self.value_from_fill = true;

		self
	}

	fn all_left(mut self) -> Self {
		self.layout = Layout::AllLeft;

		self
	}

	/// How far along the range the value sits, as a percentage.
	///
	/// The JVM measures from zero rather than from `min`, which is why a bar
	/// whose range starts above zero still reads as partly full.
	fn percent(&self) -> f64 {
		let low = self.min.min(self.max);
		let high = self.min.max(self.max);
		let span = high.max(0.0);

		if span <= 0.0 {
			return 0.0;
		}

		clamp_percent((self.value.clamp(low, high).max(0.0) / span) * 100.0)
	}

	/// The colour the value text takes.
	fn value_color(&self) -> Rgb {
		match &self.fill {
			Fill::Gradient(stops) if self.value_from_fill => {
				lerp_colors(stops, self.percent() / 100.0)
			}
			_ => rgb(color::NEUTRAL_50),
		}
	}

	/// The bar itself: a filled run, then an empty one.
	fn bar(&self) -> String {
		let percent = self.percent();
		let filled = ((percent / 100.0) * WIDTH as f64).round() as usize;
		let filled = filled.min(WIDTH);
		let empty = WIDTH - filled;
		let mut out = String::new();

		if filled > 0 {
			match &self.fill {
				Fill::Solid(solid) => {
					out.push_str(&color_tag(*solid));
					out.push_str(&GLYPH.repeat(filled));
					out.push_str("</c>");
				}
				Fill::Gradient(stops) => {
					// the gradient is squeezed into the filled part rather than
					// drawn across the whole width, so a half-full bar ends
					// halfway through the ramp instead of at its last stop
					let ratio = filled as f64 / WIDTH as f64;

					out.push_str(&gradient_tag(&slice_gradient(stops, ratio)));
					out.push_str(&GLYPH.repeat(filled));
					out.push_str("</gradient>");
				}
			}
		}

		if empty > 0 {
			out.push_str(&color_tag(rgb(color::NEUTRAL_700)));
			out.push_str(&GLYPH.repeat(empty));
			out.push_str("</c>");
		}

		out
	}

	/// The whole line, with its parts in the layout's order.
	fn render(self) -> String {
		self.render_with(&colored(&self.value_text, self.value_color()))
	}

	/// The whole line, with the value drawn by the caller.
	fn render_with(&self, value: &str) -> String {
		let label = colored(self.label, rgb(color::NEUTRAL_300));
		let bar = self.bar();

		let parts = match self.layout {
			Layout::Split => [label, bar, value.to_owned()],
			Layout::AllLeft => [label, value.to_owned(), bar],
		};

		parts
			.iter()
			.filter(|part| !part.trim().is_empty())
			.cloned()
			.collect::<Vec<String>>()
			.join(" ")
	}
}

/// Ticks per second, against a full scale of twenty.
///
/// The ramp runs the other way from the rest: a *high* number is the good one,
/// so the gradient starts at danger and ends at success.
#[must_use]
pub fn tps(label: &str, tps: f64) -> String {
	ProgressBar::metric(label, 0.0, 20.0, tps.max(0.0), format!("{:.1}", tps.max(0.0)))
		.gradient(&[color::DANGER_500, color::WARNING_300, color::SUCCESS_500])
		.all_left()
		.render()
}

/// Processor load, where a high number is the bad one.
#[must_use]
pub fn cpu(label: &str, percent: f64) -> String {
	let clamped = clamp_percent(percent);

	ProgressBar::metric(label, 0.0, 100.0, clamped, format!("{clamped:.1}%"))
		.gradient(&[color::SUCCESS_500, color::WARNING_300, color::DANGER_500])
		.render()
}

/// Memory, which says both its share and its two absolute numbers.
#[must_use]
pub fn ram(label: &str, used_bytes: u64, max_bytes: u64) -> String {
	let used = used_bytes as f64;
	let max = max_bytes as f64;
	let percent = if max <= 0.0 {
		0.0
	} else {
		clamp_percent((used * 100.0) / max)
	};

	let bar = ProgressBar::metric(label, 0.0, max, used, String::new()).gradient(&[
		color::SKY_300,
		color::INFO_500,
		color::VIOLET_500,
	]);

	let value = format!(
		"{} <gray>({} / {:.0}mb)</gray>",
		colored(&format!("{percent:.1}%"), bar.value_color()),
		colored(&format!("{:.0}mb", used / 1024.0 / 1024.0), bar.value_color()),
		max / 1024.0 / 1024.0
	);

	bar.render_with(&value)
}

/// Round-trip time, against a window past which it is simply bad.
#[must_use]
pub fn latency(label: &str, millis: f64) -> String {
	let safe = millis.max(0.0);

	ProgressBar::metric(label, 0.0, 250.0, safe, format!("{safe:.0}ms"))
		.gradient(&[color::SUCCESS_500, color::WARNING_300, color::DANGER_500])
		.render()
}

/// The stops a partly-full gradient bar is drawn with.
///
/// A bar that is `ratio` full shows only the first `ratio` of the ramp, so the
/// colour at its end says how full it is rather than always being the last
/// stop. The samples are re-lerped rather than sliced, because the stops are
/// not evenly spaced once the end moves.
fn slice_gradient(stops: &[Rgb], end_ratio: f64) -> Vec<Rgb> {
	let ratio = end_ratio.clamp(0.0, 1.0);
	let samples = stops.len().max(2);

	(0..samples)
		.map(|index| {
			let along = index as f64 / (samples - 1) as f64;

			lerp_colors(stops, along * ratio)
		})
		.collect()
}

fn color_tag(color: Rgb) -> String {
	format!("<c:{}>", hex(color))
}

fn gradient_tag(stops: &[Rgb]) -> String {
	let mut tag = String::from("<gradient");

	for stop in stops {
		tag.push(':');
		tag.push_str(&hex(*stop));
	}

	tag.push('>');

	tag
}

fn colored(text: &str, color: Rgb) -> String {
	if text.is_empty() {
		return String::new();
	}

	format!("{}{text}</c>", color_tag(color))
}

fn hex(color: Rgb) -> String {
	format!("#{:02x}{:02x}{:02x}", color.0, color.1, color.2)
}

/// A palette entry, which is a hex string by the time it reaches here.
fn rgb(value: &str) -> Rgb {
	hex_color(value).unwrap_or((0xff, 0xff, 0xff))
}

fn clamp_percent(value: f64) -> f64 {
	if !value.is_finite() {
		return 0.0;
	}

	value.clamp(0.0, 100.0)
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::text::{parse, plain_text};

	/// The glyph count is what a player sees, so it is what is asserted; the
	/// colours are checked through the parser rather than by matching markup.
	fn glyphs(bar: &str) -> usize {
		plain_text(bar).matches(GLYPH).count()
	}

	#[test]
	fn a_bar_is_always_the_same_width() {
		assert_eq!(glyphs(&cpu("CPU", 0.0)), WIDTH);
		assert_eq!(glyphs(&cpu("CPU", 37.5)), WIDTH);
		assert_eq!(glyphs(&cpu("CPU", 100.0)), WIDTH);
	}

	#[test]
	fn the_reading_is_the_number_the_caller_gave() {
		assert!(plain_text(&cpu("CPU", 42.4)).contains("42.4%"));
		assert!(plain_text(&latency("Latency", 17.6)).contains("18ms"));
		assert!(plain_text(&tps("TPS", 19.97)).contains("20.0"));
	}

	/// TPS reads the other way round: twenty is a full bar, not an alarming one.
	#[test]
	fn a_full_tps_bar_is_the_good_end_of_the_ramp() {
		let full = parse(&tps("TPS", 20.0));
		let empty = parse(&tps("TPS", 0.0));

		assert_eq!(glyphs(&tps("TPS", 20.0)), WIDTH);
		// the value sits at the success end when the bar is full, and there is
		// no fill at all to take a colour from when it is empty
		assert!(full.iter().any(|span| span.color == Some(rgb(color::SUCCESS_500))));
		assert!(!empty.iter().any(|span| span.color == Some(rgb(color::SUCCESS_500))));
	}

	#[test]
	fn the_label_and_the_value_frame_the_bar() {
		let line = plain_text(&cpu("CPU", 50.0));

		assert!(line.starts_with("CPU "));
		assert!(line.ends_with("50.0%"));
	}

	/// TPS is the one preset that puts its number before the bar.
	#[test]
	fn the_tps_preset_puts_its_value_on_the_left() {
		let line = plain_text(&tps("TPS", 20.0));

		assert!(line.starts_with("TPS 20.0 "));
	}

	#[test]
	fn memory_says_both_its_share_and_its_two_numbers() {
		let line = plain_text(&ram("RAM", 512 * 1024 * 1024, 2048 * 1024 * 1024));

		assert!(line.contains("25.0%"));
		assert!(line.contains("(512mb / 2048mb)"));
	}

	/// A machine that has not reported its memory must read as empty rather
	/// than as a division by zero.
	#[test]
	fn memory_with_no_maximum_is_empty_rather_than_nan() {
		let line = plain_text(&ram("RAM", 0, 0));

		assert!(line.contains("0.0%"));
		assert!(line.contains("(0mb / 0mb)"));
	}

	#[test]
	fn a_reading_past_the_scale_is_clamped_to_it() {
		assert_eq!(glyphs(&cpu("CPU", 400.0)), WIDTH);
		assert!(plain_text(&cpu("CPU", 400.0)).contains("100.0%"));
		assert!(plain_text(&cpu("CPU", -20.0)).contains("0.0%"));
	}

	/// A half-full bar ends halfway through the ramp, not at its last stop:
	/// otherwise every bar past the first glyph would read as fully alarming.
	#[test]
	fn a_partly_full_bar_only_shows_part_of_the_ramp() {
		let stops = [(0x00, 0x00, 0x00), (0xff, 0xff, 0xff)];
		let half = slice_gradient(&stops, 0.5);

		assert_eq!(half.first().copied(), Some((0x00, 0x00, 0x00)));
		assert_eq!(half.last().copied(), Some((0x80, 0x80, 0x80)));
	}

	/// Everything a bar emits has to survive the reader on the other side, or
	/// the tags reach the player as text.
	#[test]
	fn every_preset_parses_back_without_leaving_markup() {
		for bar in [
			tps("TPS", 12.5),
			cpu("CPU", 61.0),
			ram("RAM", 900 * 1024 * 1024, 4096 * 1024 * 1024),
			latency("Latency", 42.0),
		] {
			let read = plain_text(&bar);

			assert!(!read.contains('<'), "markup leaked into: {read}");
			assert!(!read.contains('>'), "markup leaked into: {read}");
		}
	}
}
