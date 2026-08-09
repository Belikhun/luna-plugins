//! Styled text, and the slice of MiniMessage luna's configs are written in.
//!
//! Every luna plugin on the JVM writes its operator-facing strings as
//! MiniMessage, because Adventure parses it for free. Pumpkin's text component
//! has the same styling underneath - coloured runs, bold, italic - but nothing
//! that reads the markup, so a config copied across from a Paper backend would
//! reach the player with `<yellow>` still in it.
//!
//! This is that reader. It is deliberately a subset: colours (named, `<#hex>`
//! and `<color:…>`), decorations, and `<reset>`. Tags it recognises but cannot
//! draw - `<click:…>`, `<hover:…>` - are consumed and their content kept.
//!
//! **A tag it does not recognise is text**, which is Adventure's own rule and
//! not a fallback. luna's strings depend on it: the login prompt reads
//! `Dùng <white>/login <mật_khẩu></white>`, where `<mật_khẩu>` is a placeholder
//! the player is meant to see. Dropping it leaves a hole in the sentence.
//!
//! The output is data, not a component. A `TextComponent` is a WIT resource -
//! owned, not clonable, consumed when handed to the host - so a message meant
//! for several players has to be held as its runs and rendered once per
//! recipient.

/// An RGB colour, as the palette spells them.
pub type Rgb = (u8, u8, u8);

/// One run of text, and how it is drawn.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Span {
	pub text: String,
	/// Absent means "whatever the client defaults to", not black.
	pub color: Option<Rgb>,
	pub bold: bool,
	pub italic: bool,
	pub underlined: bool,
	pub strikethrough: bool,
	pub obfuscated: bool,
}

impl Span {
	/// A run in one colour and nothing else, which is what most lines are.
	#[must_use]
	pub fn colored(text: impl Into<String>, color: Rgb) -> Self {
		Self {
			text: text.into(),
			color: Some(color),
			..Self::default()
		}
	}

	/// A run in the client's own colour.
	#[must_use]
	pub fn plain(text: impl Into<String>) -> Self {
		Self {
			text: text.into(),
			..Self::default()
		}
	}
}

/// Minecraft's sixteen legacy colours, under the names MiniMessage uses.
const NAMED: &[(&str, Rgb)] = &[
	("black", (0x00, 0x00, 0x00)),
	("dark_blue", (0x00, 0x00, 0xaa)),
	("dark_green", (0x00, 0xaa, 0x00)),
	("dark_aqua", (0x00, 0xaa, 0xaa)),
	("dark_red", (0xaa, 0x00, 0x00)),
	("dark_purple", (0xaa, 0x00, 0xaa)),
	("gold", (0xff, 0xaa, 0x00)),
	("gray", (0xaa, 0xaa, 0xaa)),
	("grey", (0xaa, 0xaa, 0xaa)),
	("dark_gray", (0x55, 0x55, 0x55)),
	("dark_grey", (0x55, 0x55, 0x55)),
	("blue", (0x55, 0x55, 0xff)),
	("green", (0x55, 0xff, 0x55)),
	("aqua", (0x55, 0xff, 0xff)),
	("red", (0xff, 0x55, 0x55)),
	("light_purple", (0xff, 0x55, 0xff)),
	("yellow", (0xff, 0xff, 0x55)),
	("white", (0xff, 0xff, 0xff)),
];

/// A colour by its MiniMessage name.
#[must_use]
pub fn named_color(name: &str) -> Option<Rgb> {
	NAMED
		.iter()
		.find(|(known, _)| *known == name)
		.map(|(_, color)| *color)
}

/// `#rrggbb`, with or without the hash.
#[must_use]
pub fn hex_color(value: &str) -> Option<Rgb> {
	let digits = value.strip_prefix('#').unwrap_or(value);

	if digits.len() != 6 || !digits.bytes().all(|byte| byte.is_ascii_hexdigit()) {
		return None;
	}

	let channel = |at: usize| u8::from_str_radix(&digits[at..at + 2], 16).ok();

	Some((channel(0)?, channel(2)?, channel(4)?))
}

