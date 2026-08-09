//! The server selector, drawn from what the proxy publishes.
//!
//! The proxy owns the menu and this draws it: it pushes an `open-v8` frame when
//! its config loads or reloads, sends an open request when a player runs
//! `/servers`, and takes back the name of whatever was clicked. Nothing about
//! the server list is configured on this side, which is what lets a Pumpkin
//! backend show the same menu as a Paper one.
//!
//! The layout arrives once and is kept; the *statuses* come from the heartbeat
//! registry and change constantly, so the items are rendered per open rather
//! than cached. A menu already on screen is redrawn by reopening it, which is
//! also how paging works - there is no way to mutate a `gui` a player is
//! already looking at through `pumpkin:plugin@0.1.0`.
//!
//! # Why this plugin handles no inventory-close event
//!
//! Reopening is only possible because nothing here listens for a close, and
//! that is a hard rule for the whole plugin rather than a preference.
//!
//! Pumpkin dispatches an event to a wasm plugin by taking that plugin's store
//! mutex and holding it for the whole guest call. `open-gui` shuts whatever the
//! player already had open, and shutting it fires `inventory-close`. So a
//! handler that opens a GUI - a click on "next page", on the dashboard, on the
//! auth mode selector's remember toggle - would fire a close **into the plugin
//! it is already running inside**, and the dispatch would block on a mutex that
//! cannot be released until it returns. That is not a slow path: it is a
//! permanent deadlock which takes every later event with it, because the store
//! mutex is never handed back. It cost one player a `read timed out` before it
//! was understood.
//!
//! With no close handler registered, `fire()` finds nothing for the event and
//! returns before touching the store, so reopening is safe from anywhere. What
//! it costs is the close signal itself, and the replacement is in the click
//! handlers in `lib.rs`: a click carries the window it happened in, and a click
//! with **no** window is a click in the player's own inventory, which means the
//! menu is gone.
//!
//! # Where this still differs from Paper
//!
//! Two things, both because reopening is the only redraw there is:
//!
//! - Paper repaints an open menu whenever a backend's status changes, so a
//!   server going down greys out under the player's cursor. Here the menu is
//!   the snapshot it was drawn from until they page or reopen it, because
//!   repainting would mean closing and reopening the window ten times a minute.
//!   The click path re-reads the layout, so a dead backend is still refused.
//! - An entry whose config asks for an enchantment glint does not get one:
//!   `pumpkin:plugin@0.1.0` has no glint override, and the nearest thing -
//!   adding a real enchantment - would put an enchantment line in the tooltip
//!   that Paper does not show.

use crate::messaging::{Dispatch, MessageBus, find_player_by_id};
use crate::text::{markup, overlay_markup, tell_markup};
use luna_core_api::palette::color;
use luna_core_api::progress_bar;
use luna_core_api::registry::{BackendRegistry, BackendStatus};
use luna_core_api::selector::{
	CONNECT_LOBBY, CONNECT_PREVIOUS, SelectorLayout, decode_layout, encode_connect,
};
use luna_core_api::selector_engine::{
	PAGE_SIZE, RenderEntry, apply_template, layout_by_page, render_item,
};
use pumpkin_plugin_api::Server;
use pumpkin_plugin_api::gui::{Gui, GuiType};
use pumpkin_plugin_api::item_stack::ItemStack;
use pumpkin_plugin_api::player::Player;
use pumpkin_plugin_api::text::TextComponent;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

/// Channels the selector speaks on, matching `CoreServerSelectorMessageChannels`.
pub const OPEN_MENU: &str = "luna:server_selector_open";
pub const CONNECT_REQUEST: &str = "luna:server_selector_connect";

/// The chest is six rows; the bottom one is the menu's own controls.
const GUI_SIZE: u32 = 54;

// Footer slots, all as the Paper controller places them.
const SLOT_LOBBY: u32 = 46;
const SLOT_PREVIOUS_SERVER: u32 = 47;
const SLOT_DASHBOARD: u32 = 48;
const SLOT_CLOSE: u32 = 49;
const SLOT_PREV_PAGE: u32 = 52;
const SLOT_NEXT_PAGE: u32 = 53;

/// What a viewer has open.
#[derive(Clone, Copy)]
struct Session {
	page: u32,
	dashboard: bool,
}

/// A screen a player is owed, waiting for a tick that can safely draw it.
#[derive(Clone, Copy)]
enum Pending {
	Page(u32),
	/// The dashboard, and the page to come back to.
	Dashboard(u32),
}

