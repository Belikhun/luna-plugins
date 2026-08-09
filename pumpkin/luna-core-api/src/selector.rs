//! The server selector's layout, as the proxy publishes it.
//!
//! The proxy owns the menu. It holds the server list, the slots, the templates
//! and the per-status styling, and it pushes the whole thing to a backend as one
//! `open-v8` frame on `luna:server_selector_open`; the backend's job is to draw
//! it and to send back which server was clicked. That split is why a Pumpkin
//! backend can show the same menu as a Paper one without either of them holding
//! the configuration.
//!
//! The frame is dense and positional - a length-prefixed list of servers, each
//! with optional materials, glint, conditional overrides and template overrides
//! per status - so it is decoded here, once, with the encoder beside it. The
//! encoder exists only for the tests: a decoder written against a format nobody
//! can produce is a decoder nobody can check.

use crate::wire::{MessageReader, MessageWriter, WireError};
use std::collections::BTreeMap;

/// The action name every selector frame opens with.
pub const OPEN_ACTION: &str = "open-v8";

/// The four states a backend can be in on the menu.
///
/// Kept as strings rather than an enum: they key the per-status maps the proxy
/// sends, and a status this build has not heard of has to survive the round trip
/// rather than collapse into a default.
pub const STATUS_ONLINE: &str = "ONLINE";
pub const STATUS_OFFLINE: &str = "OFFLINE";
pub const STATUS_MAINT: &str = "MAINT";
pub const STATUS_NOP: &str = "NOP";

/// Which of the four states a backend is in, from the one rule both sides use.
///
/// This is `SelectorStatusResolver` verbatim. The proxy applies it when a player
/// asks to connect and the backend applies it when it draws the menu; the two
/// disagreeing is a bug the player sees as an item that will not click.
#[must_use]
pub fn resolve_status(online: bool, whitelisted: bool, no_permission: bool) -> &'static str {
	if no_permission {
		return STATUS_NOP;
	}

	if !online {
		return STATUS_OFFLINE;
	}

	if whitelisted {
		return STATUS_MAINT;
	}

	STATUS_ONLINE
}

/// What a template says, with every field optional.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TemplateOverride {
	pub name: Option<String>,
	pub header_lines: Option<Vec<String>>,
	pub body_line: Option<String>,
	pub footer_lines: Option<Vec<String>>,
}

/// How an item is written: its name, the lines around the body, and the body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Template {
	pub name: String,
	pub header_lines: Vec<String>,
	pub body_line: String,
	pub footer_lines: Vec<String>,
	pub material: String,
	pub by_status: BTreeMap<String, TemplateOverride>,
}

impl Default for Template {
	fn default() -> Self {
		Self {
			name: "<b>%server_display%</b>".to_owned(),
			header_lines: Vec::new(),
			body_line: "%line%".to_owned(),
			footer_lines: Vec::new(),
			material: String::new(),
			by_status: BTreeMap::new(),
		}
	}
}

impl Template {
	/// Lay an override on top, keeping what it does not mention.
	#[must_use]
	pub fn with_override(&self, over: Option<&TemplateOverride>) -> Self {
		let Some(over) = over else {
			return self.clone();
		};

		Self {
			name: over.name.clone().unwrap_or_else(|| self.name.clone()),
			header_lines: over
				.header_lines
				.clone()
				.unwrap_or_else(|| self.header_lines.clone()),
			body_line: over
				.body_line
				.clone()
				.unwrap_or_else(|| self.body_line.clone()),
			footer_lines: over
				.footer_lines
				.clone()
				.unwrap_or_else(|| self.footer_lines.clone()),
			material: self.material.clone(),
			by_status: self.by_status.clone(),
		}
	}
}

/// An override applied only when its condition holds.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ConditionalOverride {
	pub condition: String,
	pub material: Option<String>,
	pub glint: Option<bool>,
	pub description: Option<Vec<String>>,
	pub template: Option<TemplateOverride>,
}