/// Any colour a tag can name: `red`, `#ff0000`, `color:#ff0000` or `c:#ff0000`.
///
/// `c:` is MiniMessage's own short form, and it is the one the progress bars
/// are written in, so a bar built by `progress_bar` reads back through here.
fn any_color(value: &str) -> Option<Rgb> {
	let body = value
		.strip_prefix("color:")
		.or_else(|| value.strip_prefix("colour:"))
		.or_else(|| value.strip_prefix("c:"))
		.unwrap_or(value);

	hex_color(body).or_else(|| named_color(body))
}

/// Blend a list of stops, `ratio` running 0 at the first and 1 at the last.
///
/// This is Adventure's `TextColor.lerp` walked over several stops, which is
/// what both `<gradient:…>` and the progress bars' fill colour are: plain
/// linear interpolation per channel, rounded.
#[must_use]
pub fn lerp_colors(stops: &[Rgb], ratio: f64) -> Rgb {
	let clamped = ratio.clamp(0.0, 1.0);

	match stops {
		[] => (0xff, 0xff, 0xff),
		[only] => *only,
		_ => {
			let position = clamped * (stops.len() - 1) as f64;
			let low = position.floor() as usize;
			let high = (position.ceil() as usize).min(stops.len() - 1);
			let local = position - low as f64;

			let blend = |start: u8, end: u8| -> u8 {
				(f64::from(start) + local * (f64::from(end) - f64::from(start))).round() as u8
			};

			let (start, end) = (stops[low], stops[high]);

			(
				blend(start.0, end.0),
				blend(start.1, end.1),
				blend(start.2, end.2),
			)
		}
	}
}

/// What a pushed tag changed, so a closing tag knows what to undo.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
	Color,
	Bold,
	Italic,
	Underlined,
	Strikethrough,
	Obfuscated,
}

/// The style in force, and the tag stack that got there.
#[derive(Debug, Clone, Copy, Default)]
struct Style {
	color: Option<Rgb>,
	bold: bool,
	italic: bool,
	underlined: bool,
	strikethrough: bool,
	obfuscated: bool,
}

