//! Holding a player still until the proxy says who they are.
//!
//! Authentication is the proxy's: it owns the passwords, the sessions and the
//! premium probes, and a backend deciding any of it for itself would let
//! somebody log in on one server and not another. What a backend owns is the
//! consequence, and it is the whole of this module - until the proxy answers,
//! the player cannot move, type, build, or open anything.
//!
//! The default is refusal. A player nobody has heard of is unauthenticated, so
//! a proxy that never replies leaves them locked rather than loose; every
//! restriction reads the state rather than a "we asked and it failed" flag,
//! which is what makes a dropped answer safe.
//!
//! Two pieces of the Paper plugin are deliberately **not** here, both because
//! the sandbox changes what they mean:
//!
//! - The **lobby item registry** exists so other plugins can put a compass in
//!   an authenticated player's hotbar. Pumpkin's `plugin` world has no
//!   inter-plugin imports, so there is nobody to register one, and its single
//!   built-in item opens a server selector this port does not have yet.
//! - **Clearing an unauthenticated player's inventory** is safe on Paper only
//!   because the lobby items are put back the moment they authenticate. Without
//!   that half it is a destructive operation waiting for a slow proxy, so it
//!   is left out rather than ported alone.

use crate::config::{AuthConfig, PromptStrings};
use crate::messaging::{Dispatch, MessageBus, find_player_by_id};
use crate::text::{markup, overlay, overlay_markup, render, tell, tell_markup};
use luna_core_api::auth::{
	ADMIN_REQUEST, AUTH_STATE, AdminRequest, AuthRequest, AuthResult, AuthState, AuthStates,
	COMMAND_REQUEST, COMMAND_RESPONSE, PromptMode, StateMessage, allowed_commands, command_allowed,
	mode_key,
};
use luna_core_api::palette::color;
use luna_core_api::text::{Span, parse};
use luna_core_api::throttle::Throttle;
use pumpkin_plugin_api::Server;
use pumpkin_plugin_api::boss_bar::{BossBar, BossBarColor, BossBarDivision};
use pumpkin_plugin_api::gui::{Gui, GuiType};
use pumpkin_plugin_api::item_stack::ItemStack;
use pumpkin_plugin_api::player::{Player, StatusEffectInstance, StatusEffectType};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex};

/// How long the proxy has to answer before we ask again.
const SYNC_INTERVAL_MILLIS: u64 = 1_500;

/// How often the lock is re-asserted from an event, rather than every event.
const LOCK_SYNC_MILLIS: u64 = 250;

/// How often a player who wandered is dragged back to the auth spawn.
const SPAWN_ENFORCE_MILLIS: u64 = 2_000;

/// How often the blindness and slowness are topped up.
const EFFECT_REFRESH_MILLIS: u64 = 3_000;

/// How often the hotbar prompt is redrawn.
const ACTIONBAR_MILLIS: u64 = 1_500;

/// How often one kind of refusal is written to the audit log per player.
const AUDIT_MILLIS: u64 = 3_000;

/// Effect lengths, in ticks, taken from the Paper plugin unchanged.
///
/// Both outlast their refresh interval several times over: an effect that
/// expired between two refreshes would let a locked player take a step.
const BLINDNESS_TICKS: u32 = 600;
const LOCK_EFFECT_TICKS: u32 = 220;

/// What a player's speed is put back to when nothing else is known.
const DEFAULT_WALK_SPEED: f32 = 0.2;
const DEFAULT_FLY_SPEED: f32 = 0.1;

/// The mode selector's nine slots.
const SELECTOR_SLOTS: i16 = 9;
const SLOT_PREMIUM: i16 = 3;
const SLOT_INFO: i16 = 4;
const SLOT_OFFLINE: i16 = 5;
const SLOT_REMEMBER: i16 = 7;

/// What kind of thing a player was refused, for the audit trail.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
	Move,
	Command,
	Chat,
	Interaction,
}

impl Refusal {
	/// The throttle bucket this kind is logged under.
	fn key(self) -> &'static str {
		match self {
			Self::Move => "move",
			Self::Command => "command",
			Self::Chat => "chat",
			Self::Interaction => "interaction",
		}
	}

	/// Whether the player is told, or only the log is.
	///
	/// A move fires many times a second and a chat line asking them to log in
	/// after every step would bury the one that matters; a refused command or
	/// message is a deliberate act and gets an answer.
	fn tells_the_player(self) -> bool {
		matches!(self, Self::Command | Self::Chat)
	}
}

/// One prompt, parsed once into the runs it draws as.
struct Prompt {
	bossbar: Vec<Span>,
	actionbar: Vec<Span>,
	chat: Vec<Span>,
}

impl Prompt {
	fn parse(strings: &PromptStrings) -> Self {
		Self {
			bossbar: parse(&strings.bossbar),
			actionbar: parse(&strings.actionbar),
			chat: parse(&strings.chat),
		}
	}
}

/// A player's own movement settings, kept so the lock can be undone exactly.
#[derive(Clone, Copy)]
struct MovementProfile {
	walk_speed: f32,
	fly_speed: f32,
	allow_flight: bool,
}

