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
mod config;
mod countdown;
mod http;
mod permissions;
mod state;
mod tabbridge;

use countdown::Countdowns;
use luna_core_api::countdown::{parse_time, readable_time};
use luna_core_api::envelope::PluginMessageEnvelope;
use luna_core_api::identity::BackendIdentity;
use pumpkin_plugin_api::command::{ArgumentType, Command, CommandNode, StringType};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::command_wit::{Arg, CommandError, CommandSender, ConsumedArgs, Number};
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::events::{
	EventData, EventHandler, EventPriority, PlayerCustomPayloadEvent, PlayerJoinEvent,
	PlayerLeaveEvent,
};
use pumpkin_plugin_api::scheduler::SchedulerExt;
use pumpkin_plugin_api::{Context, Plugin, PluginMetadata, Server, register_plugin};
use state::CoreState;
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

		// TAB draws the player list on the proxy but every value in it is known
		// only here, so the bridge is what makes a Pumpkin backend look like the
		// rest of the cluster in the tab list
		let bridge = Arc::new(TabBridge::new(store));
		register_tab_bridge(&context, Arc::clone(&bridge));

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

		let identity = BackendIdentity::new(
			config.heartbeat.server_name.clone(),
			config.heartbeat.server_port,
		);

		tracing::info!(
			"Luna Core đang khởi động cho backend '{}' (cổng {}).",
			identity.name(),
			config.heartbeat.server_port
		);

		if !config.heartbeat.enabled {
			tracing::warn!("Heartbeat đang tắt trong config; backend sẽ không lên console.");
			return Ok(());
		}

		let state = Arc::new(CoreState::new(config, identity));
		let interval_ticks = state.config().heartbeat.interval_seconds.max(1) * TICKS_PER_SECOND;

		// the first beat waits a second: the proxy may not be listening yet, and a
		// refused connection during boot is noise rather than information
		let beat_state = Arc::clone(&state);
		context.schedule_repeating_task(TICKS_PER_SECOND, interval_ticks, move |server| {
			beat_state.publish(&server);
		});

		// every tick, not every beat: the broker's futures only advance while this
		// is running, so the pump interval *is* the messaging latency
		let pump_state = Arc::clone(&state);
		context.schedule_repeating_task(TICKS_PER_SECOND, 1, move |_server| {
			for body in pump_state.pump_messaging() {
				dispatch(&body);
			}
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

/// Hand one delivered body to whatever module owns its channel.
///
/// The routing table is empty until the modules that consume channels are
/// ported; an unrecognised channel is logged rather than dropped silently, so a
/// message arriving with nothing to receive it is visible rather than a mystery.
fn dispatch(body: &[u8]) {
	let envelope = match PluginMessageEnvelope::decode(body) {
		Ok(envelope) => envelope,
		Err(error) => {
			tracing::warn!("Gói tin AMQP không hợp lệ ({} byte): {error:?}", body.len());
			return;
		}
	};

	tracing::debug!(
		"Chưa có module nào nhận kênh '{}' từ '{}'.",
		envelope.channel,
		envelope.source_server_name
	);
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

/// Route TAB's channel and player departures into the bridge.
fn register_tab_bridge(context: &Context, bridge: Arc<TabBridge>) {
	let payload = context.register_event_handler::<PlayerCustomPayloadEvent, _>(
		PayloadRoute {
			bridge: Arc::clone(&bridge),
		},
		EventPriority::Normal,
		// TAB waits on the answers, so the handler has to run before the server
		// moves on from the payload
		true,
	);

	if let Err(error) = payload {
		tracing::error!("Không đăng ký được TAB bridge: {error}; tab list sẽ thiếu dữ liệu.");
		return;
	}

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

/// Hands TAB's payloads to the bridge.
struct PayloadRoute {
	bridge: Arc<TabBridge>,
}

impl EventHandler<PlayerCustomPayloadEvent> for PayloadRoute {
	fn handle(
		&self,
		_server: Server,
		event: EventData<PlayerCustomPayloadEvent>,
	) -> EventData<PlayerCustomPayloadEvent> {
		if event.channel == tabbridge::CHANNEL {
			self.bridge.handle(&event.player, &event.data);
		}

		event
	}
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