/// Read a MiniMessage string into the runs it describes.
///
/// Runs are merged as they are built, so a string with no markup comes back as
/// exactly one span and an empty string as none at all.
///
/// Unclosed tags are fine - they simply reach the end of the line - and a
/// closing tag with nothing to close is ignored, which is what makes a config
/// with one typo in it degrade to slightly-wrong colour rather than to garbage.
#[must_use]
pub fn parse(input: &str) -> Vec<Span> {
	let mut spans: Vec<Span> = Vec::new();
	let mut stack: Vec<(Kind, Style)> = Vec::new();
	let mut gradients: Vec<Gradient> = Vec::new();
	let mut style = Style::default();
	let mut text = String::new();
	let mut chars = input.chars().peekable();

	while let Some(current) = chars.next() {
		// MiniMessage's own escape, so a message may talk about a tag
		if current == '\\' && chars.peek() == Some(&'<') {
			text.push('<');
			chars.next();

			continue;
		}

		if current != '<' {
			text.push(current);

			continue;
		}

		let mut tag = String::new();
		let mut closed = false;
		let mut quoted = false;

		for inner in chars.by_ref() {
			// A tag argument may be quoted, and what is quoted may itself be
			// markup: `<hover:show_text:'<red>boom</red>'>` is one tag, not a
			// tag ending at the first `>`. Stopping there would spill the hover
			// text into the line the player reads.
			if inner == '\'' {
				quoted = !quoted;
			}

			if inner == '>' && !quoted {
				closed = true;

				break;
			}

			tag.push(inner);
		}

		if !closed {
			// an unterminated `<` is text, not a tag anybody meant to write
			text.push('<');
			text.push_str(&tag);

			break;
		}

		let lowered = tag.trim().to_ascii_lowercase();

		// A gradient is the one tag whose colour depends on how much text it
		// wraps, so it cannot be decided here: the stops are remembered, the run
		// is drawn in the first of them, and the whole region is repainted per
		// character once the closing tag says how long it turned out to be.
		if let Some(stops) = gradient_stops(&lowered) {
			push_run(&mut spans, &style, &mut text);
			stack.push((Kind::Color, style));
			style.color = stops.first().copied();
			gradients.push(Gradient {
				stops,
				start: spans.len(),
			});

			continue;
		}

		if lowered == "/gradient"
			&& let Some(gradient) = gradients.pop()
		{
			push_run(&mut spans, &style, &mut text);
			paint_gradient(&mut spans, gradient.start, &gradient.stops);
		}

		match apply(&lowered, &mut stack, style) {
			Outcome::Restyled(next) => {
				push_run(&mut spans, &style, &mut text);
				style = next;
			}
			Outcome::Inert => {}
			Outcome::NotATag => {
				// Adventure's rule: what it does not recognise, it does not
				// touch. `<mật_khẩu>` is a placeholder a player reads, not
				// markup, and deleting it leaves a gap in the sentence.
				text.push('<');
				text.push_str(&tag);
				text.push('>');
			}
		}
	}

	push_run(&mut spans, &style, &mut text);

	// an unclosed gradient still runs to the end of the line, exactly as an
	// unclosed colour does
	while let Some(gradient) = gradients.pop() {
		paint_gradient(&mut spans, gradient.start, &gradient.stops);
	}

	spans
}

/// A gradient that has opened: its stops, and where its text starts.
struct Gradient {
	stops: Vec<Rgb>,
	start: usize,
}

/// The stops a `<gradient:…>` tag names, if it is one.
fn gradient_stops(tag: &str) -> Option<Vec<Rgb>> {
	if tag == "gradient" {
		// Adventure's default when the tag names nothing
		return Some(vec![(0xff, 0xff, 0xff), (0x00, 0x00, 0x00)]);
	}

	let body = tag.strip_prefix("gradient:")?;

	// a trailing argument may be the phase, which is a number rather than a
	// stop, so anything that is not a colour is simply not one
	let stops: Vec<Rgb> = body.split(':').filter_map(any_color).collect();

	if stops.is_empty() {
		return None;
	}

	Some(stops)
}

/// Repaint every character of a finished gradient region.
///
/// The runs are rebuilt one character at a time and merged back, so a gradient
/// over a word of one colour costs a span per character while a two-stop
/// gradient over a long bar of the same colour costs one.
fn paint_gradient(spans: &mut Vec<Span>, start: usize, stops: &[Rgb]) {
	if start >= spans.len() {
		return;
	}

	let region = spans.split_off(start);
	let total: usize = region.iter().map(|span| span.text.chars().count()).sum();
	let mut index = 0;

	for span in region {
		for character in span.text.chars() {
			let ratio = if total > 1 {
				index as f64 / (total - 1) as f64
			} else {
				0.0
			};

			push_span(
				spans,
				Span {
					text: character.to_string(),
					color: Some(lerp_colors(stops, ratio)),
					..span.clone()
				},
			);

			index += 1;
		}
	}
}

/// What a tag turned out to be.
enum Outcome {
	/// Recognised, and it changed how the following text is drawn.
	Restyled(Style),
	/// Recognised, and nothing here can draw it; its content still counts.
	Inert,
	/// Not markup at all, so it belongs in the sentence.
	NotATag,
}