/// Where a locked player is held.
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SpawnPoint {
	/// The world's dimension id, as `get_world_by_name` takes it.
	pub world: String,
	pub x: f64,
	pub y: f64,
	pub z: f64,
	pub yaw: f32,
	pub pitch: f32,
	pub set_by: String,
	pub updated_at: u64,
}

impl Default for SpawnPoint {
	fn default() -> Self {
		Self {
			world: String::new(),
			x: 0.0,
			y: 0.0,
			z: 0.0,
			yaw: 0.0,
			pitch: 0.0,
			set_by: String::new(),
			updated_at: 0,
		}
	}
}

impl SpawnPoint {
	/// Whether this names a place, as opposed to being the empty default.
	fn is_set(&self) -> bool {
		!self.world.trim().is_empty()
	}
}

/// What each player picked in the selector, and whether they have picked.
#[derive(Default)]
struct Selector {
	/// Everybody the selector has been opened for, so it is offered once.
	offered: BTreeSet<String>,
	/// Everybody who has chosen, or for whom choosing is moot.
	chosen: BTreeSet<String>,
	/// Whose name looks like a paid account's.
	eligible: BTreeSet<String>,
	/// Who the proxy already holds a preference for.
	remembered: BTreeSet<String>,
	/// Who has the "remember this" toggle switched on.
	remembering: BTreeSet<String>,
	/// Who has the GUI open right now, which is how a click is recognised.
	open: BTreeSet<String>,
	/// Who is owed a draw on the next tick.
	///
	/// Opening a window from a click handler deadlocks the player's own screen
	/// handler; `crate::selector` carries the whole explanation. Every draw
	/// therefore goes through here.
	pending: BTreeSet<String>,
}

/// Every per-player rate limit the guard keeps.
struct Throttles {
	sync: Throttle,
	lock: Throttle,
	spawn: Throttle,
	effects: Throttle,
	actionbar: Throttle,
	audit: BTreeMap<&'static str, Throttle>,
}

impl Throttles {
	fn new() -> Self {
		Self {
			sync: Throttle::new(SYNC_INTERVAL_MILLIS),
			lock: Throttle::new(LOCK_SYNC_MILLIS),
			spawn: Throttle::new(SPAWN_ENFORCE_MILLIS),
			effects: Throttle::new(EFFECT_REFRESH_MILLIS),
			actionbar: Throttle::new(ACTIONBAR_MILLIS),
			audit: BTreeMap::new(),
		}
	}

	fn forget(&mut self, id: &str) {
		self.sync.forget(id);
		self.lock.forget(id);
		self.spawn.forget(id);
		self.effects.forget(id);
		self.actionbar.forget(id);

		for throttle in self.audit.values_mut() {
			throttle.forget(id);
		}
	}
}

/// The auth backend.
pub struct AuthGuard {
	bus: Arc<MessageBus>,
	states: Mutex<AuthStates>,
	pending: Prompt,
	login: Prompt,
	register: Prompt,
	/// The success message, and the ones a particular method overrides it with.
	success: Prompt,
	by_method: BTreeMap<String, (Vec<Span>, Vec<Span>)>,
	allowed: BTreeSet<String>,
	selector_enabled: bool,
	clear_inventory: bool,
	audit: bool,
	spawn: Mutex<SpawnPoint>,
	spawn_path: std::path::PathBuf,
	locked: Mutex<BTreeSet<String>>,
	profiles: Mutex<BTreeMap<String, MovementProfile>>,
	bars: Mutex<BTreeMap<String, BossBar>>,
	selector: Mutex<Selector>,
	throttles: Mutex<Throttles>,
}

impl AuthGuard {
	/// Build the guard and put its channels on the bus.
	#[must_use]
	pub fn new(bus: Arc<MessageBus>, config: &AuthConfig, data_folder: &str) -> Arc<Self> {
		let spawn_path = std::path::Path::new(data_folder).join("auth-spawn.toml");
		let guard = Arc::new(Self {
			bus: Arc::clone(&bus),
			states: Mutex::new(AuthStates::new()),
			pending: Prompt::parse(&config.prompt.pending),
			login: Prompt::parse(&config.prompt.login),
			register: Prompt::parse(&config.prompt.register),
			success: Prompt::parse(&config.prompt.authenticated),
			by_method: config
				.prompt
				.by_method
				.iter()
				.map(|(method, strings)| {
					(
						method.clone(),
						(parse(&strings.chat), parse(&strings.actionbar)),
					)
				})
				.collect(),
			allowed: allowed_commands(&config.allowed_commands),
			selector_enabled: config.mode_selector_enabled,
			clear_inventory: config.clear_inventory_on_lock,
			audit: config.log_flow,
			spawn: Mutex::new(read_spawn(&spawn_path)),
			spawn_path,
			locked: Mutex::new(BTreeSet::new()),
			profiles: Mutex::new(BTreeMap::new()),
			bars: Mutex::new(BTreeMap::new()),
			selector: Mutex::new(Selector::default()),
			throttles: Mutex::new(Throttles::new()),
		});

		bus.register_outgoing(COMMAND_REQUEST);

		let states = Arc::clone(&guard);

		bus.register_incoming(AUTH_STATE, move |context| {
			let Some(player) = context.source else {
				return states.absent(AUTH_STATE);
			};

			match StateMessage::decode(context.payload) {
				Ok(Some(message)) => {
					states.on_state(player, &message);

					Dispatch::Handled
				}
				Ok(None) => Dispatch::PassThrough,
				Err(error) => {
					tracing::warn!("Auth state không đọc được: {error:?}");

					Dispatch::PassThrough
				}
			}
		});

		let results = Arc::clone(&guard);

		bus.register_incoming(COMMAND_RESPONSE, move |context| {
			let Some(player) = context.source else {
				return results.absent(COMMAND_RESPONSE);
			};

			match AuthResult::decode(context.payload) {
				Ok(Some(result)) => {
					results.on_result(player, &result);

					Dispatch::Handled
				}
				Ok(None) => Dispatch::PassThrough,
				Err(error) => {
					tracing::warn!("Auth result không đọc được: {error:?}");

					Dispatch::PassThrough
				}
			}
		});

		let admin = Arc::clone(&guard);

		bus.register_incoming(ADMIN_REQUEST, move |context| {
			let Some(player) = context.source else {
				return admin.absent(ADMIN_REQUEST);
			};

			match AdminRequest::decode(context.payload) {
				Ok(Some(request)) => {
					admin.on_admin(player, &request);

					Dispatch::Handled
				}
				Ok(None) => Dispatch::PassThrough,
				Err(error) => {
					tracing::warn!("Auth admin request không đọc được: {error:?}");

					Dispatch::PassThrough
				}
			}
		});

		guard
	}

