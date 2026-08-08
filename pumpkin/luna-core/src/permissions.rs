//! Answering the server's permission checks from luna's own store.
//!
//! On the JVM this is LuckPerms' job and every luna plugin asks it through the
//! Bukkit API. A Pumpkin component cannot do that: the `plugin` world has no
//! inter-plugin imports, so a separate permissions component would have no way
//! to answer anyone. The store therefore lives here, inside the one component,
//! and reaches the server through the permission-check event instead.
//!
//! The handler **augments** rather than replaces. Pumpkin still owns op levels
//! and every `minecraft:` node, and luna has no opinion on those; only a node
//! the store actually matches is overridden, so wiring this in cannot silently
//! strip permissions luna never knew about.

use crate::PLUGIN_NAME;
use luna_core_api::permissions::unqualify;
use luna_permissions::{PermissionStore, config};
use pumpkin_plugin_api::events::{EventData, EventHandler, PlayerPermissionCheckEvent};
use pumpkin_plugin_api::{Context, Server};
use std::sync::Arc;

/// The file the store is read from, inside the plugin's own data folder.
const FILE_NAME: &str = "permissions.toml";

/// Loads the store, reporting what it had to say about the file.
#[must_use]
pub fn load(data_folder: &str) -> Arc<PermissionStore> {
	let path = std::path::Path::new(data_folder).join(FILE_NAME);
	let (store, note) = config::load_or_create(&path);

	if let Some(message) = note {
		tracing::warn!("{message}");
	}

	Arc::new(store)
}

/// Registers the gate so the server asks luna before deciding.
pub fn register(context: &Context, store: Arc<PermissionStore>) {
	// blocking, because the answer is the point: a handler the server does not
	// wait for cannot change the result it is about to use
	let registered = context.register_event_handler::<PlayerPermissionCheckEvent, _>(
		PermissionGate { store },
		pumpkin_plugin_api::events::EventPriority::Normal,
		true,
	);

	match registered {
		Ok(_) => tracing::info!("Đã gắn luna permissions vào kiểm tra quyền của server."),
		Err(error) => tracing::error!(
			"Không đăng ký được permission handler: {error}; \
			 quyền của luna sẽ không có tác dụng."
		),
	}
}

/// Answers one permission check.
///
/// This holds the store rather than a closure over the whole load context: the
/// handler outlives every value that was in scope when it was built, and
/// capturing that scope would keep all of it alive for the server's lifetime.
struct PermissionGate {
	store: Arc<PermissionStore>,
}

impl EventHandler<PlayerPermissionCheckEvent> for PermissionGate {
	fn handle(
		&self,
		_server: Server,
		mut event: EventData<PlayerPermissionCheckEvent>,
	) -> EventData<PlayerPermissionCheckEvent> {
		let id = event.player.get_id().to_string();
		let node = unqualify(&event.permission, PLUGIN_NAME);

		if let Some(granted) = self.store.decide(&id, node) {
			event.permission_result = granted;
		}

		event
	}
}