/// The backend half of the server selector.
pub struct Selector {
	bus: Arc<MessageBus>,
	registry: Arc<Mutex<BackendRegistry>>,
	/// The menu as the proxy last published it.
	layout: Mutex<SelectorLayout>,
	sessions: Mutex<BTreeMap<String, Session>>,
	/// One entry per player owed a screen; the tick drains it.
	pending: Mutex<BTreeMap<String, Pending>>,
}

impl Selector {
	/// Build the selector and put its channels on the bus.
	#[must_use]
	pub fn new(bus: Arc<MessageBus>, registry: Arc<Mutex<BackendRegistry>>) -> Arc<Self> {
		let selector = Arc::new(Self {
			bus: Arc::clone(&bus),
			registry,
			layout: Mutex::new(SelectorLayout::default()),
			sessions: Mutex::new(BTreeMap::new()),
			pending: Mutex::new(BTreeMap::new()),
		});

		bus.register_outgoing(CONNECT_REQUEST);

		let opening = Arc::clone(&selector);

		bus.register_incoming(OPEN_MENU, move |context| {
			// `/servers` sends an empty payload: the request is "show them the
			// menu", and the menu itself came over HTTP long before. A frame
			// with a layout in it is still read, so a proxy that starts pushing
			// one needs no change here.
			let Some(player) = context.source else {
				return Dispatch::PassThrough;
			};

			if !context.payload.is_empty() {
				match decode_layout(context.payload) {
					Ok(Some(layout)) => opening.take_layout(layout),
					Ok(None) => {}
					Err(error) => tracing::warn!("Selector layout không đọc được: {error:?}"),
				}
			}

			let id = player.get_id().to_string();
			let page = opening.page_of(&id);

			opening.queue(&id, Pending::Page(page));

			Dispatch::Handled
		});

		selector
	}

	/// Ask for a screen to be drawn on the next tick.
	fn queue(&self, id: &str, pending: Pending) {
		self.pending
			.lock()
			.expect("selector pending poisoned")
			.insert(id.to_owned(), pending);
	}

	/// Draw every screen that was asked for, and clear the queue.
	///
	/// This is the **only** place the selector opens a window, and the reason is
	/// the module comment: doing it from a click handler deadlocks the player's
	/// screen-handler mutex, and doing it from a message handler is only safe by
	/// accident. A tick holds neither, so it is the one place that always works.
	pub fn tick(&self, server: &Server) {
		let due = std::mem::take(&mut *self.pending.lock().expect("selector pending poisoned"));

		for (id, pending) in due {
			let Some(player) = find_player_by_id(server, &id) else {
				continue;
			};

			match pending {
				Pending::Page(page) => self.open(server, &player, page),
				Pending::Dashboard(page) => self.open_dashboard(&player, page),
			}
		}
	}

	/// Whether this player has the menu open, which is how a click is ours.
	#[must_use]
	pub fn is_open(&self, id: &str) -> bool {
		self.sessions
			.lock()
			.expect("selector sessions poisoned")
			.contains_key(id)
	}

	/// Forget a player who left or closed the menu.
	pub fn forget(&self, id: &str) {
		self.sessions
			.lock()
			.expect("selector sessions poisoned")
			.remove(id);
	}

	/// Take a layout the heartbeat task fetched over HTTP.
	pub fn apply_payload(&self, payload: &[u8]) {
		match decode_layout(payload) {
			Ok(Some(layout)) => self.take_layout(layout),
			Ok(None) => tracing::warn!("Selector config không phải khung open-v8."),
			Err(error) => tracing::warn!("Selector config không đọc được: {error:?}"),
		}
	}

	fn take_layout(&self, layout: SelectorLayout) {
		tracing::info!(
			"Đã nhận layout server selector: {} máy chủ.",
			layout.servers.len()
		);

		*self.layout.lock().expect("selector layout poisoned") = layout;
	}

	fn page_of(&self, id: &str) -> u32 {
		self.sessions
			.lock()
			.expect("selector sessions poisoned")
			.get(id)
			.map_or(0, |session| session.page)
	}