	/// A message arrived about somebody who is not on this server.
	///
	/// Every auth message is an instruction about one connection - unlock it,
	/// tell it something, read its position - so with no connection there is
	/// nothing to carry out. It is still worth saying: a proxy repeatedly
	/// answering for a player who left is a routing fault, and silence here is
	/// what would make it invisible.
	fn absent(&self, channel: &str) -> Dispatch {
		if self.audit {
			tracing::info!("[AUTH] {channel} tới nơi nhưng người chơi không ở server này.");
		}

		Dispatch::PassThrough
	}

	/// Whether this player may do anything at all.
	#[must_use]
	pub fn is_authenticated(&self, id: &str) -> bool {
		self.states
			.lock()
			.expect("auth states poisoned")
			.is_authenticated(id)
	}

	/// Refuse something a locked player tried, telling them when it is worth it.
	///
	/// Always returns true, so a handler reads
	/// `event.cancelled = guard.refuse(&player, Refusal::Command)`.
	pub fn refuse(&self, player: &Player, kind: Refusal) -> bool {
		let id = player.get_id().to_string();

		if kind.tells_the_player() {
			let prompt = self.prompt_for(&id);

			tell(player, &prompt.chat);
		}

		self.note(player, kind);

		true
	}

	/// Record that a locked player tried something, without refusing it.
	///
	/// Movement takes this path: Paper logs the attempt and lets the event
	/// through, because the lock rather than the cancel is what holds them.
	pub fn note(&self, player: &Player, kind: Refusal) {
		if !self.audit {
			return;
		}

		let id = player.get_id().to_string();

		if !self.audit_due(kind, &id) {
			return;
		}

		tracing::info!(
			"[AUTH] blocked {:?} player={} state={:?}",
			kind,
			player.get_name(),
			self.states.lock().expect("auth states poisoned").state(&id)
		);
	}

	/// Whether a player may run this command line while still locked.
	#[must_use]
	pub fn command_permitted(&self, line: &str) -> bool {
		command_allowed(line, &self.allowed)
	}

	/// A player arrived: find out where they stand, and hold them until we do.
	pub fn on_join(&self, server: &Server, player: &Player) {
		let id = player.get_id().to_string();

		// a state already held is a reconnect the proxy has answered for; the
		// player should not be re-locked just because the connection is new
		if !self.states.lock().expect("auth states poisoned").has_state(&id) {
			self.states
				.lock()
				.expect("auth states poisoned")
				.set(&id, AuthState::pending());
		}

		self.request(player, &AuthRequest::SyncState);

		let prompt_known = self.prompt_mode(&id) != PromptMode::Pending;

		if prompt_known {
			tell(player, &self.prompt_for(&id).chat);
		}

		self.teleport_to_spawn(server, player);
		self.sync_lock(player);
	}

	/// A player left: forget everything keyed by them.
	pub fn on_leave(&self, id: &str) {
		self.hide_bar(id);
		self.states.lock().expect("auth states poisoned").clear(id);
		self.locked.lock().expect("auth locked poisoned").remove(id);
		self.profiles.lock().expect("auth profiles poisoned").remove(id);
		self.throttles.lock().expect("auth throttles poisoned").forget(id);

		let mut selector = self.selector.lock().expect("auth selector poisoned");

		selector.offered.remove(id);
		selector.chosen.remove(id);
		selector.eligible.remove(id);
		selector.remembered.remove(id);
		selector.remembering.remove(id);
		selector.open.remove(id);
		selector.pending.remove(id);
	}