/// Decide what one tag is, and what it does to the style.
fn apply(tag: &str, stack: &mut Vec<(Kind, Style)>, style: Style) -> Outcome {
	if let Some(name) = tag.strip_prefix('/') {
		let name = name.trim();

		if name.is_empty() || is_inert_tag(name) {
			return Outcome::Inert;
		}

		return close(name, stack, style);
	}

	if tag == "reset" || tag == "r" {
		stack.clear();

		return Outcome::Restyled(Style::default());
	}

	// `<!i>` turns italic off rather than on; item lore is written with it,
	// because a client italicises a custom name unless told not to
	if let Some(name) = tag.strip_prefix('!') {
		let Some(kind) = decoration(name.trim()) else {
			return Outcome::NotATag;
		};

		let mut next = style;

		match kind {
			Kind::Bold => next.bold = false,
			Kind::Italic => next.italic = false,
			Kind::Underlined => next.underlined = false,
			Kind::Strikethrough => next.strikethrough = false,
			Kind::Obfuscated => next.obfuscated = false,
			Kind::Color => return Outcome::NotATag,
		}

		return Outcome::Restyled(next);
	}

	if let Some(kind) = decoration(tag) {
		let mut next = style;

		match kind {
			Kind::Bold => next.bold = true,
			Kind::Italic => next.italic = true,
			Kind::Underlined => next.underlined = true,
			Kind::Strikethrough => next.strikethrough = true,
			Kind::Obfuscated => next.obfuscated = true,
			// a bare `<color>` names no colour, so there is nothing to open
			Kind::Color => return Outcome::NotATag,
		}

		stack.push((kind, style));

		return Outcome::Restyled(next);
	}

	if is_inert_tag(tag) {
		return Outcome::Inert;
	}

	let Some(color) = any_color(tag) else {
		return Outcome::NotATag;
	};

	let mut next = style;

	next.color = Some(color);
	stack.push((Kind::Color, style));

	Outcome::Restyled(next)
}

/// Undo the nearest matching tag.
fn close(name: &str, stack: &mut Vec<(Kind, Style)>, style: Style) -> Outcome {
	// `</gradient>` and `</c>` close what their opening form opened, which is a
	// colour; neither name carries one of its own, so neither can be matched
	// like one
	let recognised = if name == "gradient" || name == "c" {
		Some(Kind::Color)
	} else {
		decoration(name).or_else(|| any_color(name).map(|_| Kind::Color))
	};

	let Some(wanted) = recognised else {
		// `</mật_khẩu>` closes nothing because it opened nothing; it is text
		return Outcome::NotATag;
	};

	// `</green>` and `</color>` both mean "back to whatever colour was in force",
	// so the match is on what the tag changed rather than on its exact spelling
	let Some(at) = stack.iter().rposition(|(kind, _)| *kind == wanted) else {
		// recognised, but nothing of that kind is open: a stray close, which
		// Adventure also swallows rather than drawing
		return Outcome::Inert;
	};

	let (_, before) = stack[at];

	stack.truncate(at);

	// only the closed tag's own effect is undone; a colour opened inside a
	// `<b>` must not be reverted by closing the bold
	let mut next = style;

	match wanted {
		Kind::Color => next.color = before.color,
		Kind::Bold => next.bold = before.bold,
		Kind::Italic => next.italic = before.italic,
		Kind::Underlined => next.underlined = before.underlined,
		Kind::Strikethrough => next.strikethrough = before.strikethrough,
		Kind::Obfuscated => next.obfuscated = before.obfuscated,
	}

	Outcome::Restyled(next)
}

/// The decoration a tag name turns on, if it is one.
fn decoration(name: &str) -> Option<Kind> {
	match name {
		"b" | "bold" => Some(Kind::Bold),
		"i" | "italic" | "em" => Some(Kind::Italic),
		"u" | "underlined" | "underline" => Some(Kind::Underlined),
		"st" | "strikethrough" => Some(Kind::Strikethrough),
		"obf" | "obfuscated" => Some(Kind::Obfuscated),
		"color" | "colour" => Some(Kind::Color),
		_ => None,
	}
}

