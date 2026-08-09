//! Luna's cluster core for Pumpkin backends.
//!
//! What makes a server a member of the cluster rather than a standalone one:
//! it publishes a heartbeat the proxy and the console read, and it takes its
//! identity back from that same exchange. Everything platform-free - the wire
//! formats, the identity rule - lives in `luna-core-api`, exactly as it does on
//! the JVM side, so a Pumpkin backend and a Paper one cannot drift apart.
//!
//! The shape here is dictated by the sandbox. A plugin's exports are synchronous
//! and a component has no threads, so nothing runs between host calls: the beat
//! is driven by the server's own scheduler rather than by a background thread
//! the way every JVM platform does it.

mod amqp;
mod auth;
mod config;
mod countdown;
mod http;
mod messaging;
mod messenger;
mod permissions;
mod selector;
mod state;
mod tabbridge;
mod text;

use amqp::AmqpTransport;
use auth::{AuthGuard, Refusal};
use countdown::Countdowns;
use messaging::{Dispatch, MessageBus};
use crate::text::markup;
use luna_core_api::auth::AuthRequest;
use luna_core_api::palette::{Argument, color, usage};
use luna_core_api::messenger::CommandType;
use messenger::Messenger;
use selector::Selector;
use pumpkin_plugin_api::player::Player;
use luna_core_api::countdown::{parse_time, readable_time};
use luna_core_api::identity::BackendIdentity;
use pumpkin_plugin_api::command::{ArgumentType, Command, CommandNode, StringType};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::command_wit::{Arg, CommandError, CommandSender, ConsumedArgs, Number};
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::events_wit::{
	BlockBreakEventData, BlockPlaceEventData, PlayerDropItemEventData,
	PlayerInteractEntityEventData, PlayerInteractEventData, PlayerMoveEventData,
};
use pumpkin_plugin_api::events::{
	BlockBreakEvent, BlockPlaceEvent, EventData, EventHandler, EventPriority, FromIntoEvent,
	InventoryClickEvent, InventoryDragEvent, InventoryOpenEvent,
	PlayerChatEvent, PlayerCommandSendEvent, PlayerCustomPayloadEvent, PlayerDropItemEvent,
	PlayerInteractEntityEvent, PlayerInteractEvent, PlayerJoinEvent,
	PlayerLeaveEvent, PlayerMoveEvent,
};
use pumpkin_plugin_api::scheduler::SchedulerExt;
use pumpkin_plugin_api::{Context, Plugin, PluginMetadata, Server, register_plugin};
use state::{CoreState, now_millis};
use std::sync::Arc;
use tabbridge::TabBridge;

/// Ticks per second the server runs at, for turning an interval into ticks.
const TICKS_PER_SECOND: u64 = 20;

/// The capability list, read from the file the build also ships beside the wasm.
///
/// Declaring these in one place is not tidiness: Pumpkin compares an operator's
/// cached consent against this exact list, so a copy that drifted would silently
/// re-prompt on a console nobody is watching.
/// What pumpkin knows this component as.
///
/// It is not only a label: pumpkin namespaces a plugin's command permissions
/// with it, so the gate in `permissions` has to strip the same string back off.
pub const PLUGIN_NAME: &str = "luna-core";

const PERMISSIONS: &str = include_str!("../permissions.toml");

/// What `permissions.toml` holds.
#[derive(serde::Deserialize)]
struct Permissions {
	permissions: Vec<String>,
}

/// The declared capabilities, in the order the file lists them.
fn permissions() -> Vec<String> {
	toml::from_str::<Permissions>(PERMISSIONS)
		.map(|file| file.permissions)
		.unwrap_or_default()
}

/// The running state, kept so unload can close the broker connection.
#[derive(Default)]
struct LunaCore {
	state: Option<Arc<CoreState>>,
}

impl Plugin for LunaCore {
	fn new() -> Self {
		Self::default()
	}

	fn metadata(&self) -> PluginMetadata {
		PluginMetadata {
			name: PLUGIN_NAME.into(),
			version: env!("CARGO_PKG_VERSION").into(),
			authors: vec!["Belikhun".into()],
			description: "Luna cluster core for Pumpkin backends.".into(),
			dependencies: vec![],
			permissions: permissions(),
		}
	}