	/// One second of work for every player who is not through yet.
	pub fn tick(&self, server: &Server) {
		let now = now_millis();

		for player in server.get_all_players() {
			let id = player.get_id().to_string();

			if self.is_authenticated(&id) {
				// a player who authenticated between two ticks is still carrying
				// the lock until something takes it off them
				if self.locked.lock().expect("auth locked poisoned").contains(&id) {
					self.sync_lock(&player);
				}

				self.hide_bar(&id);

				continue;
			}

			self.sync_lock(&player);

			if self.prompt_mode(&id) == PromptMode::Pending {
				// nothing to draw yet, and a prompt that says "please wait" on a
				// boss bar for a whole minute reads as a broken server
				if self.due(|throttles| &mut throttles.sync, &id, now) {
					self.request(&player, &AuthRequest::SyncState);
				}

				self.hide_bar(&id);

				continue;
			}

			if self.spawn.lock().expect("auth spawn poisoned").is_set()
				&& self.due(|throttles| &mut throttles.spawn, &id, now)
			{
				self.teleport_to_spawn(server, &player);
			}

			if self.due(|throttles| &mut throttles.effects, &id, now) {
				refresh_lock_effects(&player);
			}

			self.show_prompt(server, &player, &id, now);
		}
	}

	/// Send one request to the proxy about this player.
	pub fn request(&self, player: &Player, request: &AuthRequest) -> bool {
		let payload = request.encode(&player.get_id().to_string(), &player.get_name());
		let sent = self.bus.send(player, COMMAND_REQUEST, &payload);

		if self.audit {
			tracing::info!(
				"[AUTH] TX {} player={} sent={sent}",
				request.action(),
				player.get_name()
			);
		}

		sent
	}

	/// The proxy said where a player stands.
	fn on_state(&self, player: &Player, message: &StateMessage) {
		let id = player.get_id().to_string();

		if message.player_id != id {
			// the payload is about somebody else, which on a shared channel means
			// the proxy addressed the wrong connection; acting on it would unlock
			// the wrong player
			tracing::warn!(
				"Auth state gửi nhầm người: payload={} connection={id}",
				message.player_id
			);

			return;
		}

		self.note_selector_eligibility(&id, message.premium_name_candidate, message.has_mode_preference);
		self.apply_state(player, &id, message.to_state(), "AUTH_STATE");
	}

	/// The proxy answered a `/login` or `/register`.
	fn on_result(&self, player: &Player, result: &AuthResult) {
		let id = player.get_id().to_string();

		if result.player_id != id {
			tracing::warn!(
				"Auth result gửi nhầm người: payload={} connection={id}",
				result.player_id
			);

			return;
		}

		self.note_selector_eligibility(&id, result.premium_name_candidate, result.has_mode_preference);

		// a failure, or a success that did not actually let them in, is the only
		// case the proxy's own words are shown; a real login gets the configured
		// congratulation instead, which is the one that names the method
		if !result.success || !result.authenticated {
			// the proxy writes the body as MiniMessage and Paper wraps the whole
			// thing in one colour, so the wrap has to be parsed with it rather
			// than applied to a finished component
			let wrapped = if result.success {
				format!("<green>{}</green>", result.message)
			} else {
				format!("<red>{}</red>", result.message)
			};

			tell_markup(player, &wrapped);
		}

		self.apply_state(player, &id, result.to_state(), "COMMAND_RESPONSE");

		if result.success && result.authenticated {
			self.congratulate(player, &result.method);
		}
	}

	/// An operator asked for the auth spawn to be moved to where they stand.
	fn on_admin(&self, player: &Player, request: &AdminRequest) {
		let id = player.get_id().to_string();

		if request.target_id != id {
			// the proxy routes this to the operator's own connection, because
			// their position is the whole payload; anyone else's is meaningless
			return;
		}

		let (x, y, z) = player.get_position();
		let point = SpawnPoint {
			world: player.get_world().get_id(),
			x,
			y,
			z,
			yaw: player.get_yaw(),
			pitch: player.get_pitch(),
			set_by: request.actor.clone(),
			updated_at: now_millis(),
		};

		let written = write_spawn(&self.spawn_path, &point);

		if let Err(error) = written {
			tracing::error!("Không ghi được auth-spawn: {error}");
			tell_markup(
				player,
				&format!(
					"<color:{danger}>❌ Không thể cập nhật auth-spawn tại vị trí hiện tại.</color>",
					danger = color::DANGER_500,
				),
			);

			return;
		}

		*self.spawn.lock().expect("auth spawn poisoned") = point;

		tell_markup(
			player,
			&format!(
				"<color:{success}>✔ Điểm auth-spawn đã được cập nhật bởi {actor}.</color>",
				success = color::SUCCESS_500,
				actor = request.actor,
			),
		);
	}

	/// Record a state, redrawing whatever it changed.
	fn apply_state(&self, player: &Player, id: &str, state: AuthState, reason: &str) {
		let previous = self
			.states
			.lock()
			.expect("auth states poisoned")
			.set(id, state);

		if self.audit && previous != state {
			tracing::info!(
				"[AUTH] {} player={} {:?} -> {:?}",
				reason,
				player.get_name(),
				previous,
				state
			);
		}

		if state.authenticated {
			self.hide_bar(id);
			self.close_selector(id);
		}

		self.sync_lock(player);
	}

