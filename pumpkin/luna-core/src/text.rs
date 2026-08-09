//! Turning styled runs into the component the host draws.
//!
//! There is one renderer because a `TextComponent` is a WIT resource: it is
//! owned, it cannot be cloned, and handing it to the host consumes it. A line
//! meant for a boss bar and for chat, or for every player on the server, has to
//! be held as [`Span`]s and built again per use. Every module that says
//! something to a player goes through here, so the countdown's palette and a
//! config's MiniMessage end up drawn the same way.

use luna_core_api::text::{Span, parse};
use pumpkin_plugin_api::player::Player;
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::{Server, common::RgbColor};

/// Build a component from the runs. One per use; see the module note.
#[must_use]
pub fn render(spans: &[Span]) -> TextComponent {
	let root = TextComponent::text("");

	for span in spans {
		let run = TextComponent::text(&span.text);

		if let Some((red, green, blue)) = span.color {
			run.color_rgb(RgbColor {
				r: red,
				g: green,
				b: blue,
			});
		}

		if span.bold {
			run.bold(true);
		}

		if span.underlined {
			run.underlined(true);
		}

		if span.strikethrough {
			run.strikethrough(true);
		}

		if span.obfuscated {
			run.obfuscated(true);
		}

		// always written, never only when true: a custom item name is italic
		// unless the component says otherwise, which is what `<!i>` is for
		run.italic(span.italic);

		root.add_child(run);
	}

	root
}

/// Build a component from a MiniMessage string, for something shown once.
///
/// Anything shown to several players is parsed once into spans and rendered per
/// recipient instead; see the module note.
#[must_use]
pub fn markup(source: &str) -> TextComponent {
	render(&parse(source))
}

/// Say one line to a player, in chat.
pub fn tell(player: &Player, spans: &[Span]) {
	player.send_system_message(render(spans), false);
}

/// Say one MiniMessage line to a player, in chat.
pub fn tell_markup(player: &Player, source: &str) {
	player.send_system_message(markup(source), false);
}

/// Say one line to a player, over the hotbar.
pub fn overlay(player: &Player, spans: &[Span]) {
	player.show_actionbar(render(spans));
}

/// Say one MiniMessage line to a player, over the hotbar.
pub fn overlay_markup(player: &Player, source: &str) {
	player.show_actionbar(markup(source));
}

/// Say one line to everybody on this server, in chat.
pub fn broadcast(server: &Server, spans: &[Span]) {
	for player in server.get_all_players() {
		tell(&player, spans);
	}
}