	fn on_load(&mut self, context: Context) -> Result<(), String> {
		let data_folder = context.get_data_folder();
		let (config, note) = config::CoreConfig::load_or_create(&data_folder);

		if let Some(message) = note {
			tracing::warn!("{message}");
		}

		// before the heartbeat, and independent of it: permissions have to answer
		// whether or not this backend ever reaches the proxy
		let store = permissions::load(&data_folder);
		permissions::register(&context, Arc::clone(&store));

		let identity = BackendIdentity::new(
			config.heartbeat.server_name.clone(),
			config.heartbeat.server_port,
		);

		// the broker belongs to messaging rather than to the beat, which only
		// hands it the settings the proxy publishes; sharing it is what lets the
		// bus exist on a backend whose heartbeat is switched off
		let transport = Arc::new(AmqpTransport::new());
		let bus = Arc::new(MessageBus::new(
			Arc::clone(&transport),
			identity.clone(),
			config.logging.plugin_messaging_enabled,
		));

		register_bus(&context, Arc::clone(&bus));

		// Before anything else that touches a player, because Pumpkin fires
		// handlers in registration order: chat from somebody who has not logged
		// in has to be refused here rather than reach the messenger.
		let guard = config.auth.enabled.then(|| {
			let guard = AuthGuard::new(Arc::clone(&bus), &config.auth, &data_folder);

			register_auth(&context, Arc::clone(&guard));

			guard
		});

		if guard.is_none() {
			tracing::warn!("Auth đang tắt trong config; người chơi vào thẳng không cần đăng nhập.");
		}

		// TAB draws the player list on the proxy but every value in it is known
		// only here, so the bridge is what makes a Pumpkin backend look like the
		// rest of the cluster in the tab list
		let bridge = Arc::new(TabBridge::new(Arc::clone(&store), Arc::clone(&bus)));
		register_tab_bridge(&context, &bus, Arc::clone(&bridge));

		// the messenger's own state is all on the proxy; this side publishes
		// presence, forwards what a player typed, and renders what comes back
		let messenger = Messenger::new(
			Arc::clone(&bus),
			Arc::clone(&store),
			now_millis(),
			&identity.name(),
		);

		register_messenger(&context, Arc::clone(&messenger));

		let countdowns = Arc::new(Countdowns::new());
		register_countdown(&context, Arc::clone(&countdowns));

		// a boss bar is per viewer, so somebody joining mid-countdown sees nothing
		// at all until they are added to the bars already running
		let joining = Arc::clone(&countdowns);
		let joined = context.register_event_handler::<PlayerJoinEvent, _>(
			CountdownJoin { countdowns: joining },
			EventPriority::Normal,
			false,
		);

		if let Err(error) = joined {
			tracing::warn!("Không theo dõi được người chơi vào cho countdown: {error}");
		}

		// a second is well inside the ten a request gets, and a timeout is the one
		// thing a player waiting on a silent proxy has no other sign of
		let sweeping = Arc::clone(&messenger);
		context.schedule_repeating_task(TICKS_PER_SECOND, TICKS_PER_SECOND, move |server| {
			sweeping.tick(&server);
		});

		// the prompt, the spawn and the lock effects all need topping up, and
		// each carries its own throttle inside; a second is what paces the boss
		// bar, and everything slower than that simply skips most ticks
		if let Some(guard) = guard.clone() {
			context.schedule_repeating_task(TICKS_PER_SECOND, TICKS_PER_SECOND, move |server| {
				guard.tick(&server);
			});
		}

		// every tick, beside the countdown: a warmup window closes on its own, and
		// what was held back for it has nothing else to prompt it out
		let flushing = Arc::clone(&bus);
		context.schedule_repeating_task(TICKS_PER_SECOND, 1, move |server| {
			flushing.flush_pending(&server);
		});

		// every tick: inside five minutes the bar shows tenths of a second, and at
		// one redraw a second that digit visibly stutters. The deadline itself is
		// wall-clock, so this only decides how smoothly the bar moves.
		let ticking = Arc::clone(&countdowns);
		context.schedule_repeating_task(TICKS_PER_SECOND, 1, move |server| {
			ticking.tick(&server);
		});

		if config.heartbeat.forwarding_secret.trim().is_empty() {
			// without it every request is refused, and the operator would only see
			// a silent absence from the console
			tracing::error!(
				"Thiếu heartbeat.forwardingSecret trong {data_folder}/config.toml; \
				 backend sẽ không xuất hiện trên console."
			);
		}

		tracing::info!(
			"Luna Core đang khởi động cho backend '{}' (cổng {}).",
			identity.name(),
			config.heartbeat.server_port
		);

		if !config.heartbeat.enabled {
			tracing::warn!("Heartbeat đang tắt trong config; backend sẽ không lên console.");
			return Ok(());
		}

		let state = Arc::new(CoreState::new(config, identity, transport, data_folder.clone()));
		let interval_ticks = state.config().heartbeat.interval_seconds.max(1) * TICKS_PER_SECOND;

		// after the state, because the menu is drawn from the registry the
		// heartbeat fills; before anything else needs it
		let selector = Selector::new(Arc::clone(&bus), state.registry());

		register_selector(&context, Arc::clone(&selector));

		// One click handler for both menus, and it has to stay that way: two
		// blocking handlers on this event mean the second one reads a blanked
		// `window-type`. `InventoryClicks` carries the whole story.
		let clicked = context.register_event_handler::<InventoryClickEvent, _>(
			InventoryClicks {
				guard: guard.clone(),
				selector: Arc::clone(&selector),
			},
			EventPriority::Normal,
			// the items are not real and must not be takeable, which only a
			// handler the server waits for can guarantee
			true,
		);

		if let Err(error) = clicked {
			tracing::error!(
				"Không đăng ký được click inventory: {error}; \
				 menu sẽ mở nhưng không bấm được."
			);
		}

		// Every tick, and it has to be a tick rather than a click: a window can
		// only be opened from here (`selector.rs` explains why), so this is what
		// makes paging, the dashboard and the auth mode selector work at all. A
		// slower period would be visible as lag on every button in the menu.
		let drawing_selector = Arc::clone(&selector);
		let drawing_guard = guard.clone();

		context.schedule_repeating_task(TICKS_PER_SECOND, 1, move |server| {
			drawing_selector.tick(&server);

			if let Some(guard) = drawing_guard.as_ref() {
				guard.draw_selectors(&server);
			}
		});

		// the first beat waits a second: the proxy may not be listening yet, and a
		// refused connection during boot is noise rather than information
		let beat_state = Arc::clone(&state);
		let beat_selector = Arc::clone(&selector);
		context.schedule_repeating_task(TICKS_PER_SECOND, interval_ticks, move |server| {
			beat_state.publish(&server);

			// beside the beat rather than on its own timer: the menu changes
			// when an operator reloads the proxy, which is exactly as often as
			// the registry does, and an unchanged body costs one 304-shaped read
			if let Some(payload) = beat_state.fetch_selector_config() {
				beat_selector.apply_payload(&payload);
			}
		});

		// every tick, not every beat: the broker's futures only advance while this
		// is running, so the pump interval *is* the messaging latency
		let pump_state = Arc::clone(&state);
		let pump_bus = Arc::clone(&bus);
		context.schedule_repeating_task(TICKS_PER_SECOND, 1, move |server| {
			for body in pump_state.pump_messaging() {
				pump_bus.dispatch_envelope(&server, &body);
			}
		});

		let naming = Arc::clone(&messenger);
		let named_state = Arc::clone(&state);
		context.schedule_repeating_task(TICKS_PER_SECOND, TICKS_PER_SECOND, move |_server| {
			// the proxy's name for this backend arrives with the first beat, and
			// a request stamped with the locally configured one would be routed
			// back to a server the proxy does not believe exists
			naming.set_server_name(&named_state.identity().name());
		});

		tracing::info!(
			"Đã bật heartbeat tới proxy endpoint={} mỗi {}s.",
			state.config().heartbeat.endpoint,
			state.config().heartbeat.interval_seconds
		);

		// TAB asks for a refresh interval per placeholder; a second is well inside
		// the shortest it asks for, and only differences are actually sent
		let refresh_bridge = Arc::clone(&bridge);
		context.schedule_repeating_task(TICKS_PER_SECOND, TICKS_PER_SECOND, move |server| {
			for player in server.get_all_players() {
				refresh_bridge.refresh(&player);
			}
		});

		self.state = Some(state);

		Ok(())
	}