	/// Tell a player they are in, in whichever words their method earns.
	fn congratulate(&self, player: &Player, method: &str) {
		let (chat, actionbar) = self
			.by_method
			.get(method)
			.map_or((&self.success.chat, &self.success.actionbar), |(chat, bar)| {
				(chat, bar)
			});

		tell(player, chat);
		overlay(player, actionbar);
	}

	/// Put the lock on, or take it off, to match the player's state.
	///
	/// Idempotent by design: it is called from the tick, from every state
	/// change and from the throttled path in the move handler, and doing the
	/// work twice would fight the player's own speed settings.
	fn sync_lock(&self, player: &Player) {
		let id = player.get_id().to_string();

		if self.is_authenticated(&id) {
			let was_locked = self.locked.lock().expect("auth locked poisoned").remove(&id);

			if was_locked {
				self.release(player, &id);
			}

			return;
		}

		let newly_locked = self
			.locked
			.lock()
			.expect("auth locked poisoned")
			.insert(id.clone());

		if newly_locked {
			self.apply_lock(player, &id);
		}
	}

	/// Re-assert the lock, but no more often than [`LOCK_SYNC_MILLIS`].
	///
	/// The move handler calls this: it fires many times a second per player, and
	/// the state it is checking changes at most once per proxy round trip.
	pub fn sync_lock_if_due(&self, player: &Player) {
		let id = player.get_id().to_string();

		if !self.due(|throttles| &mut throttles.lock, &id, now_millis()) {
			return;
		}

		self.sync_lock(player);
	}

	/// Freeze a player, remembering what they were before.
	fn apply_lock(&self, player: &Player, id: &str) {
		let abilities = player.get_abilities();

		self.profiles
			.lock()
			.expect("auth profiles poisoned")
			.entry(id.to_owned())
			.or_insert(MovementProfile {
				walk_speed: usable_speed(abilities.walk_speed, DEFAULT_WALK_SPEED),
				fly_speed: usable_speed(abilities.fly_speed, DEFAULT_FLY_SPEED),
				allow_flight: abilities.allow_flying,
			});

		if self.clear_inventory {
			clear_inventory(player);
		}

		player.set_walk_speed(0.0);
		player.set_fly_speed(0.0);

		// nothing can hurt them while they cannot run away from it; this also
		// covers the damage the plugin has no event it can cancel - pumpkin's
		// entity-damage event names an entity id rather than a player, so there
		// is nothing to match a victim against
		player.set_invulnerable(true);

		refresh_lock_effects(player);
	}

	/// Give a player back everything the lock took.
	fn release(&self, player: &Player, id: &str) {
		let profile = self
			.profiles
			.lock()
			.expect("auth profiles poisoned")
			.remove(id);

		let profile = profile.unwrap_or(MovementProfile {
			walk_speed: DEFAULT_WALK_SPEED,
			fly_speed: DEFAULT_FLY_SPEED,
			allow_flight: false,
		});

		player.set_walk_speed(usable_speed(profile.walk_speed, DEFAULT_WALK_SPEED));
		player.set_fly_speed(usable_speed(profile.fly_speed, DEFAULT_FLY_SPEED));
		player.set_allow_flight(profile.allow_flight);
		player.set_invulnerable(false);

		for effect in [
			StatusEffectType::Blindness,
			StatusEffectType::Slowness,
			StatusEffectType::JumpBoost,
		] {
			player.remove_effect(effect);
		}

		if self.audit {
			tracing::info!("[AUTH] unlocked player={}", player.get_name());
		}
	}

	/// Draw the boss bar and, less often, the hotbar line.
	fn show_prompt(&self, server: &Server, player: &Player, id: &str, now: u64) {
		let prompt = self.prompt_for(id);

		{
			let mut bars = self.bars.lock().expect("auth bars poisoned");
			let fresh = !bars.contains_key(id);

			let bar = bars.entry(id.to_owned()).or_insert_with(|| {
				let bar = BossBar::new(
					render(&prompt.bossbar),
					BossBarColor::Yellow,
					BossBarDivision::NoDivision,
				);

				bar.set_health(1.0);
				bar
			});

			// the title is re-set even on a bar that already existed: the prompt
			// changes under the player when the proxy answers register rather
			// than login
			bar.set_title(render(&prompt.bossbar));

			if fresh {
				// a bar takes ownership of the handle it is shown to, and the
				// caller's cannot be given away, so the roster supplies another
				if let Some(viewer) = find_player_by_id(server, id) {
					bar.add_player(viewer);
				}
			}
		}

		if self.due(|throttles| &mut throttles.actionbar, id, now) {
			overlay(player, &prompt.actionbar);
		}
	}

	/// Take a player's bar down, if they have one.
	fn hide_bar(&self, id: &str) {
		let bar = self.bars.lock().expect("auth bars poisoned").remove(id);

		if let Some(bar) = bar {
			bar.remove_all();
		}
	}

	/// Which of the three prompts applies to this player.
	fn prompt_for(&self, id: &str) -> &Prompt {
		match self.prompt_mode(id) {
			PromptMode::Register => &self.register,
			PromptMode::Login => &self.login,
			PromptMode::Pending => &self.pending,
		}
	}

	fn prompt_mode(&self, id: &str) -> PromptMode {
		let state = self.states.lock().expect("auth states poisoned").state(id);

		if state.authenticated {
			return PromptMode::Pending;
		}

		state.prompt
	}