	/// Draw the menu for one player at one page.
	pub fn open(&self, server: &Server, player: &Player, page: u32) {
		let layout = self.layout.lock().expect("selector layout poisoned").clone();

		if layout.is_empty() {
			tell_markup(
				player,
				"<red>❌ Danh sách máy chủ chưa sẵn sàng. Vui lòng thử lại sau vài giây.</red>",
			);

			return;
		}

		let statuses = self
			.registry
			.lock()
			.expect("backend registry poisoned")
			.snapshot();

		let pages = layout_by_page(&layout, &statuses);
		let max_page = pages.keys().copied().max().unwrap_or(0);
		let current = page.min(max_page);

		let title = apply_template(
			&layout.gui_title,
			&BTreeMap::from([("player_name".to_owned(), player.get_name())]),
		);

		let gui = Gui::new(GuiType::Generic9x6, gui_title(&title));

		// nothing in here is a real item; the GUI refusing to give anything up
		// is what makes a click the handler somehow misses still take nothing
		gui.set_allow_grab_items(false);
		gui.set_allow_put_items(false);

		let occupied = pages.get(&current);

		if let Some(items) = occupied {
			for (slot, entry) in items {
				gui.set_item(*slot, self.server_item(&layout, entry, player));
			}
		}

		decorate_grid(&gui, occupied);
		decorate_footer(&gui, current, max_page);

		self.sessions.lock().expect("selector sessions poisoned").insert(
			player.get_id().to_string(),
			Session {
				page: current,
				dashboard: false,
			},
		);

		player.open_gui(gui);

		let _ = server;
	}

	/// Draw one server's item, with the viewer's permission taken into account.
	fn server_item(&self, layout: &SelectorLayout, entry: &RenderEntry, player: &Player) -> ItemStack {
		let no_permission = !entry.entry.permission.trim().is_empty()
			&& !player.has_permission(&entry.entry.permission);

		let item = render_item(layout, &entry.entry, entry.status.as_ref(), no_permission);
		let stack = ItemStack::new(&material_key(&item.material), 1);

		stack.set_custom_name(Some(markup(&format!("<!i>{}", item.title))));

		if !item.lore.is_empty() {
			stack.set_lore(
				item.lore
					.iter()
					.map(|line| markup(&format!("<!i>{line}")))
					.collect(),
			);
		}

		stack
	}

	/// A click landed while the menu was open.
	///
	/// Nothing here draws: a verb that changes the screen is queued for the
	/// tick, because this runs with the player's screen-handler mutex held.
	pub fn on_click(&self, server: &Server, player: &Player, raw_slot: i16) {
		let id = player.get_id().to_string();

		let Some(session) = self
			.sessions
			.lock()
			.expect("selector sessions poisoned")
			.get(&id)
			.copied()
		else {
			return;
		};

		let Ok(slot) = u32::try_from(raw_slot) else {
			return;
		};

		if slot >= GUI_SIZE {
			// their own inventory; the click is refused but means nothing
			return;
		}

		tracing::info!(
			"[SELECTOR] click player={} slot={slot} dashboard={}",
			player.get_name(),
			session.dashboard
		);

		if session.dashboard {
			if slot == SLOT_CLOSE {
				self.queue(&id, Pending::Page(session.page));
			}

			return;
		}

		match slot {
			SLOT_LOBBY => self.connect(player, CONNECT_LOBBY),
			SLOT_PREVIOUS_SERVER => self.connect(player, CONNECT_PREVIOUS),
			SLOT_DASHBOARD => self.queue(&id, Pending::Dashboard(session.page)),
			// there is no close in `pumpkin:plugin@0.1.0`, so the button can
			// only say what does work; forgetting the session instead would
			// leave the menu on screen and stop it answering anything
			SLOT_CLOSE => overlay_markup(player, "<gray>Nhấn Esc để đóng menu.</gray>"),
			SLOT_PREV_PAGE if session.page > 0 => self.queue(&id, Pending::Page(session.page - 1)),
			SLOT_NEXT_PAGE => self.queue(&id, Pending::Page(session.page + 1)),
			slot if slot < PAGE_SIZE => self.click_server(server, player, session.page, slot),
			_ => {}
		}
	}

