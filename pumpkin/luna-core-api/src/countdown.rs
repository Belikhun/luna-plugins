//! Countdown wording and time parsing, shared with the Paper plugin.
//!
//! A countdown announced on a Pumpkin backend has to read exactly as it does on
//! a Paper one; a player moving between them should not be able to tell which
//! server drew the bar. So the two formats a countdown needs - what an operator
//! may type for a length, and how a remaining time is written - live here beside
//! the wire formats rather than being re-derived per platform.

/// An RGB colour, as the palette spells them.
///
/// Re-exported rather than redefined: the countdown's palette and a config's
/// `<color:#…>` have to be the same type, or the one renderer cannot draw both.
pub use crate::text::Rgb;

// Palette entries `LunaPalette` holds on the JVM side, by the same names.
const AMBER_300: Rgb = (0xfc, 0xd3, 0x4d);
const TEAL_300: Rgb = (0x5e, 0xea, 0xd4);
const SKY_300: Rgb = (0x7d, 0xd3, 0xfc);

/// A remaining time, written and coloured.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Readable {
	pub text: String,
	pub color: Rgb,
}

/// Write a remaining time the way `CountInstance.readableTime` does.
///
/// The three bands are the JVM's, colour included: past an hour it is coarse and
/// amber, past five minutes it is whole seconds and teal, and inside five
/// minutes it gains a decimal and turns sky blue. The decimal is the reason the
/// bar is redrawn every tick rather than every second - at one update a second
/// the tenths place would visibly stutter.
#[must_use]
pub fn readable_time(seconds: f64) -> Readable {
	if seconds / 3600.0 > 1.0 {
		return Readable {
			text: format!("{:.0}h {:.2}m", (seconds / 3600.0).floor(), (seconds % 3600.0) / 60.0),
			color: AMBER_300,
		};
	}

	if seconds > 300.0 {
		return Readable {
			text: format!("{:.0}m {:.0}s", (seconds / 60.0).floor(), seconds % 60.0),
			color: TEAL_300,
		};
	}

	Readable {
		text: format!("{seconds:.1}s"),
		color: SKY_300,
	}
}

/// Read a length an operator typed: `90`, `30s`, `5m`, `2h`, `1d`.
///
/// A bare number is seconds, which is what the JVM's `parseTime` does and what
/// the command's own suggestions offer. Anything unreadable answers `None`
/// rather than a default: silently starting a five-minute countdown because a
/// typo did not parse is worse than saying so.
#[must_use]
pub fn parse_time(input: &str) -> Option<u32> {
	let value = input.trim().to_ascii_lowercase();

	if value.is_empty() {
		return None;
	}

	let (digits, multiplier) = match value.as_bytes()[value.len() - 1] {
		b'd' => (&value[..value.len() - 1], 86_400),
		b'h' => (&value[..value.len() - 1], 3_600),
		b'm' => (&value[..value.len() - 1], 60),
		b's' => (&value[..value.len() - 1], 1),
		_ => (value.as_str(), 1),
	};

	digits
		.parse::<u32>()
		.ok()
		.and_then(|count| count.checked_mul(multiplier))
		.filter(|seconds| *seconds > 0)
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn under_five_minutes_carries_a_decimal() {
		assert_eq!(readable_time(29.94).text, "29.9s");
		assert_eq!(readable_time(29.94).color, SKY_300);
		assert_eq!(readable_time(300.0).text, "300.0s");
	}

	#[test]
	fn past_five_minutes_it_is_whole_units() {
		assert_eq!(readable_time(301.0).text, "5m 1s");
		assert_eq!(readable_time(301.0).color, TEAL_300);
	}

	#[test]
	fn past_an_hour_the_minutes_carry_the_precision() {
		assert_eq!(readable_time(5_400.0).text, "1h 30.00m");
		assert_eq!(readable_time(5_400.0).color, AMBER_300);
	}

	/// Exactly an hour is *not* past one, so it stays in the minutes band; the
	/// JVM's test is `seconds / 3600 > 1`, not `>=`.
	#[test]
	fn an_exact_hour_stays_in_the_minute_band() {
		assert_eq!(readable_time(3_600.0).text, "60m 0s");
	}

	#[test]
	fn a_bare_number_is_seconds() {
		assert_eq!(parse_time("90"), Some(90));
	}

	#[test]
	fn every_suffix_the_jvm_takes() {
		assert_eq!(parse_time("30s"), Some(30));
		assert_eq!(parse_time("5m"), Some(300));
		assert_eq!(parse_time("2h"), Some(7_200));
		assert_eq!(parse_time("1d"), Some(86_400));
		assert_eq!(parse_time(" 1D "), Some(86_400));
	}

	#[test]
	fn nonsense_is_refused_rather_than_defaulted() {
		assert_eq!(parse_time(""), None);
		assert_eq!(parse_time("soon"), None);
		assert_eq!(parse_time("-5"), None);
		assert_eq!(parse_time("0"), None);
		assert_eq!(parse_time("s"), None);
	}

	#[test]
	fn an_absurd_length_does_not_wrap() {
		assert_eq!(parse_time("999999999d"), None);
	}
}