/// One server as the menu describes it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ServerEntry {
	pub backend_name: String,
	pub display_name: String,
	pub accent_color: String,
	/// Empty means everyone may see and click it.
	pub permission: String,
	/// What the proxy calls this backend in its own server list.
	pub host_name: String,
	/// Absent when the proxy did not pin one; the engine then packs it in order.
	pub slot: Option<u32>,
	pub page: Option<u32>,
	pub material: String,
	pub material_by_status: BTreeMap<String, String>,
	pub glint: Option<bool>,
	pub glint_by_status: BTreeMap<String, bool>,
	pub conditional: Vec<ConditionalOverride>,
	pub description: Vec<String>,
	pub description_by_status: BTreeMap<String, Vec<String>>,
	pub template: Option<Template>,
}

impl ServerEntry {
	/// The material for this status, falling back to the plain one.
	#[must_use]
	pub fn material_for(&self, status: &str) -> &str {
		match self.material_by_status.get(status) {
			Some(found) if !found.trim().is_empty() => found,
			_ => &self.material,
		}
	}

	/// Whether the item glints in this status, when anything says so.
	#[must_use]
	pub fn glint_for(&self, status: &str) -> Option<bool> {
		self.glint_by_status.get(status).copied().or(self.glint)
	}

	/// The description for this status, falling back to the plain one.
	#[must_use]
	pub fn description_for(&self, status: &str) -> &[String] {
		self.description_by_status
			.get(status)
			.map_or(&self.description, Vec::as_slice)
	}
}

/// The whole menu.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SelectorLayout {
	pub gui_title: String,
	pub template: Template,
	pub status_colors: BTreeMap<String, String>,
	pub status_icons: BTreeMap<String, String>,
	/// Keyed by the lowercased backend name, which is how a lookup arrives.
	pub servers: BTreeMap<String, ServerEntry>,
}

impl Default for SelectorLayout {
	fn default() -> Self {
		Self {
			gui_title: "Danh Sách Máy Chủ".to_owned(),
			template: Template::default(),
			status_colors: default_status_colors(),
			status_icons: default_status_icons(),
			servers: BTreeMap::new(),
		}
	}
}

impl SelectorLayout {
	#[must_use]
	pub fn is_empty(&self) -> bool {
		self.servers.is_empty()
	}

	#[must_use]
	pub fn server(&self, backend_name: &str) -> Option<&ServerEntry> {
		self.servers.get(&normalize(backend_name))
	}

	#[must_use]
	pub fn status_color(&self, status: &str) -> &str {
		self.status_colors
			.get(status)
			.map_or("<white>", String::as_str)
	}

	#[must_use]
	pub fn status_icon(&self, status: &str) -> &str {
		self.status_icons.get(status).map_or("●", String::as_str)
	}

	/// The template for one server in one status, with every layer applied.
	///
	/// The order is the JVM's: the menu template, then its own per-status
	/// override, then the server's template wholesale, then *that* template's
	/// per-status override.
	#[must_use]
	pub fn resolve_template(&self, entry: Option<&ServerEntry>, status: &str) -> Template {
		let base = self.template.with_override(self.template.by_status.get(status));

		let Some(server_template) = entry.and_then(|entry| entry.template.as_ref()) else {
			return base;
		};

		server_template.with_override(server_template.by_status.get(status))
	}
}

/// The colours a status is drawn in when the proxy names none.
#[must_use]
pub fn default_status_colors() -> BTreeMap<String, String> {
	[
		(STATUS_ONLINE, "<green>"),
		(STATUS_OFFLINE, "<red>"),
		(STATUS_MAINT, "<yellow>"),
		(STATUS_NOP, "<dark_gray>"),
	]
	.into_iter()
	.map(|(status, color)| (status.to_owned(), color.to_owned()))
	.collect()
}

/// The glyph a status is drawn with when the proxy names none.
#[must_use]
pub fn default_status_icons() -> BTreeMap<String, String> {
	[
		(STATUS_ONLINE, "●"),
		(STATUS_OFFLINE, "●"),
		(STATUS_MAINT, "●"),
		(STATUS_NOP, "●"),
	]
	.into_iter()
	.map(|(status, icon)| (status.to_owned(), icon.to_owned()))
	.collect()
}

/// A backend name as the maps key it.
#[must_use]
pub fn normalize(value: &str) -> String {
	value.trim().to_ascii_lowercase()
}