	/// Put a player back on the auth spawn, when there is one.
	fn teleport_to_spawn(&self, server: &Server, player: &Player) -> bool {
		let point = self.spawn.lock().expect("auth spawn poisoned").clone();

		if !point.is_set() {
			return false;
		}

		let Some(world) = server.get_world_by_name(&point.world) else {
			tracing::warn!("auth-spawn trỏ tới world không tồn tại: {}", point.world);

			return false;
		};

		player.teleport(
			(point.x, point.y, point.z),
			Some(point.yaw),
			Some(point.pitch),
			world,
		);

		true
	}

	/// Whether one throttle says this player's key may run now.
	fn due<F>(&self, pick: F, id: &str, now: u64) -> bool
	where
		F: FnOnce(&mut Throttles) -> &mut Throttle,
	{
		let mut throttles = self.throttles.lock().expect("auth throttles poisoned");

		pick(&mut throttles).due(id, now)
	}

	/// Whether this refusal is worth another line in the log.
	fn audit_due(&self, kind: Refusal, id: &str) -> bool {
		let now = now_millis();
		let mut throttles = self.throttles.lock().expect("auth throttles poisoned");

		throttles
			.audit
			.entry(kind.key())
			.or_insert_with(|| Throttle::new(AUDIT_MILLIS))
			.due(id, now)
	}
}

// The mode selector: a nine-slot GUI offered to a player whose name looks like
// a paid account's, so they can say whether it is theirs before the proxy
// probes for it.
impl AuthGuard {
	/// Whether this player has the selector open, which is how a click is ours.
	///
	/// Pumpkin's inventory-click event names a window *type*, not an instance,
	/// so the only reliable question is which player we opened one for. That is
	/// safe here precisely because an unauthenticated player cannot open
	/// anything else - every other inventory open is refused.
	#[must_use]
	pub fn selector_is_open(&self, id: &str) -> bool {
		self.selector
			.lock()
			.expect("auth selector poisoned")
			.open
			.contains(id)
	}

	/// Whether the selector should still be offered to this player.
	#[must_use]
	pub fn selector_wanted(&self, id: &str) -> bool {
		if !self.selector_enabled || self.is_authenticated(id) {
			return false;
		}

		let selector = self.selector.lock().expect("auth selector poisoned");

		selector.eligible.contains(id)
			&& !selector.chosen.contains(id)
			&& !selector.remembered.contains(id)
	}

	/// Ask for the selector to be shown, unless it is no longer wanted.
	///
	/// Nothing is drawn here; [`AuthGuard::draw_selectors`] does that on the
	/// next tick, because opening a window from a click handler deadlocks the
	/// player's own screen handler. `crate::selector` carries the detail.
	pub fn open_selector(&self, id: &str) {
		if !self.selector_wanted(id) {
			return;
		}

		self.selector
			.lock()
			.expect("auth selector poisoned")
			.pending
			.insert(id.to_owned());
	}

	/// Draw the selector for everybody who was promised it.
	pub fn draw_selectors(&self, server: &Server) {
		let due = std::mem::take(
			&mut self
				.selector
				.lock()
				.expect("auth selector poisoned")
				.pending,
		);

		for id in due {
			let Some(player) = find_player_by_id(server, &id) else {
				continue;
			};

			if !self.selector_wanted(&id) {
				continue;
			}

			let remember = {
				let mut selector = self.selector.lock().expect("auth selector poisoned");

				// set before the window opens, not after: opening it fires the
				// inventory-open event, and this flag is what lets that one through
				selector.open.insert(id.clone());
				selector.offered.insert(id.clone());
				selector.remembering.contains(&id)
			};

			player.open_gui(build_selector(remember));
		}
	}

	/// A click landed while the selector was open.
	///
	/// The index is the **raw** slot, which is the one that counts the window
	/// and the player's own inventory as one run. A nine-slot GUI owns 0..9 of
	/// it; the plain slot number would put the player's fourth hotbar square on
	/// top of the Premium button.
	pub fn on_selector_click(&self, player: &Player, raw_slot: i16) {
		let id = player.get_id().to_string();

		if self.is_authenticated(&id) {
			self.close_selector(&id);

			return;
		}

		if !(0..SELECTOR_SLOTS).contains(&raw_slot) {
			// their own inventory: the click is still refused, it just does not
			// mean anything
			return;
		}

		let slot = raw_slot;

		if slot == SLOT_REMEMBER {
			let remembering = {
				let mut selector = self.selector.lock().expect("auth selector poisoned");

				if selector.remembering.contains(&id) {
					selector.remembering.remove(&id);
					false
				} else {
					selector.remembering.insert(id.clone());
					true
				}
			};

			// the toggle is a slot in a GUI whose items cannot be replaced in
			// place, so the whole thing is drawn again with the new label
			self.selector
				.lock()
				.expect("auth selector poisoned")
				.pending
				.insert(id.clone());

			overlay_markup(
				player,
				if remembering {
					"<gold>Đã bật ghi nhớ lựa chọn vĩnh viễn.</gold>"
				} else {
					"<yellow>Đã tắt ghi nhớ vĩnh viễn (chỉ 24h).</yellow>"
				},
			);

			return;
		}

		let premium = match slot {
			SLOT_PREMIUM => true,
			SLOT_OFFLINE => false,
			_ => return,
		};

		let remember = self
			.selector
			.lock()
			.expect("auth selector poisoned")
			.remembering
			.contains(&id);

		let mode = mode_key(premium, remember);
		let sent = self.request(
			player,
			&AuthRequest::SetPreference {
				mode: mode.to_owned(),
			},
		);

		if !sent {
			// the choice never left this server, so the player has not chosen;
			// marking them as having done so would close a selector that changed
			// nothing on the proxy
			overlay_markup(player, "<red>Không gửi được lựa chọn. Vui lòng thử lại.</red>");

			return;
		}

		self.selector
			.lock()
			.expect("auth selector poisoned")
			.chosen
			.insert(id.clone());

		tell_markup(player, chosen_message(premium, remember));
		self.close_selector(&id);
	}