	/// A click on one of the server items.
	fn click_server(&self, server: &Server, player: &Player, page: u32, slot: u32) {
		let layout = self.layout.lock().expect("selector layout poisoned").clone();
		let statuses = self
			.registry
			.lock()
			.expect("backend registry poisoned")
			.snapshot();

		// the layout is recomputed rather than remembered: a backend that went
		// down since the menu opened must not still be clickable, and the paging
		// is cheap next to the round trip a connect costs
		let pages = layout_by_page(&layout, &statuses);

		let Some(entry) = pages.get(&page).and_then(|items| items.get(&slot)) else {
			return;
		};

		if !entry.entry.permission.trim().is_empty()
			&& !player.has_permission(&entry.entry.permission)
		{
			tell_markup(player, "<red>❌ Bạn không có quyền vào máy chủ này.</red>");

			return;
		}

		// The proxy is asked for the *registry's* name, not the layout's host
		// name, because that is what all three JVM backends send and what the
		// proxy's connect handler resolves against.
		let target = entry
			.status
			.as_ref()
			.map_or_else(|| entry.entry.backend_name.clone(), |status| status.name.clone());

		self.connect(player, &target);

		let _ = server;
	}

	/// Ask the proxy to move this player.
	///
	/// The session is deliberately **kept**. Paper closes the window here, and
	/// nothing on this platform can, so dropping the session would leave a menu
	/// on screen that no longer answers a click. That is not hypothetical: it is
	/// what asking for a lobby the proxy does not have looked like - the menu
	/// stayed up and went dead. A connect that works ends in a leave event,
	/// which forgets them; one that does not leaves the menu usable.
	fn connect(&self, player: &Player, backend_name: &str) {
		let payload = encode_connect(&player.get_id().to_string(), backend_name);

		if !self.bus.send(player, CONNECT_REQUEST, &payload) {
			tell_markup(
				player,
				"<red>❌ Không gửi được yêu cầu chuyển máy chủ. Vui lòng thử lại.</red>",
			);
		}
	}

	/// The network dashboard, which is the one screen drawn from local numbers.
	fn open_dashboard(&self, player: &Player, return_page: u32) {
		let statuses = self
			.registry
			.lock()
			.expect("backend registry poisoned")
			.snapshot();

		let stats = Dashboard::of(&statuses);

		// TEMPORARY: a panel showing a zero and a panel that never got drawn
		// look the same to a player, and only one of them is a bug.
		tracing::info!(
			"[SELECTOR] dashboard servers={}/{} players={} tps={:.2} cpu={:.1} \
			 ram={}/{} latency={:.0} uptime={}",
			stats.online_servers,
			stats.servers,
			stats.players,
			stats.average_tps,
			stats.average_cpu,
			stats.ram_used,
			stats.ram_max,
			stats.average_latency,
			stats.max_uptime
		);

		let gui = Gui::new(
			GuiType::Generic9x6,
			gui_title("<gradient:#6DFFD4:#4EA3FF>Thống Kê Toàn Mạng</gradient>"),
		);

		gui.set_allow_grab_items(false);
		gui.set_allow_put_items(false);

		for slot in 0..GUI_SIZE {
			gui.set_item(
				slot,
				panel_item("minecraft:gray_stained_glass_pane", "<gray> </gray>", &[]),
			);
		}

		for (slot, key, name, lines) in stats.items() {
			gui.set_item(slot, panel_item(&key, &name, &lines));
		}

		gui.set_item(
			SLOT_CLOSE,
			panel_item(
				"minecraft:arrow",
				"<yellow>Quay Lại Danh Sách Server</yellow>",
				&["<gray>Trở về trang trước đó</gray>".to_owned()],
			),
		);

		self.sessions.lock().expect("selector sessions poisoned").insert(
			player.get_id().to_string(),
			Session {
				page: return_page,
				dashboard: true,
			},
		);

		player.open_gui(gui);
	}
}

/// What the dashboard shows, aggregated over the registry.
struct Dashboard {
	servers: usize,
	online_servers: usize,
	players: u64,
	average_tps: f64,
	average_cpu: f64,
	average_latency: f64,
	ram_used: u64,
	ram_max: u64,
	max_uptime: u64,
}

impl Dashboard {
	fn of(statuses: &[BackendStatus]) -> Self {
		let online: Vec<&BackendStatus> = statuses.iter().filter(|status| status.online).collect();
		let count = online.len().max(1) as f64;
		let sum = |pick: fn(&BackendStatus) -> f64| -> f64 {
			online.iter().map(|status| pick(status)).sum::<f64>()
		};

		Self {
			servers: statuses.len(),
			online_servers: online.len(),
			players: online.iter().map(|status| u64::from(status.online_players)).sum(),
			average_tps: sum(|status| status.tps) / count,
			average_cpu: sum(|status| status.cpu_percent) / count,
			average_latency: sum(|status| status.latency_millis as f64) / count,
			ram_used: online.iter().map(|status| status.ram_used_bytes).sum(),
			ram_max: online.iter().map(|status| status.ram_max_bytes).sum(),
			max_uptime: online.iter().map(|status| status.uptime_millis).max().unwrap_or(0),
		}
	}