/// Tags Adventure knows that carry no colour or decoration of their own.
///
/// They are consumed rather than shown - their content is what matters - even
/// though nothing here can act on them. Recognising them is what keeps a
/// `<click:…>` out of the sentence while leaving `<mật_khẩu>` in it.
fn is_inert_tag(tag: &str) -> bool {
	// the name is what identifies a tag; the arguments after the first colon
	// vary, and the closing form (`</hover>`) carries none at all
	let name = tag.split(':').next().unwrap_or(tag);

	const NAMES: &[&str] = &[
		"reset",
		"r",
		"rainbow",
		"pride",
		"newline",
		"br",
		"click",
		"hover",
		"insert",
		"insertion",
		"font",
		"key",
		"keybind",
		"lang",
		"tr",
		"translate",
		"selector",
		"sel",
		"score",
		"nbt",
		"shadow_color",
		"shadow",
		"transition",
	];

	NAMES.contains(&name)
}

/// Close off the run built so far, dropping it when it is empty.
fn push_run(spans: &mut Vec<Span>, style: &Style, text: &mut String) {
	if text.is_empty() {
		return;
	}

	push_span(
		spans,
		Span {
			text: std::mem::take(text),
			color: style.color,
			bold: style.bold,
			italic: style.italic,
			underlined: style.underlined,
			strikethrough: style.strikethrough,
			obfuscated: style.obfuscated,
		},
	);
}

/// Add a run, folding it into the previous one when they look the same.
///
/// A tag that changed nothing visible should not split the line in two, and a
/// gradient's per-character runs should collapse wherever the colour repeats.
fn push_span(spans: &mut Vec<Span>, span: Span) {
	if let Some(last) = spans.last_mut()
		&& last.color == span.color
		&& last.bold == span.bold
		&& last.italic == span.italic
		&& last.underlined == span.underlined
		&& last.strikethrough == span.strikethrough
		&& last.obfuscated == span.obfuscated
	{
		last.text.push_str(&span.text);

		return;
	}

	spans.push(span);
}

/// Everything a line says, with the markup taken out.
///
/// This is what a log line or a plain-text sink wants; it is the same parse, so
/// it can never disagree with what a player is shown.
#[must_use]
pub fn plain_text(input: &str) -> String {
	parse(input).into_iter().map(|span| span.text).collect()
}

#[cfg(test)]
mod tests {
	use super::*;

	const YELLOW: Rgb = (0xff, 0xff, 0x55);
	const WHITE: Rgb = (0xff, 0xff, 0xff);

	#[test]
	fn text_with_no_markup_is_one_run() {
		assert_eq!(parse("Đang kiểm tra"), vec![Span::plain("Đang kiểm tra")]);
	}

	#[test]
	fn an_empty_string_has_no_runs() {
		assert!(parse("").is_empty());
	}

	#[test]
	fn a_named_colour_opens_and_closes() {
		let spans = parse("<yellow>chờ</yellow> chút");

		assert_eq!(
			spans,
			vec![Span::colored("chờ", YELLOW), Span::plain(" chút")]
		);
	}

	#[test]
	fn both_hex_spellings_are_the_same_colour() {
		assert_eq!(parse("<#ffff55>a"), parse("<color:#ffff55>a"));
		assert_eq!(parse("<#ffff55>a"), vec![Span::colored("a", YELLOW)]);
	}

	#[test]
	fn a_decoration_nests_inside_a_colour() {
		let spans = parse("<white><b>Vui lòng</b> đăng nhập</white>");

		assert_eq!(
			spans,
			vec![
				Span {
					text: "Vui lòng".to_owned(),
					color: Some(WHITE),
					bold: true,
					..Span::default()
				},
				Span::colored(" đăng nhập", WHITE),
			]
		);
	}

