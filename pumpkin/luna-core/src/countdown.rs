//! Operator-run countdowns, drawn on a boss bar for everyone on the server.
//!
//! This is the Paper plugin's behaviour, kept deliberately: the same boss bar
//! draining from green through yellow to red, the same chat lines when one
//! starts, is cancelled or fires, and the same five seconds of lingering
//! afterwards. A player moving between backends should not be able to tell which
//! one they are on.
//!
//! Remaining time is derived from the wall clock rather than counted down per
//! tick, exactly as on the JVM. A server that stalls for two seconds still
//! finishes its countdown when it said it would, instead of drifting later by
//! however long it was busy.

use luna_core_api::countdown::{Rgb, readable_time};
use pumpkin_plugin_api::boss_bar::{BossBar, BossBarColor, BossBarDivision};
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::{Server, common::RgbColor};
use std::collections::BTreeMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// What a countdown is called when the operator does not say.
pub const DEFAULT_TITLE: &str = "Sự Kiện Kết Thúc";

/// Fraction remaining below which the bar turns yellow, then red.
const YELLOW_BELOW: f64 = 0.6;
const RED_BELOW: f64 = 0.3;

/// How long a finished or cancelled bar stays on screen before it is taken away.
const LINGER: Duration = Duration::from_secs(5);

/// When a finished bar starts draining to empty, measured from its deadline.
const DRAIN_AT: Duration = Duration::from_millis(4_800);

// Palette entries the titles use, matching the JVM's MiniMessage tags.
const GRAY: Rgb = (0xaa, 0xaa, 0xaa);
const GREEN: Rgb = (0x55, 0xff, 0x55);
const WHITE: Rgb = (0xff, 0xff, 0xff);
const LIGHT_PURPLE: Rgb = (0xff, 0x55, 0xff);

/// What phase of its life a countdown is in.
///
/// The two are separate because only one of them still counts: a bar that has
/// fired, or that an operator cancelled, is no longer a countdown anybody can
/// stop or list, but it is still on screen and still has to be taken down.
enum Phase {
	Counting,
	/// Finished or cancelled; the bar comes down at this instant.
	Leaving { at: Instant },
}

/// One countdown an operator started.
struct Running {
	title: String,
	/// Length asked for, which is what the bar's fill is a fraction of.
	total: f64,
	ends_at: Instant,
	bar: BossBar,
	/// Last colour pushed, so an unchanged one is not re-sent every tick.
	color: BossBarColor,
	phase: Phase,
}

impl Running {
	/// Seconds left, negative once the deadline has passed.
	fn remaining(&self) -> f64 {
		let now = Instant::now();

		if now < self.ends_at {
			return self.ends_at.duration_since(now).as_secs_f64();
		}

		-(now.duration_since(self.ends_at).as_secs_f64())
	}

	/// The colour the bar should be at this much of it left.
	fn color_for(progress: f64) -> BossBarColor {
		if progress < RED_BELOW {
			return BossBarColor::Red;
		}

		if progress < YELLOW_BELOW {
			return BossBarColor::Yellow;
		}

		BossBarColor::Green
	}

	/// Push a colour, but only when it actually moved.
	fn recolor(&mut self, color: BossBarColor) {
		if self.color == color {
			return;
		}

		self.color = color;
		self.bar.set_color(color);
	}
}

/// Everything currently on screen, counting or leaving.
#[derive(Default)]
pub struct Countdowns {
	running: Mutex<BTreeMap<u32, Running>>,
	next_id: Mutex<u32>,
}

impl Countdowns {
	#[must_use]
	pub fn new() -> Self {
		Self::default()
	}