	fn on_unload(&mut self, _context: Context) -> Result<(), String> {
		// the scheduler drops this plugin's tasks with it, but the broker holds a
		// socket and a consumer that outlive them
		if let Some(state) = self.state.take() {
			state.shutdown();
		}

		tracing::info!("Luna Core đã dừng.");
		Ok(())
	}
}

/// Route clicks to the selector.
///
/// There is no `/servers` here: the command lives on the proxy, which is the
/// only side that knows the whole network. It reaches this backend as an open
/// frame on the selector's own channel.
///
/// There is also **no inventory-close handler**, and there must not be one
/// anywhere in this plugin; see [`crate::selector`] for why.
fn register_selector(context: &Context, selector: Arc<Selector>) {
	let leaving = context.register_event_handler::<PlayerLeaveEvent, _>(
		SelectorLeave { selector },
		EventPriority::Normal,
		false,
	);

	if let Err(error) = leaving {
		tracing::warn!("Không theo dõi được người chơi rời đi cho server selector: {error}");
	}
}

/// Every inventory click, routed to whichever menu the player has open.
///
/// This is **one** handler on purpose. Pumpkin hands a blocking handler the
/// event, takes back what it returns, and rebuilds the host's copy from it -
/// and that rebuild writes `window-type: none`, `clicked-item: none` and
/// `cursor: none`, because a plugin is not allowed to change them. So the
/// second blocking handler on this event sees a blanked copy of those three
/// fields, whatever the first one did. Two handlers meant the selector always
/// read `window-type` as `none`, decided its menu had been closed, and dropped
/// the session on the player's first click; every button after that was dead.
/// One handler sees the event as the server built it.
struct InventoryClicks {
	guard: Option<Arc<AuthGuard>>,
	selector: Arc<Selector>,
}

impl EventHandler<InventoryClickEvent> for InventoryClicks {
	fn handle(
		&self,
		server: Server,
		mut event: EventData<InventoryClickEvent>,
	) -> EventData<InventoryClickEvent> {
		let id = event.player.get_id().to_string();

		// A click with no window happened in the player's own inventory, which
		// is the only sign either menu gets that it was closed; nothing can tell
		// them directly (see `crate::selector`).
		let windowless = event.window_type.is_none();

		if let Some(guard) = self.guard.as_ref() {
			if guard.selector_is_open(&id) {
				event.cancelled = true;

				if windowless {
					// Paper's answer to a player dismissing the mode selector
					// without choosing is to put it straight back
					guard.on_selector_closed(&event.player);
				} else {
					guard.on_selector_click(&event.player, event.raw_slot);
				}

				return event;
			}

			if !guard.is_authenticated(&id) {
				guard.refuse(&event.player, Refusal::Interaction);
				event.cancelled = true;

				return event;
			}
		}

		if !self.selector.is_open(&id) {
			return event;
		}

		if windowless {
			self.selector.forget(&id);

			return event;
		}

		// nothing in the menu is a real item, so every click in it is cancelled
		// whether or not it lands on something that does anything
		event.cancelled = true;
		self.selector.on_click(&server, &event.player, event.raw_slot);

		event
	}
}

/// Drops a player's page when they leave.
struct SelectorLeave {
	selector: Arc<Selector>,
}

impl EventHandler<PlayerLeaveEvent> for SelectorLeave {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerLeaveEvent>,
	) -> EventData<PlayerLeaveEvent> {
		self.selector.forget(&event.player.get_id().to_string());

		event
	}
}

/// Register the messenger's commands and the events that feed it.
///
/// Chat is intercepted rather than observed: every message goes to the proxy so
/// it can decide who hears it, which is the whole point of a network chat, and
/// letting the server also broadcast it locally would show it twice to everyone
/// on this backend and once to everybody else.
fn register_messenger(context: &Context, messenger: Arc<Messenger>) {
	let handler = |branch| MessengerCommand {
		messenger: Arc::clone(&messenger),
		branch,
	};

	for (name, branch, description) in [
		("nw", MessengerBranch::Network, "Chuyển sang kênh chat toàn mạng."),
		("sv", MessengerBranch::Server, "Chuyển sang kênh chat máy chủ này."),
	] {
		// the root executes, with no child node: these take no arguments, and a
		// literal child of the same name would only fire as `/nw nw`
		let command =
			Command::new(&[name.to_owned()], description).execute(handler(branch));

		context.register_command(command, MESSENGER_PERMISSION);
	}

	// /poke <target>
	let poke_target = CommandNode::argument("target", &ArgumentType::String(StringType::SingleWord))
		.execute(handler(MessengerBranch::Poke));
	let poke = Command::new(&["poke".to_owned()], "Chọc một người chơi trong mạng.");

	poke.then(poke_target);
	context.register_command(poke, MESSENGER_PERMISSION);

	// /msg <target> [message]; the bare target switches the conversation to them
	let msg_body = CommandNode::argument("message", &ArgumentType::String(StringType::Greedy))
		.execute(handler(MessengerBranch::Direct));
	let msg_target = CommandNode::argument("target", &ArgumentType::String(StringType::SingleWord))
		.execute(handler(MessengerBranch::SwitchDirect));

	msg_target.then(msg_body);

	let msg = Command::new(&["msg".to_owned()], "Nhắn riêng cho một người chơi.");

	msg.then(msg_target);
	context.register_command(msg, MESSENGER_PERMISSION);

	// /r <message>
	let reply_body = CommandNode::argument("message", &ArgumentType::String(StringType::Greedy))
		.execute(handler(MessengerBranch::Reply));
	let reply = Command::new(&["r".to_owned()], "Trả lời tin nhắn riêng gần nhất.");

	reply.then(reply_body);
	context.register_command(reply, MESSENGER_PERMISSION);

	let chat = context.register_event_handler::<PlayerChatEvent, _>(
		ChatRoute {
			messenger: Arc::clone(&messenger),
		},
		EventPriority::Normal,
		// the event has to be cancelled before the server broadcasts it, which
		// only a blocking handler can do
		true,
	);

	if let Err(error) = chat {
		tracing::error!(
			"Không chuyển được chat sang messenger: {error}; chat sẽ chỉ ở máy chủ này."
		);
	}

	let joined = context.register_event_handler::<PlayerJoinEvent, _>(
		PresenceJoin {
			messenger: Arc::clone(&messenger),
		},
		EventPriority::Normal,
		false,
	);

	if let Err(error) = joined {
		tracing::warn!("Không báo được người chơi vào cho messenger: {error}");
	}

	let left = context.register_event_handler::<PlayerLeaveEvent, _>(
		PresenceLeave { messenger },
		EventPriority::Normal,
		false,
	);

	if let Err(error) = left {
		tracing::warn!("Không báo được người chơi rời đi cho messenger: {error}");
	}
}