	/// Closing a decoration must leave a colour opened inside it alone; getting
	/// this wrong is how the rest of a line silently loses its colour.
	#[test]
	fn closing_a_decoration_keeps_a_colour_opened_within_it() {
		let spans = parse("<b><yellow>a</b>b");

		assert_eq!(
			spans,
			vec![
				Span {
					text: "a".to_owned(),
					color: Some(YELLOW),
					bold: true,
					..Span::default()
				},
				Span::colored("b", YELLOW),
			]
		);
	}

	#[test]
	fn reset_drops_everything_at_once() {
		let spans = parse("<yellow><b>a<reset>b");

		assert_eq!(
			spans,
			vec![
				Span {
					text: "a".to_owned(),
					color: Some(YELLOW),
					bold: true,
					..Span::default()
				},
				Span::plain("b"),
			]
		);
	}

	#[test]
	fn bang_italic_turns_italic_off() {
		let spans = parse("<i>a<!i>b");

		assert_eq!(
			spans,
			vec![
				Span {
					text: "a".to_owned(),
					italic: true,
					..Span::default()
				},
				Span::plain("b"),
			]
		);
	}

	/// A gradient runs across its own text, so the ends are its stops and every
	/// character between them is a step. Drawing it as one flat colour - which
	/// this used to do - turns the dashboard's progress bars into solid blocks.
	#[test]
	fn a_gradient_runs_across_the_text_it_wraps() {
		let spans = parse("<gradient:#ff0000:#0000ff>abcde</gradient>");

		assert_eq!(spans.len(), 5);
		assert_eq!(plain_text("<gradient:#ff0000:#0000ff>abcde</gradient>"), "abcde");
		assert_eq!(spans[0].color, Some((0xff, 0x00, 0x00)));
		assert_eq!(spans[2].color, Some((0x80, 0x00, 0x80)));
		assert_eq!(spans[4].color, Some((0x00, 0x00, 0xff)));
	}

	#[test]
	fn a_gradient_with_three_stops_passes_through_the_middle_one() {
		let spans = parse("<gradient:#ff0000:#00ff00:#0000ff>abc</gradient>");

		assert_eq!(spans[1].color, Some((0x00, 0xff, 0x00)));
	}

	/// One character has nowhere to travel, so it takes the first stop.
	#[test]
	fn a_gradient_over_one_character_is_its_first_stop() {
		assert_eq!(
			parse("<gradient:#6dffd4:#4ea3ff>◈</gradient>"),
			vec![Span::colored("◈", (0x6d, 0xff, 0xd4))]
		);
	}

	#[test]
	fn a_gradient_that_is_never_closed_still_runs_to_the_end() {
		let spans = parse("<gradient:#ff0000:#0000ff>abcde");

		assert_eq!(spans.len(), 5);
		assert_eq!(spans[4].color, Some((0x00, 0x00, 0xff)));
	}

	/// A gradient keeps whatever else was in force; only the colour is its own.
	#[test]
	fn a_gradient_leaves_the_other_decorations_alone() {
		let spans = parse("<b><gradient:#ff0000:#0000ff>ab</gradient></b>");

		assert!(spans.iter().all(|span| span.bold));
	}

	/// `<c:…>` is the short spelling the progress bars are written in.
	#[test]
	fn the_short_colour_tag_is_the_same_as_the_long_one() {
		assert_eq!(parse("<c:#ffff55>a</c>b"), parse("<color:#ffff55>a</color>b"));
	}

	#[test]
	fn colours_blend_per_channel_between_stops() {
		let stops = [(0x00, 0x00, 0x00), (0xff, 0xff, 0xff)];

		assert_eq!(lerp_colors(&stops, 0.0), (0x00, 0x00, 0x00));
		assert_eq!(lerp_colors(&stops, 0.5), (0x80, 0x80, 0x80));
		assert_eq!(lerp_colors(&stops, 1.0), (0xff, 0xff, 0xff));
		// out of range is clamped rather than extrapolated
		assert_eq!(lerp_colors(&stops, 2.0), (0xff, 0xff, 0xff));
	}