	/// The panels, in the slots the Paper dashboard puts them.
	///
	/// Each metric is a progress bar rather than a bare number, because that is
	/// what the Paper dashboard shows and a number alone says nothing about
	/// whether it is a good one.
	fn items(&self) -> Vec<(u32, String, String, Vec<String>)> {
		let megabytes = |bytes: u64| bytes / 1024 / 1024;

		vec![
			(
				10,
				"minecraft:clock".to_owned(),
				"<yellow>TPS Tổng Thể</yellow>".to_owned(),
				vec![
					"<gray>Giá trị trung bình toàn mạng</gray>".to_owned(),
					progress_bar::tps("TPS", self.average_tps),
				],
			),
			(
				12,
				"minecraft:redstone".to_owned(),
				"<color:#FF9A4D>CPU Trung Bình</color>".to_owned(),
				vec![
					"<gray>Tải CPU theo heartbeat backend</gray>".to_owned(),
					progress_bar::cpu("CPU", self.average_cpu),
				],
			),
			(
				14,
				"minecraft:iron_block".to_owned(),
				"<color:#7FDBFF>RAM Tổng</color>".to_owned(),
				vec![
					"<gray>Sử dụng bộ nhớ toàn mạng</gray>".to_owned(),
					progress_bar::ram("RAM", self.ram_used, self.ram_max),
					format!(
						"<gray>{}MB / {}MB</gray>",
						megabytes(self.ram_used),
						megabytes(self.ram_max)
					),
				],
			),
			(
				16,
				"minecraft:repeater".to_owned(),
				"<aqua>Latency Heartbeat</aqua>".to_owned(),
				vec![
					"<gray>Độ trễ backend → proxy</gray>".to_owned(),
					progress_bar::latency("Latency", self.average_latency),
				],
			),
			(
				30,
				"minecraft:emerald".to_owned(),
				"<green>Online Servers</green>".to_owned(),
				vec![format!(
					"<white>{}</white><gray>/</gray><white>{}</white>",
					self.online_servers, self.servers
				)],
			),
			(
				31,
				"minecraft:chest".to_owned(),
				"<gold>Uptime Cao Nhất</gold>".to_owned(),
				vec![
					"<gray>Máy chủ chạy lâu nhất</gray>".to_owned(),
					format!("<white>{}</white>", readable_uptime(self.max_uptime)),
				],
			),
			(
				32,
				"minecraft:player_head".to_owned(),
				"<color:#9EE6A3>Người Chơi Toàn Mạng</color>".to_owned(),
				vec![format!("<white>{}</white>", self.players)],
			),
		]
	}
}

/// A span of time in words, as `Formatters.duration` writes it.
fn readable_uptime(millis: u64) -> String {
	let total = millis / 1000;
	let days = total / 86_400;
	let hours = (total % 86_400) / 3600;
	let minutes = (total % 3600) / 60;
	let seconds = total % 60;

	let mut parts: Vec<String> = Vec::new();

	if days > 0 {
		parts.push(format!("{days} ngày"));
	}

	if hours > 0 {
		parts.push(format!("{hours} giờ"));
	}

	if minutes > 0 {
		parts.push(format!("{minutes} phút"));
	}

	if seconds > 0 || parts.is_empty() {
		parts.push(format!("{seconds} giây"));
	}

	parts.join(" ")
}

/// A GUI's own title, in the colour every luna menu wears.
fn gui_title(text: &str) -> TextComponent {
	markup(&format!("<color:{}>{text}</color>", color::GUI_TITLE_PRIMARY))
}

/// The frame around the server grid: a rainbow of panes down each edge.
///
/// The border is decoration only, and it steps through its colours per *pane
/// drawn* rather than per slot, so a pinned server sitting on the edge does not
/// leave a gap in the sequence.
fn decorate_grid(gui: &Gui, occupied: Option<&BTreeMap<u32, RenderEntry>>) {
	const BORDER: &[&str] = &[
		"purple_stained_glass_pane",
		"magenta_stained_glass_pane",
		"pink_stained_glass_pane",
		"red_stained_glass_pane",
		"orange_stained_glass_pane",
		"yellow_stained_glass_pane",
		"lime_stained_glass_pane",
		"green_stained_glass_pane",
		"cyan_stained_glass_pane",
		"light_blue_stained_glass_pane",
		"blue_stained_glass_pane",
	];

	let mut drawn = 0;

	for slot in 0..PAGE_SIZE {
		let (row, column) = (slot / 9, slot % 9);

		if row != 0 && row != 4 && column != 0 && column != 8 {
			continue;
		}

		if occupied.is_some_and(|items| items.contains_key(&slot)) {
			continue;
		}

		gui.set_item(
			slot,
			panel_item(
				&material_key(BORDER[drawn % BORDER.len()]),
				"<gradient:#6DFFD4:#4EA3FF>◈</gradient>",
				&[],
			),
		);

		drawn += 1;
	}
}