/// Everyone may talk; what they may say is the proxy's business.
const MESSENGER_PERMISSION: &str = "messenger.use";

/// Which of the messenger's commands a handler was registered under.
#[derive(Clone, Copy)]
enum MessengerBranch {
	Network,
	Server,
	SwitchDirect,
	Direct,
	Poke,
	Reply,
}

/// Runs whichever messenger command the player typed.
struct MessengerCommand {
	messenger: Arc<Messenger>,
	branch: MessengerBranch,
}

impl CommandHandler for MessengerCommand {
	fn handle(
		&self,
		sender: CommandSender,
		server: Server,
		args: ConsumedArgs,
	) -> Result<i32, CommandError> {
		let Some(player) = sender_player(&sender, &server) else {
			sender.send_message(TextComponent::text("❌ Lệnh này chỉ dùng cho người chơi."));

			return Ok(0);
		};

		let target = word(&args, "target");
		let message = word(&args, "message").unwrap_or_default();

		let (command_type, argument, failure) = match self.branch {
			MessengerBranch::Network => (
				CommandType::SwitchNetwork,
				String::new(),
				"❌ Không thể chuyển sang kênh mạng lúc này.",
			),
			MessengerBranch::Server => (
				CommandType::SwitchServer,
				String::new(),
				"❌ Không thể chuyển sang kênh máy chủ lúc này.",
			),
			MessengerBranch::SwitchDirect => (
				CommandType::SwitchDirect,
				target.clone().unwrap_or_default(),
				"❌ Không thể chuyển sang nhắn tin trực tiếp lúc này.",
			),
			MessengerBranch::Direct => (
				CommandType::SendDirect,
				message,
				"❌ Không thể gửi tin nhắn lúc này.",
			),
			MessengerBranch::Poke => (
				CommandType::SendPoke,
				target.clone().unwrap_or_default(),
				"❌ Không thể gửi yêu cầu chọc lúc này.",
			),
			MessengerBranch::Reply => (
				CommandType::SendReply,
				message,
				"❌ Không thể gửi tin nhắn trả lời lúc này.",
			),
		};

		let sent = self
			.messenger
			.send_command(&player, command_type, &argument, target.as_deref());

		if !sent {
			sender.send_message(TextComponent::text(failure));
		}

		Ok(0)
	}
}

/// Sends every chat message to the proxy instead of broadcasting it here.
struct ChatRoute {
	messenger: Arc<Messenger>,
}

impl EventHandler<PlayerChatEvent> for ChatRoute {
	fn handle(
		&self,
		_server: Server,
		mut event: EventData<PlayerChatEvent>,
	) -> EventData<PlayerChatEvent> {
		// auth registers its chat handler first and cancels for anybody who has
		// not logged in; forwarding it anyway would put their words on the whole
		// network on the strength of a name they have not proved
		if event.cancelled {
			return event;
		}

		let message = event.message.trim().to_owned();

		if message.is_empty() {
			return event;
		}

		event.cancelled = true;

		let sent = self
			.messenger
			.send_command(&event.player, CommandType::SendChat, &message, None);

		if !sent {
			event.player.send_system_message(
				TextComponent::text("❌ Không thể gửi chat messenger lúc này."),
				false,
			);
		}

		event
	}
}

/// Tells the network a player arrived.
struct PresenceJoin {
	messenger: Arc<Messenger>,
}

impl EventHandler<PlayerJoinEvent> for PresenceJoin {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerJoinEvent>,
	) -> EventData<PlayerJoinEvent> {
		// whether this is their first ever join is the proxy's record to keep,
		// not something a backend can answer
		self.messenger.publish_join(&event.player, false);

		event
	}
}

/// Tells the network a player left.
struct PresenceLeave {
	messenger: Arc<Messenger>,
}

impl EventHandler<PlayerLeaveEvent> for PresenceLeave {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerLeaveEvent>,
	) -> EventData<PlayerLeaveEvent> {
		self.messenger.publish_leave(&event.player);

		event
	}
}

/// The player a command came from, when it came from one.
fn sender_player(sender: &CommandSender, server: &Server) -> Option<Player> {
	let name = sender.get_name();

	server
		.get_all_players()
		.into_iter()
		.find(|player| player.get_name() == name)
}

/// The permission an operator needs to run `/countdown`.

///
/// The node is the Paper plugin's, unchanged. It is what LuckPerms already holds
/// for the JVM backends, and a group that grants the command on one server has
/// to grant it on all of them; inventing a tidier `luna.countdown.*` here would
/// make the same group mean two different things.
const COUNTDOWN_PERMISSION: &str = "countdown.countdown";