/// Read an `open-v8` frame, or `None` when the payload is a different action.
pub fn decode_layout(payload: &[u8]) -> Result<Option<SelectorLayout>, WireError> {
	let mut reader = MessageReader::new(payload);

	if reader.read_utf()? != OPEN_ACTION {
		return Ok(None);
	}

	let gui_title = reader.read_utf()?;
	let template = read_template(&mut reader)?;
	let mut status_colors = BTreeMap::new();
	let mut status_icons = BTreeMap::new();

	for _ in 0..count(&mut reader)? {
		let status = reader.read_utf()?;

		status_colors.insert(status.clone(), reader.read_utf()?);
		status_icons.insert(status, reader.read_utf()?);
	}

	let mut servers = BTreeMap::new();

	for _ in 0..count(&mut reader)? {
		let entry = read_server(&mut reader)?;

		servers.insert(normalize(&entry.backend_name), entry);
	}

	Ok(Some(SelectorLayout {
		gui_title,
		template,
		status_colors,
		status_icons,
		servers,
	}))
}

fn read_server(reader: &mut MessageReader<'_>) -> Result<ServerEntry, WireError> {
	let backend_name = reader.read_utf()?;
	let display_name = reader.read_utf()?;
	let accent_color = reader.read_utf()?;
	let permission = reader.read_utf()?;
	let host_name = reader.read_utf()?;
	let slot = optional_index(reader.read_i32()?);
	let page = optional_index(reader.read_i32()?);
	let material = reader.read_utf()?;

	let mut material_by_status = BTreeMap::new();

	for _ in 0..count(reader)? {
		let status = reader.read_utf()?;

		material_by_status.insert(status, reader.read_utf()?);
	}

	let glint = if reader.read_bool()? {
		Some(reader.read_bool()?)
	} else {
		None
	};

	let mut glint_by_status = BTreeMap::new();

	for _ in 0..count(reader)? {
		let status = reader.read_utf()?;

		glint_by_status.insert(status, reader.read_bool()?);
	}

	let mut conditional = Vec::new();

	for _ in 0..count(reader)? {
		conditional.push(read_conditional(reader)?);
	}

	let mut description = Vec::new();

	for _ in 0..count(reader)? {
		description.push(reader.read_utf()?);
	}

	let mut description_by_status = BTreeMap::new();

	for _ in 0..count(reader)? {
		let status = reader.read_utf()?;

		description_by_status.insert(status, read_lines(reader)?);
	}

	let template = if reader.read_bool()? {
		Some(read_template(reader)?)
	} else {
		None
	};

	Ok(ServerEntry {
		backend_name,
		display_name,
		accent_color,
		permission,
		host_name,
		slot,
		page,
		material,
		material_by_status,
		glint,
		glint_by_status,
		conditional,
		description,
		description_by_status,
		template,
	})
}

fn read_conditional(reader: &mut MessageReader<'_>) -> Result<ConditionalOverride, WireError> {
	let condition = reader.read_utf()?;
	let material = optional(reader, |reader| reader.read_utf())?;
	let glint = optional(reader, |reader| reader.read_bool())?;
	let description = optional(reader, read_lines)?;
	let template = optional(reader, read_template_override)?;

	Ok(ConditionalOverride {
		condition,
		material,
		glint,
		description,
		template,
	})
}

fn read_template(reader: &mut MessageReader<'_>) -> Result<Template, WireError> {
	let name = reader.read_utf()?;
	let header_lines = read_lines(reader)?;
	let body_line = reader.read_utf()?;
	let footer_lines = read_lines(reader)?;
	let material = reader.read_utf()?;

	let mut by_status = BTreeMap::new();

	for _ in 0..count(reader)? {
		let status = reader.read_utf()?;

		by_status.insert(status, read_template_override(reader)?);
	}

	Ok(Template {
		name,
		header_lines,
		body_line,
		footer_lines,
		material,
		by_status,
	})
}

fn read_template_override(reader: &mut MessageReader<'_>) -> Result<TemplateOverride, WireError> {
	Ok(TemplateOverride {
		name: optional(reader, |reader| reader.read_utf())?,
		header_lines: optional(reader, read_lines)?,
		body_line: optional(reader, |reader| reader.read_utf())?,
		footer_lines: optional(reader, read_lines)?,
	})
}