	/// Start one, announce it, and return the id an operator stops it by.
	pub fn start(&self, server: &Server, title: &str, seconds: u32) -> u32 {
		let seconds = seconds.max(1);
		let title = match title.trim() {
			"" => DEFAULT_TITLE.to_owned(),
			given => given.to_owned(),
		};

		let id = {
			let mut next = self.next_id.lock().expect("countdown ids poisoned");
			*next += 1;
			*next
		};

		let bar = BossBar::new(
			TextComponent::text("CountDown"),
			BossBarColor::Green,
			BossBarDivision::NoDivision,
		);

		for player in server.get_all_players() {
			bar.add_player(player);
		}

		let countdown = Running {
			title,
			total: f64::from(seconds),
			ends_at: Instant::now() + Duration::from_secs(u64::from(seconds)),
			bar,
			color: BossBarColor::Green,
			phase: Phase::Counting,
		};

		let announcement = starting_line(&countdown.title, f64::from(seconds));

		self.running
			.lock()
			.expect("countdowns poisoned")
			.insert(id, countdown);

		broadcast(server, &announcement);

		id
	}

	/// Stop one, announcing why. False when no such countdown is counting.
	pub fn stop(&self, server: &Server, id: u32, reason: Option<&str>) -> bool {
		let title = {
			let mut running = self.running.lock().expect("countdowns poisoned");

			let Some(countdown) = running.get_mut(&id) else {
				return false;
			};

			if matches!(countdown.phase, Phase::Leaving { .. }) {
				return false;
			}

			// the bar says why it stopped and stays put for a moment; taking it
			// away the instant the command runs leaves nobody any the wiser
			countdown.bar.set_title(render(&cancelled_bar(&countdown.title, reason)));
			countdown.recolor(BossBarColor::Purple);
			countdown.phase = Phase::Leaving {
				at: Instant::now() + LINGER,
			};

			countdown.title.clone()
		};

		broadcast(server, &cancelled_line(id, &title));

		true
	}

	/// Stop every countdown still counting, announcing each.
	pub fn stop_all(&self, server: &Server, reason: Option<&str>) -> usize {
		let ids: Vec<u32> = self.active().into_iter().map(|(id, _, _)| id).collect();
		let mut stopped = 0;

		for id in ids {
			if self.stop(server, id, reason) {
				stopped += 1;
			}
		}

		stopped
	}

	/// Every countdown still counting, as `(id, title, seconds left)`.
	#[must_use]
	pub fn active(&self) -> Vec<(u32, String, f64)> {
		self.running
			.lock()
			.expect("countdowns poisoned")
			.iter()
			.filter(|(_, countdown)| matches!(countdown.phase, Phase::Counting))
			.map(|(id, countdown)| (*id, countdown.title.clone(), countdown.remaining()))
			.collect()
	}

	/// Show every bar this player is not yet on.
	///
	/// A boss bar is per viewer, so somebody who joins mid-countdown sees nothing
	/// at all unless they are added to the ones already running.
	pub fn show_to(&self, server: &Server, id: &str) {
		let running = self.running.lock().expect("countdowns poisoned");

		if running.is_empty() {
			return;
		}

		for countdown in running.values() {
			// the bar takes ownership of the handle it is given, so the roster is
			// re-read per bar rather than one handle being reused
			let Some(player) = server
				.get_all_players()
				.into_iter()
				.find(|player| player.get_id().to_string() == id)
			else {
				return;
			};

			countdown.bar.add_player(player);
		}
	}