	/// The player closed the selector. Put it back if they have not chosen.
	pub fn on_selector_closed(&self, player: &Player) {
		let id = player.get_id().to_string();

		self.selector
			.lock()
			.expect("auth selector poisoned")
			.open
			.remove(&id);

		if !self.selector_wanted(&id) {
			return;
		}

		self.open_selector(&id);
	}

	/// Stop treating this player's clicks as the selector's.
	///
	/// It does not shut the window: `pumpkin:plugin@0.1.0` has `open-gui` and
	/// no matching close, so a player who authenticates with the selector open
	/// closes it themselves. Dropping the flag is what stops it being reopened
	/// underneath them when they do.
	fn close_selector(&self, id: &str) {
		self.selector
			.lock()
			.expect("auth selector poisoned")
			.open
			.remove(id);
	}

	/// Record what the proxy said about this player's account, and act on it.
	fn note_selector_eligibility(
		&self,
		id: &str,
		premium_name: bool,
		has_preference: bool,
	) {
		{
			let mut selector = self.selector.lock().expect("auth selector poisoned");

			if premium_name {
				selector.eligible.insert(id.to_owned());
			} else {
				selector.eligible.remove(id);
			}

			if has_preference {
				selector.remembered.insert(id.to_owned());
			} else {
				selector.remembered.remove(id);
			}

			// Paper clears the "has chosen" mark here rather than only setting
			// it: a player whose choice the proxy did not keep is eligible
			// again, and leaving them marked would hide the selector for the
			// rest of the session
			if premium_name && !has_preference {
				selector.chosen.remove(id);
			} else {
				selector.chosen.insert(id.to_owned());
			}
		}

		if !self.selector_wanted(id) {
			self.close_selector(id);

			return;
		}

		// only offered once per session; a player who dismissed it gets it back
		// from `on_selector_closed` rather than from every state message
		let already = self
			.selector
			.lock()
			.expect("auth selector poisoned")
			.offered
			.contains(id);

		if !already {
			self.open_selector(id);
		}
	}
}

/// The selector's title, which Paper writes as plain text rather than markup.
const SELECTOR_TITLE: &str = "Chọn kiểu tài khoản";

/// What a player is told after picking a login mode, as Paper words it.
fn chosen_message(premium: bool, remember: bool) -> &'static str {
	match (premium, remember) {
		(true, true) => "<yellow>Đã chọn Premium (ghi nhớ vĩnh viễn). Bạn sẽ được kết nối lại để xác thực online.</yellow>",
		(true, false) => "<yellow>Đã chọn Premium (24h). Bạn sẽ được kết nối lại để xác thực online.</yellow>",
		(false, true) => "<green>Đã chọn Offline (ghi nhớ vĩnh viễn). Tiếp tục đăng nhập bằng mật khẩu server.</green>",
		(false, false) => "<green>Đã chọn Offline (24h). Tiếp tục đăng nhập bằng mật khẩu server.</green>",
	}
}

/// Build the nine-slot selector, with the remember toggle in the state given.
///
/// Every string here is `AuthRestrictionListener.createModeSelectorInventory`
/// verbatim, MiniMessage and all, rather than re-expressed as colours picked by
/// hand. Paper writes these in vanilla's *named* colours (`<yellow>`, `<green>`,
/// `<aqua>`, `<gold>`) which are not luna's palette, and eyeballing a hex for
/// each one is exactly how the two ports drift apart.
fn build_selector(remember: bool) -> Gui {
	let gui = Gui::new(GuiType::Generic9x1, markup(SELECTOR_TITLE));

	// belt and braces with the cancelled click event: the GUI itself refuses to
	// give anything up, so a click the handler somehow misses still takes nothing
	gui.set_allow_grab_items(false);
	gui.set_allow_put_items(false);

	for slot in [0, 1, 2, 6, 8] {
		gui.set_item(
			slot,
			selector_item(
				"minecraft:gray_stained_glass_pane",
				"<dark_gray>•</dark_gray>",
				&["<gray> </gray>"],
			),
		);
	}

	gui.set_item(
		SLOT_INFO as u32,
		selector_item(
			"minecraft:book",
			"<yellow><b>ℹ Chọn Chế Độ Đăng Nhập</b></yellow>",
			&[
				"<gray>Premium hoặc Offline.</gray>",
				"<gray>Nút bên phải bật/tắt ghi nhớ.</gray>",
				"",
				"<gold>⚠ Hãy chọn đúng để tránh lỗi phiên.</gold>",
			],
		),
	);

	gui.set_item(
		SLOT_PREMIUM as u32,
		selector_item(
			"minecraft:nether_star",
			"<green><b>★ Tài Khoản Premium</b></green>",
			&[
				"<gray>Dùng launcher Microsoft.</gray>",
				"<gray>Sẽ probe xác thực online.</gray>",
				"",
				"<yellow>▶ Ấn để chọn.</yellow>",
			],
		),
	);

	gui.set_item(
		SLOT_OFFLINE as u32,
		selector_item(
			"minecraft:iron_bars",
			"<aqua><b>⬤ Tài Khoản Offline</b></aqua>",
			&[
				"<gray>Dùng launcher cracked.</gray>",
				"<gray>Không ép xác thực online.</gray>",
				"",
				"<yellow>▶ Ấn để chọn.</yellow>",
			],
		),
	);

	gui.set_item(SLOT_REMEMBER as u32, remember_item(remember));

	gui
}