/// Register `/countdown start|stop|stopall|list`.
fn register_countdown(context: &Context, countdowns: Arc<Countdowns>) {
	let handler = |branch| CountdownCommand {
		countdowns: Arc::clone(&countdowns),
		branch,
	};

	let title = CommandNode::argument("title", &ArgumentType::String(StringType::Greedy))
		.execute(handler(Branch::Start));

	// a word rather than a number: the JVM takes "30s", "5m" and "2h" as well as
	// a bare count of seconds, and an integer argument would reject all but one
	let length = CommandNode::argument("length", &ArgumentType::String(StringType::SingleWord))
		.execute(handler(Branch::Start));

	length.then(title);

	let start = CommandNode::literal("start");
	start.then(length);

	let id =
		CommandNode::argument("id", &ArgumentType::Integer((Some(1), None))).execute(handler(Branch::Stop));
	let stop = CommandNode::literal("stop");

	stop.then(id);

	let stop_all = CommandNode::literal("stopall").execute(handler(Branch::StopAll));
	let list = CommandNode::literal("list").execute(handler(Branch::List));

	let command = Command::new(
		&["countdown".to_owned()],
		"Đếm ngược tới một sự kiện, thông báo cho cả server.",
	);

	command.then(start);
	command.then(stop);
	command.then(stop_all);
	command.then(list);

	context.register_command(command, COUNTDOWN_PERMISSION);
}

/// Which branch of `/countdown` a handler was registered under.
///
/// `stopall` and `list` are both bare literals carrying no arguments, so the
/// arguments alone cannot tell them apart; the branch is recorded at
/// registration instead of being guessed at from what arrived.
#[derive(Clone, Copy)]
enum Branch {
	Start,
	Stop,
	StopAll,
	List,
}

/// Runs whichever branch of `/countdown` the operator typed.
///
/// One handler type for every branch: they differ by a few lines each, and
/// splitting them would mean four copies of the same `Arc` plumbing.
struct CountdownCommand {
	countdowns: Arc<Countdowns>,
	branch: Branch,
}

impl CommandHandler for CountdownCommand {
	fn handle(
		&self,
		sender: CommandSender,
		server: Server,
		args: ConsumedArgs,
	) -> Result<i32, CommandError> {
		match self.branch {
			Branch::Start => self.start(&sender, &server, &args),
			Branch::Stop => self.stop(&sender, &server, &args),
			Branch::StopAll => self.stop_all(&sender, &server),
			Branch::List => self.list(&sender),
		}

		Ok(0)
	}
}

impl CountdownCommand {
	/// `/countdown start <length> [title]`
	fn start(&self, sender: &CommandSender, server: &Server, args: &ConsumedArgs) {
		let length = word(args, "length").unwrap_or_default();

		let Some(seconds) = parse_time(&length) else {
			sender.send_message(TextComponent::text(&format!(
				"Thời gian không hợp lệ: {length}"
			)));

			return;
		};

		let title = word(args, "title").unwrap_or_default();
		let id = self.countdowns.start(server, &title, seconds);

		sender.send_message(TextComponent::text(&format!("Đã tạo countdown #{id}.")));
	}

	/// `/countdown stop <id>`
	fn stop(&self, sender: &CommandSender, server: &Server, args: &ConsumedArgs) {
		let Some(id) = integer(args, "id") else {
			return;
		};

		let stopped = self.countdowns.stop(server, id as u32, None);

		sender.send_message(TextComponent::text(if stopped {
			"Đã hủy countdown."
		} else {
			"Không có countdown nào với id đó."
		}));
	}

	/// `/countdown stopall`
	fn stop_all(&self, sender: &CommandSender, server: &Server) {
		let stopped = self.countdowns.stop_all(server, None);

		sender.send_message(TextComponent::text(&format!("Đã hủy {stopped} countdown.")));
	}

	/// `/countdown list`
	fn list(&self, sender: &CommandSender) {
		let active = self.countdowns.active();

		if active.is_empty() {
			sender.send_message(TextComponent::text("Không có countdown nào đang chạy."));

			return;
		}

		for (id, title, remaining) in active {
			sender.send_message(TextComponent::text(&format!(
				"#{id} {title} - còn {}",
				readable_time(remaining).text
			)));
		}
	}
}

/// One string argument, or `None` when this branch did not carry it.
fn word(args: &ConsumedArgs, key: &str) -> Option<String> {
	match args.get_value(key) {
		Arg::Simple(text) | Arg::Msg(text) => Some(text),
		_ => None,
	}
}

/// One integer argument, or `None` when this branch did not carry it.
fn integer(args: &ConsumedArgs, key: &str) -> Option<i64> {
	match args.get_value(key) {
		Arg::Num(Ok(number)) => Some(match number {
			Number::Int32(value) => i64::from(value),
			Number::Int64(value) => value,
			Number::Float32(value) => value as i64,
			Number::Float64(value) => value as i64,
		}),
		_ => None,
	}
}

/// Shows the running countdown bars to a player who just arrived.
struct CountdownJoin {
	countdowns: Arc<Countdowns>,
}

impl EventHandler<PlayerJoinEvent> for CountdownJoin {
	fn handle(
		&self,
		server: Server,
		event: EventData<PlayerJoinEvent>,
	) -> EventData<PlayerJoinEvent> {
		self.countdowns
			.show_to(&server, &event.player.get_id().to_string());

		event
	}
}

