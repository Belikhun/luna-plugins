//! Loading a [`PermissionStore`](crate::PermissionStore) from a file.
//!
//! The format is TOML for the same reason the rest of the Pumpkin side is:
//! Pumpkin's own config is TOML and `control`'s `confedit` already speaks it, so
//! a managed permissions file needs no new machinery on either side.
//!
//! ```toml
//! defaultGroup = "default"
//!
//! [groups.default]
//! weight = 0
//! prefix = "&7"
//! permissions = ["luna.selector.open"]
//!
//! [groups.admin]
//! weight = 100
//! prefix = "&c[Admin] "
//! inherits = ["default"]
//! permissions = ["*", "-luna.admin.stop"]
//!
//! [users."0b1a3f1e-0000-4000-8000-000000000001"]
//! groups = ["admin"]
//! ```

use crate::{Group, PermissionStore, User};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::Path;

/// The file as it is written on disk.
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct PermissionsFile {
	/// Who everyone is, before any user entry says otherwise.
	pub default_group: String,
	pub groups: BTreeMap<String, GroupEntry>,
	pub users: BTreeMap<String, UserEntry>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct GroupEntry {
	pub weight: i32,
	pub prefix: String,
	pub suffix: String,
	pub permissions: Vec<String>,
	pub inherits: Vec<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct UserEntry {
	pub groups: Vec<String>,
	pub permissions: Vec<String>,
}

impl Default for PermissionsFile {
	fn default() -> Self {
		let mut groups = BTreeMap::new();

		// a file with nothing in it should still let people onto the server and
		// open the selector, or a fresh backend looks broken rather than empty
		groups.insert(
			"default".to_owned(),
			GroupEntry {
				weight: 0,
				prefix: String::new(),
				suffix: String::new(),
				permissions: vec!["luna.selector.open".to_owned()],
				inherits: Vec::new(),
			},
		);

		Self {
			default_group: "default".to_owned(),
			groups,
			users: BTreeMap::new(),
		}
	}
}

impl PermissionsFile {
	/// Turn the parsed file into the store the service queries.
	#[must_use]
	pub fn into_store(self) -> PermissionStore {
		let mut store = PermissionStore::new(self.default_group);

		for (name, entry) in self.groups {
			store.insert_group(Group {
				name,
				weight: entry.weight,
				prefix: entry.prefix,
				suffix: entry.suffix,
				permissions: entry.permissions,
				inherits: entry.inherits,
			});
		}

		for (id, entry) in self.users {
			store.insert_user(
				id,
				User {
					groups: entry.groups,
					permissions: entry.permissions,
				},
			);
		}

		store
	}
}

/// Read the file, writing the defaults out when it is not there yet.
///
/// A malformed file is reported but not fatal, and the caller gets a working
/// default store: locking every player out of a backend because one bracket is
/// wrong is a worse failure than running with the defaults and saying so.
pub fn load_or_create(path: &Path) -> (PermissionStore, Option<String>) {
	match std::fs::read_to_string(path) {
		Ok(body) => match toml::from_str::<PermissionsFile>(&body) {
			Ok(file) => (file.into_store(), None),
			Err(error) => (
				PermissionsFile::default().into_store(),
				Some(format!(
					"permissions tại {} không hợp lệ: {error}",
					path.display()
				)),
			),
		},
		Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
			let file = PermissionsFile::default();
			let note = match write(path, &file) {
				Ok(()) => format!("Đã tạo permissions mặc định tại {}.", path.display()),
				Err(write_error) => format!("Không thể ghi permissions mặc định: {write_error}"),
			};

			(file.into_store(), Some(note))
		}
		Err(error) => (
			PermissionsFile::default().into_store(),
			Some(format!("Không thể đọc permissions: {error}")),
		),
	}
}

fn write(path: &Path, file: &PermissionsFile) -> std::io::Result<()> {
	let body =
		toml::to_string_pretty(file).map_err(|error| std::io::Error::other(error.to_string()))?;

	if let Some(parent) = path.parent() {
		std::fs::create_dir_all(parent)?;
	}

	std::fs::write(path, body)
}

#[cfg(test)]
mod tests {
	use super::*;

	const SAMPLE: &str = r#"
defaultGroup = "default"

[groups.default]
weight = 0
prefix = "&7"
permissions = ["luna.selector.open"]

[groups.mod]
weight = 50
prefix = "&a[Mod] "
inherits = ["default"]
permissions = ["luna.admin.*", "-luna.admin.stop"]

[groups.admin]
weight = 100
prefix = "&c[Admin] "
inherits = ["mod"]
permissions = ["*"]

[users."0b1a3f1e-0000-4000-8000-000000000001"]
groups = ["admin"]
"#;

	#[test]
	fn parses_and_resolves_a_real_file() {
		let file: PermissionsFile = toml::from_str(SAMPLE).expect("parses");
		let store = file.into_store();
		let admin = "0b1a3f1e-0000-4000-8000-000000000001";

		assert_eq!(store.group_name(admin), "admin");
		assert_eq!(store.prefix(admin), "&c[Admin] ");
		// inherited from default, three groups up
		assert!(store.has_permission(admin, "luna.selector.open"));
		// mod's specific denial still stands under admin's `*`
		assert!(!store.has_permission(admin, "luna.admin.stop"));
	}

	#[test]
	fn an_unlisted_player_falls_to_the_default_group() {
		let store = toml::from_str::<PermissionsFile>(SAMPLE)
			.expect("parses")
			.into_store();

		assert_eq!(store.group_name("someone-else"), "default");
		assert!(store.has_permission("someone-else", "luna.selector.open"));
		assert!(!store.has_permission("someone-else", "luna.admin.kick"));
	}

	#[test]
	fn omitted_fields_keep_their_defaults() {
		let file: PermissionsFile =
			toml::from_str("[groups.basic]\npermissions = [\"a\"]\n").expect("parses");
		let entry = file.groups.get("basic").expect("group");

		assert_eq!(entry.weight, 0);
		assert!(entry.inherits.is_empty());
		assert_eq!(file.default_group, "default");
	}

	#[test]
	fn the_default_file_is_usable_on_its_own() {
		let store = PermissionsFile::default().into_store();

		assert!(store.has_permission("anyone", "luna.selector.open"));
		assert_eq!(store.group_name("anyone"), "default");
	}

	#[test]
	fn round_trips_through_toml() {
		let file = PermissionsFile::default();
		let parsed: PermissionsFile =
			toml::from_str(&toml::to_string(&file).expect("encodes")).expect("decodes");

		assert_eq!(parsed.default_group, file.default_group);
		assert!(parsed.groups.contains_key("default"));
	}

	#[test]
	fn a_missing_file_is_created_and_still_answers() {
		let dir = std::env::temp_dir().join(format!(
			"luna-perms-{}",
			std::time::SystemTime::now()
				.duration_since(std::time::UNIX_EPOCH)
				.map(|d| d.as_nanos())
				.unwrap_or(0)
		));
		let path = dir.join("permissions.toml");

		let (store, note) = load_or_create(&path);

		assert!(note.is_some_and(|message| message.contains("Đã tạo")));
		assert!(path.exists());
		assert!(store.has_permission("anyone", "luna.selector.open"));

		let _ = std::fs::remove_dir_all(&dir);
	}

	#[test]
	fn a_malformed_file_falls_back_rather_than_locking_everyone_out() {
		let dir = std::env::temp_dir().join(format!(
			"luna-perms-bad-{}",
			std::time::SystemTime::now()
				.duration_since(std::time::UNIX_EPOCH)
				.map(|d| d.as_nanos())
				.unwrap_or(0)
		));
		std::fs::create_dir_all(&dir).expect("temp dir");
		let path = dir.join("permissions.toml");
		std::fs::write(&path, "[groups.default\nbroken").expect("write");

		let (store, note) = load_or_create(&path);

		assert!(note.is_some_and(|message| message.contains("không hợp lệ")));
		assert!(store.has_permission("anyone", "luna.selector.open"));

		let _ = std::fs::remove_dir_all(&dir);
	}
}