/// The remember toggle, drawn for whichever state it is in.
fn remember_item(remember: bool) -> ItemStack {
	if remember {
		return selector_item(
			"minecraft:lime_dye",
			"<gold><b>🔔 Ghi Nhớ: BẬT</b></gold>",
			&[
				"<gray>Lựa chọn sẽ được giữ vĩnh viễn.</gray>",
				"<yellow>▶ Ấn để chuyển trạng thái.</yellow>",
			],
		);
	}

	selector_item(
		"minecraft:gray_dye",
		"<gray><b>🔔 Ghi Nhớ: TẮT</b></gray>",
		&[
			"<gray>Lựa chọn chỉ có hiệu lực 24 giờ.</gray>",
			"<yellow>▶ Ấn để chuyển trạng thái.</yellow>",
		],
	)
}

/// One item in the selector: a name and lore, both written as MiniMessage.
///
/// `<!i>` leads every line because a client italicises a custom name and its
/// lore unless the component says otherwise, and Paper prepends the same tag
/// for the same reason.
fn selector_item(registry_key: &str, name: &str, lore: &[&str]) -> ItemStack {
	let stack = ItemStack::new(registry_key, 1);

	stack.set_custom_name(Some(markup(&format!("<!i>{name}"))));

	if !lore.is_empty() {
		stack.set_lore(
			lore.iter()
				.map(|line| markup(&format!("<!i>{line}")))
				.collect(),
		);
	}

	stack
}

/// Top up the effects that make a locked player unable to go anywhere.
///
/// Speed zero alone is not enough: a client predicts its own movement, so a
/// player pressing forward still slides before the server pulls them back.
/// Slowness at this amplifier stops the prediction, and the jump boost at 128
/// is the vanilla trick for cancelling jump entirely.
fn refresh_lock_effects(player: &Player) {
	for (effect, duration, amplifier) in [
		(StatusEffectType::Blindness, BLINDNESS_TICKS, 0),
		(StatusEffectType::Slowness, LOCK_EFFECT_TICKS, 10),
		(StatusEffectType::JumpBoost, LOCK_EFFECT_TICKS, 128),
	] {
		player.add_effect(StatusEffectInstance {
			effect_type: effect,
			duration,
			amplifier,
			ambient: false,
			show_particles: false,
			show_icon: false,
		});
	}
}

/// Empty a locked player's inventory, as `clearUnauthorizedInventory` does.
///
/// The slot numbers are the vanilla player container: 0-35 is the main
/// inventory and the hotbar, 36-39 the armour, 40 the off hand.
fn clear_inventory(player: &Player) {
	for slot in 0..=40u8 {
		player.set_inventory_item(slot, None);
	}
}

/// A speed that would leave the player stuck, replaced by the default.
fn usable_speed(value: f32, fallback: f32) -> f32 {
	if value > 0.0 {
		return value;
	}

	fallback
}

/// Read the auth spawn, treating an unreadable file as "no spawn set".
fn read_spawn(path: &std::path::Path) -> SpawnPoint {
	let Ok(body) = std::fs::read_to_string(path) else {
		return SpawnPoint::default();
	};

	match toml::from_str(&body) {
		Ok(point) => point,
		Err(error) => {
			// falling back to no spawn keeps players where they logged in, which
			// is strictly safer than teleporting them somewhere half-parsed
			tracing::warn!("auth-spawn không hợp lệ tại {}: {error}", path.display());

			SpawnPoint::default()
		}
	}
}

/// Write the auth spawn out, creating the data folder if it is missing.
fn write_spawn(path: &std::path::Path, point: &SpawnPoint) -> std::io::Result<()> {
	let body = toml::to_string_pretty(point)
		.map_err(|error| std::io::Error::other(error.to_string()))?;

	if let Some(parent) = path.parent() {
		std::fs::create_dir_all(parent)?;
	}

	std::fs::write(path, body)
}

/// Milliseconds since the epoch, which is what every throttle here compares.
fn now_millis() -> u64 {
	crate::state::now_millis()
}