/// Feed the bus everything the server hears, and everyone it sees come and go.
///
/// This is the only place a custom payload is read: a module that wants one
/// registers a channel on the bus rather than another handler here, so the set
/// of channels the backend answers is one list instead of a scattering of `if`s.
fn register_bus(context: &Context, bus: Arc<MessageBus>) {
	let payload = context.register_event_handler::<PlayerCustomPayloadEvent, _>(
		PayloadRoute {
			bus: Arc::clone(&bus),
		},
		EventPriority::Normal,
		// TAB waits on the answers, so the handler has to run before the server
		// moves on from the payload
		true,
	);

	if let Err(error) = payload {
		tracing::error!(
			"Không đăng ký được plugin messaging: {error}; \
			 backend sẽ không nhận được gói tin nào."
		);
	}

	// the warmup window only means anything if the bus knows when somebody
	// arrived, and the map only stays bounded if it knows when they left
	let joined = context.register_event_handler::<PlayerJoinEvent, _>(
		SenderJoin {
			bus: Arc::clone(&bus),
		},
		EventPriority::Normal,
		false,
	);

	if let Err(error) = joined {
		tracing::warn!("Không theo dõi được người chơi vào cho plugin messaging: {error}");
	}

	let left = context.register_event_handler::<PlayerLeaveEvent, _>(
		SenderLeave { bus },
		EventPriority::Normal,
		false,
	);

	if let Err(error) = left {
		tracing::warn!("Không theo dõi được người chơi rời đi cho plugin messaging: {error}");
	}
}

/// Hands every custom payload to the bus.
struct PayloadRoute {
	bus: Arc<MessageBus>,
}

impl EventHandler<PlayerCustomPayloadEvent> for PayloadRoute {
	fn handle(
		&self,
		server: Server,
		event: EventData<PlayerCustomPayloadEvent>,
	) -> EventData<PlayerCustomPayloadEvent> {
		self.bus
			.dispatch_incoming(&server, Some(&event.player), &event.channel, &event.data);

		event
	}
}

/// Starts a player's send warmup window.
struct SenderJoin {
	bus: Arc<MessageBus>,
}

impl EventHandler<PlayerJoinEvent> for SenderJoin {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerJoinEvent>,
	) -> EventData<PlayerJoinEvent> {
		self.bus.bind_sender(&event.player.get_id().to_string());

		event
	}
}

/// Forgets a player who left.
struct SenderLeave {
	bus: Arc<MessageBus>,
}

impl EventHandler<PlayerLeaveEvent> for SenderLeave {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerLeaveEvent>,
	) -> EventData<PlayerLeaveEvent> {
		self.bus.unbind_sender(&event.player.get_id().to_string());

		event
	}
}

/// Put TAB's channel on the bus, and drop a player's state when they leave.
fn register_tab_bridge(context: &Context, bus: &Arc<MessageBus>, bridge: Arc<TabBridge>) {
	let listening = Arc::clone(&bridge);

	bus.register_incoming(tabbridge::CHANNEL, move |context| {
		let Some(player) = context.source else {
			// TAB's protocol is a conversation with one player's client; a
			// delivery with nobody attached has nothing to answer
			return Dispatch::PassThrough;
		};

		listening.handle(player, context.payload);

		Dispatch::Handled
	});

	// the bridge answers on the same channel it listens on
	bus.register_outgoing(tabbridge::CHANNEL);

	let leave = context.register_event_handler::<PlayerLeaveEvent, _>(
		LeaveRoute { bridge },
		EventPriority::Normal,
		false,
	);

	if let Err(error) = leave {
		// not fatal, but the map would grow for the lifetime of the server
		tracing::warn!("Không theo dõi được người chơi rời đi cho TAB bridge: {error}");
	}

	tracing::info!("Đã bật TAB bridge trên kênh {}.", tabbridge::CHANNEL);
}

/// Drops a player's bridge state when they leave.
struct LeaveRoute {
	bridge: Arc<TabBridge>,
}

impl EventHandler<PlayerLeaveEvent> for LeaveRoute {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerLeaveEvent>,
	) -> EventData<PlayerLeaveEvent> {
		self.bridge.forget(&event.player.get_id().to_string());

		event
	}
}

register_plugin!(LunaCore);

/// The permissions `/login` and `/register` need.
///
/// They exist so an operator can take the commands away from a compromised
/// account, not to gate them: a player who cannot run `/login` cannot get past
/// the lock, so the shipped default group grants both.
const AUTH_LOGIN_PERMISSION: &str = "auth.login";
const AUTH_REGISTER_PERMISSION: &str = "auth.register";