/// The bottom row: paging either side, and the four fixed controls.
fn decorate_footer(gui: &Gui, page: u32, max_page: u32) {
	let (previous_key, previous_name, previous_lore) = if page > 0 {
		(
			"minecraft:map",
			"<yellow>← Trang trước</yellow>",
			"<gray>Lùi về trang danh sách trước đó</gray>",
		)
	} else {
		(
			"minecraft:black_stained_glass_pane",
			"<dark_gray>Trang trước</dark_gray>",
			"<gray>Bạn đang ở trang đầu</gray>",
		)
	};

	// the whole row is laid down first, so the slots between the controls read
	// as part of the bar rather than as holes in it
	for slot in PAGE_SIZE..GUI_SIZE {
		gui.set_item(
			slot,
			panel_item("minecraft:black_stained_glass_pane", "<dark_gray> </dark_gray>", &[]),
		);
	}

	let (next_key, next_name, next_lore) = if page < max_page {
		(
			"minecraft:paper",
			"<yellow>Trang sau →</yellow>",
			"<gray>Chuyển sang trang danh sách kế tiếp</gray>",
		)
	} else {
		(
			"minecraft:black_stained_glass_pane",
			"<dark_gray>Trang sau</dark_gray>",
			"<gray>Bạn đang ở trang cuối</gray>",
		)
	};

	for (slot, key, name, lore) in [
		(
			SLOT_PREV_PAGE,
			previous_key,
			previous_name,
			vec![previous_lore.to_owned()],
		),
		(
			SLOT_LOBBY,
			"minecraft:oak_door",
			"<aqua>Về Sảnh</aqua>",
			vec![
				"<gray>Kết nối về lobby</gray>".to_owned(),
				"<yellow>Nhấn để chuyển máy chủ</yellow>".to_owned(),
			],
		),
		(
			SLOT_PREVIOUS_SERVER,
			"minecraft:compass",
			"<gold>Quay Lại Server Trước</gold>",
			vec![
				"<gray>Khôi phục server gần nhất</gray>".to_owned(),
				"<yellow>Nhấn để quay lại</yellow>".to_owned(),
			],
		),
		(
			SLOT_DASHBOARD,
			"minecraft:clock",
			"<color:#6DFFD4>Bảng Điều Khiển Hệ Thống</color>",
			vec![
				"<gray>TPS, CPU, RAM, latency, uptime</gray>".to_owned(),
				"<yellow>Nhấn để mở dashboard</yellow>".to_owned(),
			],
		),
		(
			SLOT_CLOSE,
			"minecraft:barrier",
			"<red>Đóng</red>",
			// Paper's has no lore, because clicking it closes the window. Here
			// it cannot, so the item says what the player has to do instead.
			vec!["<gray>Nhấn Esc để đóng menu</gray>".to_owned()],
		),
		(SLOT_NEXT_PAGE, next_key, next_name, vec![next_lore.to_owned()]),
	] {
		gui.set_item(slot, panel_item(key, name, &lore));
	}
}

/// One decorative item: a name, and lore, both MiniMessage.
fn panel_item(registry_key: &str, name: &str, lore: &[String]) -> ItemStack {
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

/// A material name as pumpkin wants it.
///
/// The selector config is written in Bukkit's spelling (`RED_CONCRETE`) because
/// it is shared with the Paper backends, so it has to be folded into a registry
/// key here rather than the operator keeping two lists.
#[must_use]
pub fn material_key(name: &str) -> String {
	let trimmed = name.trim();

	if trimmed.is_empty() {
		return "minecraft:stone".to_owned();
	}

	if trimmed.contains(':') {
		return trimmed.to_ascii_lowercase();
	}

	format!("minecraft:{}", trimmed.to_ascii_lowercase())
}