fn read_lines(reader: &mut MessageReader<'_>) -> Result<Vec<String>, WireError> {
	let mut lines = Vec::new();

	for _ in 0..count(reader)? {
		lines.push(reader.read_utf()?);
	}

	Ok(lines)
}

/// A present-flag followed by the value, which is how the JVM writes an optional.
fn optional<T, F>(reader: &mut MessageReader<'_>, read: F) -> Result<Option<T>, WireError>
where
	F: FnOnce(&mut MessageReader<'_>) -> Result<T, WireError>,
{
	if !reader.read_bool()? {
		return Ok(None);
	}

	Ok(Some(read(reader)?))
}

/// A length prefix, clamped so a negative or absurd count cannot allocate.
///
/// The JVM writes these as a signed int and nothing legitimate is anywhere near
/// the cap; a frame claiming more is truncated rather than trusted, because the
/// reads that follow would fail on the buffer anyway.
fn count(reader: &mut MessageReader<'_>) -> Result<usize, WireError> {
	const CAP: i32 = 4096;

	Ok(reader.read_i32()?.clamp(0, CAP) as usize)
}

/// `-1` is how the writer spells "the proxy pinned nothing here".
fn optional_index(value: i32) -> Option<u32> {
	u32::try_from(value).ok()
}

/// Write an `open-v8` frame. For tests, and for whatever grows a proxy side.
#[must_use]
pub fn encode_layout(layout: &SelectorLayout) -> Vec<u8> {
	let mut writer = MessageWriter::new();

	writer.write_utf(OPEN_ACTION).write_utf(&layout.gui_title);
	write_template(&mut writer, &layout.template);

	let statuses: Vec<&String> = layout.status_colors.keys().collect();

	writer.write_i32(statuses.len() as i32);

	for status in statuses {
		writer
			.write_utf(status)
			.write_utf(layout.status_color(status))
			.write_utf(layout.status_icon(status));
	}

	writer.write_i32(layout.servers.len() as i32);

	for entry in layout.servers.values() {
		write_server(&mut writer, entry);
	}

	writer.into_vec()
}

fn write_server(writer: &mut MessageWriter, entry: &ServerEntry) {
	writer
		.write_utf(&entry.backend_name)
		.write_utf(&entry.display_name)
		.write_utf(&entry.accent_color)
		.write_utf(&entry.permission)
		.write_utf(&entry.host_name)
		.write_i32(entry.slot.map_or(-1, |slot| slot as i32))
		.write_i32(entry.page.map_or(-1, |page| page as i32))
		.write_utf(&entry.material)
		.write_i32(entry.material_by_status.len() as i32);

	for (status, material) in &entry.material_by_status {
		writer.write_utf(status).write_utf(material);
	}

	writer.write_bool(entry.glint.is_some());

	if let Some(glint) = entry.glint {
		writer.write_bool(glint);
	}

	writer.write_i32(entry.glint_by_status.len() as i32);

	for (status, glint) in &entry.glint_by_status {
		writer.write_utf(status).write_bool(*glint);
	}

	writer.write_i32(entry.conditional.len() as i32);

	for conditional in &entry.conditional {
		writer.write_utf(&conditional.condition);
		write_optional(writer, conditional.material.as_ref(), |writer, value| {
			writer.write_utf(value);
		});
		write_optional(writer, conditional.glint.as_ref(), |writer, value| {
			writer.write_bool(*value);
		});
		write_optional(writer, conditional.description.as_ref(), write_lines);
		write_optional(writer, conditional.template.as_ref(), write_template_override);
	}

	writer.write_i32(entry.description.len() as i32);

	for line in &entry.description {
		writer.write_utf(line);
	}

	writer.write_i32(entry.description_by_status.len() as i32);

	for (status, lines) in &entry.description_by_status {
		writer.write_utf(status);
		write_lines(writer, lines);
	}

	writer.write_bool(entry.template.is_some());

	if let Some(template) = &entry.template {
		write_template(writer, template);
	}
}

fn write_template(writer: &mut MessageWriter, template: &Template) {
	writer.write_utf(&template.name);
	write_lines(writer, &template.header_lines);
	writer.write_utf(&template.body_line);
	write_lines(writer, &template.footer_lines);
	writer.write_utf(&template.material);
	writer.write_i32(template.by_status.len() as i32);

	for (status, over) in &template.by_status {
		writer.write_utf(status);
		write_template_override(writer, over);
	}
}

fn write_template_override(writer: &mut MessageWriter, over: &TemplateOverride) {
	write_optional(writer, over.name.as_ref(), |writer, value| {
		writer.write_utf(value);
	});
	write_optional(writer, over.header_lines.as_ref(), write_lines);
	write_optional(writer, over.body_line.as_ref(), |writer, value| {
		writer.write_utf(value);
	});
	write_optional(writer, over.footer_lines.as_ref(), write_lines);
}

fn write_lines(writer: &mut MessageWriter, lines: &Vec<String>) {
	writer.write_i32(lines.len() as i32);

	for line in lines {
		writer.write_utf(line);
	}
}

fn write_optional<T, F>(writer: &mut MessageWriter, value: Option<&T>, write: F)
where
	F: FnOnce(&mut MessageWriter, &T),
{
	writer.write_bool(value.is_some());

	if let Some(value) = value {
		write(writer, value);
	}
}

/// What a click sends back: who clicked, and which backend they picked.
///
/// The two reserved names are the proxy's, and it resolves them itself because
/// only it knows where a player has been.
pub const CONNECT_LOBBY: &str = "__lobby__";
pub const CONNECT_PREVIOUS: &str = "__previous__";

/// Write a connect request for one player.
#[must_use]
pub fn encode_connect(player_id: &str, backend_name: &str) -> Vec<u8> {
	let mut writer = MessageWriter::new();

	writer.write_utf(player_id).write_utf(backend_name);

	writer.into_vec()
}

#[cfg(test)]
mod tests {
	use super::*;

	fn layout() -> SelectorLayout {
		let mut servers = BTreeMap::new();

		servers.insert(
			"lobby".to_owned(),
			ServerEntry {
				backend_name: "lobby".to_owned(),
				display_name: "Sảnh".to_owned(),
				accent_color: "#7dd3fc".to_owned(),
				permission: String::new(),
				host_name: "lobby".to_owned(),
				slot: Some(10),
				page: Some(0),
				material: "minecraft:oak_door".to_owned(),
				material_by_status: [("OFFLINE".to_owned(), "minecraft:barrier".to_owned())]
					.into_iter()
					.collect(),
				glint: Some(true),
				glint_by_status: [("MAINT".to_owned(), false)].into_iter().collect(),
				conditional: vec![ConditionalOverride {
					condition: "%online_players% > 0".to_owned(),
					material: Some("minecraft:beacon".to_owned()),
					glint: Some(true),
					description: Some(vec!["<gray>đông người</gray>".to_owned()]),
					template: Some(TemplateOverride {
						name: Some("<green>%server_display%</green>".to_owned()),
						..TemplateOverride::default()
					}),
				}],
				description: vec!["<gray>Điểm bắt đầu</gray>".to_owned()],
				description_by_status: [(
					"OFFLINE".to_owned(),
					vec!["<red>đang tắt</red>".to_owned()],
				)]
				.into_iter()
				.collect(),
				template: Some(Template {
					name: "<b>%server_display%</b>".to_owned(),
					header_lines: vec!["<gray>—</gray>".to_owned()],
					body_line: "%line%".to_owned(),
					footer_lines: vec![],
					material: "minecraft:oak_door".to_owned(),
					by_status: [(
						"MAINT".to_owned(),
						TemplateOverride {
							body_line: Some("<yellow>%line%</yellow>".to_owned()),
							..TemplateOverride::default()
						},
					)]
					.into_iter()
					.collect(),
				}),
			},
		);

		SelectorLayout {
			gui_title: "Danh Sách Máy Chủ".to_owned(),
			template: Template::default(),
			status_colors: default_status_colors(),
			status_icons: default_status_icons(),
			servers,
		}
	}

	#[test]
	fn a_layout_round_trips_through_the_frame() {
		let original = layout();
		let decoded = decode_layout(&encode_layout(&original))
			.expect("decodes")
			.expect("is an open frame");

		assert_eq!(decoded, original);
	}

	#[test]
	fn another_action_on_the_channel_is_not_ours() {
		let mut writer = MessageWriter::new();
		writer.write_utf("close-v1");

		assert_eq!(decode_layout(&writer.into_vec()), Ok(None));
	}

	#[test]
	fn a_truncated_frame_is_an_error_rather_than_a_guess() {
		let encoded = encode_layout(&layout());

		assert!(decode_layout(&encoded[..encoded.len() / 2]).is_err());
	}

	/// `-1` is the writer's "nothing pinned"; every real slot survives.
	#[test]
	fn an_unpinned_slot_decodes_as_absent() {
		assert_eq!(optional_index(-1), None);
		assert_eq!(optional_index(0), Some(0));
		assert_eq!(optional_index(45), Some(45));
	}

	#[test]
	fn the_status_rule_matches_the_shared_resolver() {
		assert_eq!(resolve_status(true, false, false), STATUS_ONLINE);
		assert_eq!(resolve_status(false, false, false), STATUS_OFFLINE);
		assert_eq!(resolve_status(true, true, false), STATUS_MAINT);
		// no permission wins over everything, including being offline
		assert_eq!(resolve_status(false, true, true), STATUS_NOP);
	}

	#[test]
	fn a_per_status_material_wins_over_the_plain_one() {
		let entry = &layout().servers["lobby"];

		assert_eq!(entry.material_for(STATUS_OFFLINE), "minecraft:barrier");
		assert_eq!(entry.material_for(STATUS_ONLINE), "minecraft:oak_door");
	}

	#[test]
	fn a_per_status_glint_wins_over_the_plain_one() {
		let entry = &layout().servers["lobby"];

		assert_eq!(entry.glint_for(STATUS_MAINT), Some(false));
		assert_eq!(entry.glint_for(STATUS_ONLINE), Some(true));
	}

	#[test]
	fn a_per_status_description_wins_over_the_plain_one() {
		let entry = &layout().servers["lobby"];

		assert_eq!(entry.description_for(STATUS_OFFLINE), ["<red>đang tắt</red>"]);
		assert_eq!(entry.description_for(STATUS_ONLINE), ["<gray>Điểm bắt đầu</gray>"]);
	}

	#[test]
	fn a_template_override_keeps_what_it_does_not_mention() {
		let base = Template {
			name: "a".to_owned(),
			body_line: "b".to_owned(),
			..Template::default()
		};

		let applied = base.with_override(Some(&TemplateOverride {
			body_line: Some("c".to_owned()),
			..TemplateOverride::default()
		}));

		assert_eq!(applied.name, "a");
		assert_eq!(applied.body_line, "c");
	}

	#[test]
	fn a_server_template_replaces_the_menu_template() {
		let layout = layout();
		let entry = layout.servers.get("lobby");
		let resolved = layout.resolve_template(entry, STATUS_MAINT);

		// the server's own template wins wholesale, then its MAINT override
		assert_eq!(resolved.body_line, "<yellow>%line%</yellow>");
		assert_eq!(resolved.header_lines, ["<gray>—</gray>"]);
	}

	#[test]
	fn a_server_with_no_template_keeps_the_menus() {
		let layout = layout();
		let resolved = layout.resolve_template(None, STATUS_ONLINE);

		assert_eq!(resolved.body_line, Template::default().body_line);
	}

	#[test]
	fn a_lookup_is_case_insensitive() {
		let layout = layout();

		assert!(layout.server("LOBBY").is_some());
		assert!(layout.server(" lobby ").is_some());
		assert!(layout.server("nope").is_none());
	}

	#[test]
	fn an_unknown_status_falls_back_to_white_and_a_dot() {
		let layout = SelectorLayout::default();

		assert_eq!(layout.status_color("WEIRD"), "<white>");
		assert_eq!(layout.status_icon("WEIRD"), "●");
	}

	#[test]
	fn a_connect_request_names_the_player_then_the_backend() {
		let encoded = encode_connect("id", CONNECT_LOBBY);
		let mut reader = MessageReader::new(&encoded);

		assert_eq!(reader.read_utf(), Ok("id".to_owned()));
		assert_eq!(reader.read_utf(), Ok("__lobby__".to_owned()));
		assert_eq!(reader.remaining(), 0);
	}
}