/// Register the auth commands and every restriction that enforces the lock.
///
/// Order matters here in a way it does not on the JVM. Pumpkin fires handlers
/// in **registration order** - `EventPriority` is recorded and then not used to
/// sort - so the only way to run before another handler is to register first.
/// That is why the caller wires auth before the messenger: a chat line from a
/// player who has not logged in must be refused here rather than forwarded to
/// the proxy as network chat.
fn register_auth(context: &Context, guard: Arc<AuthGuard>) {
	for (name, branch, description, permission) in [
		("login", AuthBranch::Login, "Đăng nhập tài khoản Luna Auth.", AUTH_LOGIN_PERMISSION),
		("l", AuthBranch::Login, "Đăng nhập tài khoản Luna Auth.", AUTH_LOGIN_PERMISSION),
		("register", AuthBranch::Register, "Đăng ký tài khoản Luna Auth.", AUTH_REGISTER_PERMISSION),
		("reg", AuthBranch::Register, "Đăng ký tài khoản Luna Auth.", AUTH_REGISTER_PERMISSION),
	] {
		let handler = AuthCommand {
			guard: Arc::clone(&guard),
			branch,
		};

		let password =
			CommandNode::argument("password", &ArgumentType::String(StringType::SingleWord));

		let password = match branch {
			AuthBranch::Login => password.execute(handler),
			AuthBranch::Register => {
				// both attempts are required, so only the second one runs: a
				// `/register hunter2` is a usage error rather than a password
				// confirmed against itself
				let confirm =
					CommandNode::argument("confirm", &ArgumentType::String(StringType::SingleWord))
						.execute(handler);

				password.then(confirm);
				password
			}
		};

		let command = Command::new(&[name.to_owned()], description);

		command.then(password);

		// the root executes too, so a bare `/login` answers with luna's usage
		// line the way Paper does; without it the argument tree refuses the
		// command itself and the player gets brigadier's English parse error
		let command = command.execute(AuthUsage { branch });

		context.register_command(command, permission);
	}

	// The lock itself, and only what the Paper listener cancels. Every one is
	// blocking, because a handler the server does not wait for cannot cancel
	// what it is about to do.
	//
	// Movement is deliberately absent. Paper does not cancel a move either: the
	// player is held by walk and fly speed zero, slowness and the spawn
	// teleport, and cancelling on top of that fights the client's own movement
	// prediction into a rubber-band. `AuthMoveWatch` still watches, because the
	// move is what re-asserts the lock between ticks.
	//
	// Item damage and hand swapping are absent for the same reason they are
	// no-ops on Paper for an unauthenticated player: those handlers exist there
	// only to protect lobby items, which this port has no registry for.
	restrict::<PlayerInteractEvent>(context, &guard, Refusal::Interaction);
	restrict::<PlayerInteractEntityEvent>(context, &guard, Refusal::Interaction);
	restrict::<PlayerDropItemEvent>(context, &guard, Refusal::Interaction);
	restrict::<BlockBreakEvent>(context, &guard, Refusal::Interaction);
	restrict::<BlockPlaceEvent>(context, &guard, Refusal::Interaction);

	register_blocking(context, "auth move", AuthMoveWatch { guard: Arc::clone(&guard) });
	register_blocking(context, "auth drag", AuthInventoryDrag { guard: Arc::clone(&guard) });

	register_blocking(context, "auth command", AuthCommandRoute { guard: Arc::clone(&guard) });
	register_blocking(context, "auth chat", AuthChatRoute { guard: Arc::clone(&guard) });
	register_blocking(context, "auth inventory", AuthInventoryOpen { guard: Arc::clone(&guard) });

	let joined = context.register_event_handler::<PlayerJoinEvent, _>(
		AuthJoin {
			guard: Arc::clone(&guard),
		},
		EventPriority::Normal,
		false,
	);

	if let Err(error) = joined {
		tracing::error!(
			"Không theo dõi được người chơi vào cho auth: {error}; \
			 người chơi sẽ vào server mà không bị khóa."
		);
	}

	let left = context.register_event_handler::<PlayerLeaveEvent, _>(
		AuthLeave { guard },
		EventPriority::Normal,
		false,
	);

	if let Err(error) = left {
		tracing::warn!("Không theo dõi được người chơi rời đi cho auth: {error}");
	}
}

/// Register one blocking handler, reporting a failure as the hole that it is.
fn register_blocking<E, H>(context: &Context, what: &str, handler: H)
where
	E: FromIntoEvent + Send + Sync + 'static,
	H: EventHandler<E> + Send + Sync + 'static,
{
	let registered = context.register_event_handler::<E, _>(handler, EventPriority::Normal, true);

	if let Err(error) = registered {
		// not a warning: each of these is one thing a locked player can still do
		tracing::error!("Không đăng ký được {what}: {error}; hạn chế này sẽ không có tác dụng.");
	}
}

/// Cancel one kind of event for anybody who has not authenticated.
fn restrict<E>(context: &Context, guard: &Arc<AuthGuard>, kind: Refusal)
where
	E: FromIntoEvent + Send + Sync + 'static,
	E::Data: Restrictable + Send + Sync,
{
	register_blocking::<E, _>(
		context,
		"auth restriction",
		Restrict::<E> {
			guard: Arc::clone(guard),
			kind,
			event: std::marker::PhantomData,
		},
	);
}

/// An event that names a player and can be called off.
///
/// Ten events carry the same two fields under ten type names, and the rule
/// applied to them is one sentence long. This is what lets that sentence be
/// written once rather than copied per event, which is how one of the copies
/// eventually ends up missing the check.
trait Restrictable {
	/// Who did it, when the event names somebody.
	fn actor(&self) -> Option<&Player>;
	fn cancel(&mut self);
}

macro_rules! restrictable {
	($($data:ty),* $(,)?) => {
		$(
			impl Restrictable for $data {
				fn actor(&self) -> Option<&Player> {
					Some(&self.player)
				}

				fn cancel(&mut self) {
					self.cancelled = true;
				}
			}
		)*
	};
}

restrictable!(
	PlayerInteractEventData,
	PlayerInteractEntityEventData,
	PlayerDropItemEventData,
	BlockPlaceEventData,
);

/// A block can also break without anybody breaking it.
impl Restrictable for BlockBreakEventData {
	fn actor(&self) -> Option<&Player> {
		self.player.as_ref()
	}

	fn cancel(&mut self) {
		self.cancelled = true;
	}
}

/// Refuses one kind of event to anybody who is not through the lock.
struct Restrict<E: FromIntoEvent> {
	guard: Arc<AuthGuard>,
	kind: Refusal,
	event: std::marker::PhantomData<E>,
}

impl<E> EventHandler<E> for Restrict<E>
where
	E: FromIntoEvent,
	E::Data: Restrictable,
{
	fn handle(&self, _server: Server, mut event: E::Data) -> E::Data {
		let Some(player) = event.actor() else {
			return event;
		};

		if self.guard.is_authenticated(&player.get_id().to_string()) {
			return event;
		}

		// re-asserted from here as well as from the tick: a client that predicts
		// its own movement is a second ahead of the next tick, and the lock is
		// what stops the prediction rather than the cancel
		self.guard.sync_lock_if_due(player);
		self.guard.refuse(player, self.kind);
		event.cancel();

		event
	}
}

/// Which auth command a handler was registered under.
#[derive(Clone, Copy)]
enum AuthBranch {
	Login,
	Register,
}

/// Answers a bare `/login` or `/register` with the usage line.
struct AuthUsage {
	branch: AuthBranch,
}

impl CommandHandler for AuthUsage {
	fn handle(
		&self,
		sender: CommandSender,
		_server: Server,
		_args: ConsumedArgs,
	) -> Result<i32, CommandError> {
		sender.send_message(markup(&usage_line(self.branch)));

		Ok(0)
	}
}