	/// One tick of every countdown: the bar, and the ones that just fired.
	///
	/// Called from the plugin's scheduled task rather than a thread, which is
	/// what the sandbox allows; the wall-clock deadline is what keeps that from
	/// mattering to the result.
	pub fn tick(&self, server: &Server) {
		let mut finished: Vec<Line> = Vec::new();

		{
			let mut running = self.running.lock().expect("countdowns poisoned");

			running.retain(|id, countdown| {
				let remaining = countdown.remaining();

				if let Phase::Leaving { at } = countdown.phase {
					if Instant::now() >= at {
						countdown.bar.remove_all();

						return false;
					}

					// a cancelled bar keeps whatever it was left showing; only a
					// finished one still has emptying to do
					if remaining < 0.0 && countdown.color == BossBarColor::Blue {
						countdown
							.bar
							.set_health(if -remaining >= DRAIN_AT.as_secs_f64() { 0.0 } else { 1.0 });
					}

					return true;
				}

				if remaining > 0.0 {
					let progress = remaining / countdown.total;

					countdown.bar.set_health(progress as f32);
					countdown.recolor(Running::color_for(progress));
					countdown
						.bar
						.set_title(render(&counting_bar(*id, &countdown.title, remaining)));

					return true;
				}

				// it fired: the bar fills, turns blue and says so, then leaves
				countdown.recolor(BossBarColor::Blue);
				countdown.bar.set_health(1.0);
				countdown.bar.set_title(render(&finished_bar(*id, &countdown.title)));
				countdown.phase = Phase::Leaving {
					at: countdown.ends_at + LINGER,
				};

				finished.push(finished_line(*id, &countdown.title));

				true
			});
		}

		// the lock is released before anything reaches a player: a broadcast is a
		// host call per player, and holding a lock across it would put every
		// countdown behind the slowest connection
		for line in &finished {
			broadcast(server, line);
		}
	}
}

/// One coloured run of a message.
///
/// A `TextComponent` is a WIT resource: it is owned, it cannot be cloned, and
/// handing it to the host consumes it. A line therefore cannot be built once and
/// sent to everybody; it is held as its runs and rendered fresh per recipient,
/// which is also what lets the same line serve a bar title and a chat message.
type Line = Vec<(String, Rgb)>;

/// `Sự kiện <title> sẽ bắt đầu sau <time> nữa!`
fn starting_line(title: &str, seconds: f64) -> Line {
	let time = readable_time(seconds);

	vec![
		("Sự kiện ".to_owned(), WHITE),
		(title.to_owned(), GREEN),
		(" sẽ bắt đầu sau ".to_owned(), WHITE),
		(time.text, time.color),
		(" nữa!".to_owned(), WHITE),
	]
}

/// `#<id> <title> sau <time>`, the bar's own title while it counts.
fn counting_bar(id: u32, title: &str, remaining: f64) -> Line {
	let time = readable_time(remaining);

	vec![
		(format!("#{id} "), GRAY),
		(title.to_owned(), GREEN),
		(" sau ".to_owned(), GRAY),
		(time.text, time.color),
	]
}

/// `#<id> <title> đã bắt đầu!`
fn finished_bar(id: u32, title: &str) -> Line {
	vec![
		(format!("#{id} "), GRAY),
		(title.to_owned(), GREEN),
		(" đã bắt đầu!".to_owned(), GRAY),
	]
}

/// The same words, prefixed for chat.
fn finished_line(id: u32, title: &str) -> Line {
	let mut line = vec![("Sự kiện ".to_owned(), WHITE)];

	line.extend(finished_bar(id, title));

	line
}

/// What a cancelled bar is left showing.
fn cancelled_bar(title: &str, reason: Option<&str>) -> Line {
	if let Some(reason) = reason.map(str::trim).filter(|given| !given.is_empty()) {
		return vec![(reason.to_owned(), WHITE)];
	}

	vec![
		("Đã hủy bỏ ".to_owned(), WHITE),
		(title.to_owned(), LIGHT_PURPLE),
	]
}

/// `Sự kiện (#<id>) <title> đã bị hủy!`
fn cancelled_line(id: u32, title: &str) -> Line {
	vec![
		("Sự kiện ".to_owned(), WHITE),
		(format!("(#{id}) "), GRAY),
		(title.to_owned(), LIGHT_PURPLE),
		(" đã bị hủy!".to_owned(), WHITE),
	]
}

/// Build a component from the runs. One per use; see [`Line`].
fn render(line: &Line) -> TextComponent {
	let root = TextComponent::text("");

	for (message, color) in line {
		let run = TextComponent::text(message);

		run.color_rgb(RgbColor {
			r: color.0,
			g: color.1,
			b: color.2,
		});

		root.add_child(run);
	}

	root
}

/// Send one line to everybody, in chat rather than over the hotbar.
fn broadcast(server: &Server, line: &Line) {
	for player in server.get_all_players() {
		player.send_system_message(render(line), false);
	}
}