	/// A tag Adventure knows but this cannot draw is still swallowed; only its
	/// content reaches the player.
	#[test]
	fn a_recognised_but_undrawable_tag_leaves_only_its_content() {
		assert_eq!(parse("a<hover:show_text:'x'>b</hover>"), vec![Span::plain("ab")]);
		assert_eq!(parse("a<click:run_command:'/x'>b</click>"), vec![Span::plain("ab")]);
	}

	/// The regression this rule exists for. luna's own prompts carry
	/// `<mật_khẩu>` as a placeholder the player reads; dropping it left
	/// `Dùng /register   để tạo tài khoản` with the gap still in it.
	#[test]
	fn a_placeholder_that_is_not_a_tag_stays_in_the_sentence() {
		let register = plain_text(
			"<yellow>Dùng <white>/register <mật_khẩu> <nhập_lại></white> để tạo tài khoản</yellow>",
		);
		let login = plain_text("<yellow>Dùng <white>/login <mật_khẩu></white> để đăng nhập</yellow>");

		assert_eq!(register, "Dùng /register <mật_khẩu> <nhập_lại> để tạo tài khoản");
		assert_eq!(login, "Dùng /login <mật_khẩu> để đăng nhập");
	}

	/// A closing tag for something that was never markup is text as well, or
	/// the sentence loses the other half of its placeholder.
	#[test]
	fn an_unknown_closing_tag_stays_in_the_sentence() {
		assert_eq!(plain_text("a</mật_khẩu>b"), "a</mật_khẩu>b");
	}

	#[test]
	fn strikethrough_and_obfuscated_are_carried_rather_than_swallowed() {
		let spans = parse("<st>a</st><obf>b</obf>");

		assert_eq!(spans[0].strikethrough, true);
		assert_eq!(spans[1].obfuscated, true);
	}

	/// A hover or click argument is quoted and may hold markup of its own; the
	/// whole tag has to be swallowed, not just up to the first `>` inside it.
	#[test]
	fn a_quoted_tag_argument_may_contain_markup() {
		let spans = parse("<hover:show_text:'<red>đừng</red>'>xong</hover>");

		assert_eq!(spans, vec![Span::plain("xong")]);
	}

	#[test]
	fn a_click_wrapping_a_coloured_line_keeps_only_the_line() {
		let spans = parse(
			"<click:suggest_command:'/login pw'><hover:show_text:'<color:#0ea5e9>x</color>'>\
			 <yellow>Dùng</yellow></hover></click>",
		);

		assert_eq!(spans, vec![Span::colored("Dùng", YELLOW)]);
	}

	#[test]
	fn a_closing_tag_with_nothing_open_is_ignored() {
		assert_eq!(parse("</yellow>a"), vec![Span::plain("a")]);
	}

	#[test]
	fn an_unterminated_tag_stays_as_text() {
		assert_eq!(parse("a<yellow"), vec![Span::plain("a<yellow")]);
	}

	#[test]
	fn an_escaped_bracket_is_literal() {
		assert_eq!(parse("dùng \\<mật_khẩu>"), vec![Span::plain("dùng <mật_khẩu>")]);
	}

	#[test]
	fn runs_that_look_the_same_are_merged() {
		assert_eq!(parse("<yellow>a</yellow><yellow>b"), vec![Span::colored("ab", YELLOW)]);
	}

	#[test]
	fn plain_text_is_the_same_parse_without_the_style() {
		assert_eq!(
			plain_text("<yellow>✔ <b>Đã xác thực</b></yellow>"),
			"✔ Đã xác thực"
		);
	}

	#[test]
	fn a_hex_colour_has_to_be_six_digits() {
		assert_eq!(hex_color("#fff"), None);
		assert_eq!(hex_color("#ffff55"), Some(YELLOW));
		assert_eq!(hex_color("ffff55"), Some(YELLOW));
		assert_eq!(hex_color("#gggggg"), None);
	}
}