/// The usage line for one of the auth commands, worded as `CommandStrings` does.
///
/// Always the canonical name, never the alias the player typed: Paper hard-codes
/// `/login` and `/register` here, so somebody who ran `/reg` is told about
/// `/register` and learns the real command rather than being taught the alias.
fn usage_line(branch: AuthBranch) -> String {
	match branch {
		AuthBranch::Login => usage("/login", &[Argument::required("mat_khau", "text")]),
		AuthBranch::Register => usage(
			"/register",
			&[
				Argument::required("mat_khau", "text"),
				Argument::required("nhap_lai", "text"),
			],
		),
	}
}

/// Forwards `/login` and `/register` to the proxy, which decides them.
struct AuthCommand {
	guard: Arc<AuthGuard>,
	branch: AuthBranch,
}

impl CommandHandler for AuthCommand {
	fn handle(
		&self,
		sender: CommandSender,
		server: Server,
		args: ConsumedArgs,
	) -> Result<i32, CommandError> {
		let Some(player) = sender_player(&sender, &server) else {
			sender.send_message(markup(&format!(
				"<color:{danger}>❌ Lệnh này chỉ dùng trong game.</color>",
				danger = color::DANGER_500,
			)));

			return Ok(0);
		};

		let password = word(&args, "password").unwrap_or_default();
		let request = match self.branch {
			AuthBranch::Login => AuthRequest::Login { password },
			AuthBranch::Register => AuthRequest::Register {
				password,
				confirm: word(&args, "confirm").unwrap_or_default(),
			},
		};

		if !self.guard.request(&player, &request) {
			// the proxy holds the passwords, so a request that never left is the
			// difference between "wrong password" and "nothing happened"
			sender.send_message(markup(&format!(
				"<color:{danger}>❌ Không thể gửi yêu cầu lên proxy. \
				 Vui lòng thử lại sau vài giây.</color>",
				danger = color::DANGER_500,
			)));
		}

		Ok(0)
	}
}

/// Re-asserts the lock as a player moves, without refusing the move itself.
///
/// A client predicts its own movement, so between two ticks it has already
/// walked; the lock is what stops the prediction, and this is where it gets
/// re-applied often enough to matter. Paper's `onMove` does exactly this and
/// no more.
struct AuthMoveWatch {
	guard: Arc<AuthGuard>,
}

impl EventHandler<PlayerMoveEvent> for AuthMoveWatch {
	fn handle(&self, _server: Server, event: PlayerMoveEventData) -> PlayerMoveEventData {
		if self.guard.is_authenticated(&event.player.get_id().to_string()) {
			return event;
		}

		self.guard.sync_lock_if_due(&event.player);
		self.guard.note(&event.player, Refusal::Move);

		event
	}
}

/// Refuses a drag inside the mode selector, and nothing else.
///
/// Paper cancels a drag only when the top inventory is the selector, or when it
/// would move a lobby item; an unauthenticated player dragging in their own
/// inventory is left alone there, so it is left alone here.
struct AuthInventoryDrag {
	guard: Arc<AuthGuard>,
}

impl EventHandler<InventoryDragEvent> for AuthInventoryDrag {
	fn handle(
		&self,
		_server: Server,
		mut event: EventData<InventoryDragEvent>,
	) -> EventData<InventoryDragEvent> {
		if self.guard.selector_is_open(&event.player.get_id().to_string()) {
			event.cancelled = true;
		}

		event
	}
}

/// Refuses every command an unauthenticated player has not been allowed.
struct AuthCommandRoute {
	guard: Arc<AuthGuard>,
}

impl EventHandler<PlayerCommandSendEvent> for AuthCommandRoute {
	fn handle(
		&self,
		_server: Server,
		mut event: EventData<PlayerCommandSendEvent>,
	) -> EventData<PlayerCommandSendEvent> {
		if self.guard.is_authenticated(&event.player.get_id().to_string()) {
			return event;
		}

		if self.guard.command_permitted(&event.command) {
			return event;
		}

		self.guard.refuse(&event.player, Refusal::Command);
		event.cancelled = true;

		event
	}
}

/// Refuses chat from anybody who has not authenticated.
///
/// Registered before the messenger's own chat handler, which is what keeps an
/// unauthenticated line from being forwarded to the proxy as network chat.
struct AuthChatRoute {
	guard: Arc<AuthGuard>,
}

impl EventHandler<PlayerChatEvent> for AuthChatRoute {
	fn handle(&self, _server: Server, mut event: EventData<PlayerChatEvent>) -> EventData<PlayerChatEvent> {
		if self.guard.is_authenticated(&event.player.get_id().to_string()) {
			return event;
		}

		self.guard.refuse(&event.player, Refusal::Chat);
		event.cancelled = true;

		event
	}
}

/// Refuses every inventory an unauthenticated player did not open by choosing.
struct AuthInventoryOpen {
	guard: Arc<AuthGuard>,
}

impl EventHandler<InventoryOpenEvent> for AuthInventoryOpen {
	fn handle(&self, _server: Server, mut event: EventData<InventoryOpenEvent>) -> EventData<InventoryOpenEvent> {
		let id = event.player.get_id().to_string();

		if self.guard.is_authenticated(&id) || self.guard.selector_is_open(&id) {
			return event;
		}

		self.guard.refuse(&event.player, Refusal::Interaction);
		event.cancelled = true;

		event
	}
}

/// Locks a player the moment they arrive, and asks the proxy about them.
struct AuthJoin {
	guard: Arc<AuthGuard>,
}

impl EventHandler<PlayerJoinEvent> for AuthJoin {
	fn handle(&self, server: Server, event: EventData<PlayerJoinEvent>) -> EventData<PlayerJoinEvent> {
		self.guard.on_join(&server, &event.player);

		event
	}
}

/// Forgets everything keyed by a player who left.
struct AuthLeave {
	guard: Arc<AuthGuard>,
}

impl EventHandler<PlayerLeaveEvent> for AuthLeave {
	fn handle(&self, _server: Server, event: EventData<PlayerLeaveEvent>) -> EventData<PlayerLeaveEvent> {
		self.guard.on_leave(&event.player.get_id().to_string());

		event
	}
}
