//! Turning a selector layout and the registry into the items on a page.
//!
//! This is `ServerSelectorEngine`, and it is shared on the JVM for the same
//! reason it is separate here: the proxy applies it to decide whether a player
//! may connect, and the backend applies it to draw the menu. The two disagreeing
//! is a bug the player sees as an item that will not click, so there is one
//! implementation and it is tested rather than eyeballed.
//!
//! The condition language is small but real - `status == OFFLINE`, `online > 0`,
//! `!has_permission && is_maint` - and the production config uses it, so it is
//! ported rather than approximated.

use crate::registry::BackendStatus;
use crate::selector::{
	ConditionalOverride, STATUS_MAINT, STATUS_NOP, STATUS_OFFLINE, STATUS_ONLINE, SelectorLayout,
	ServerEntry, Template, resolve_status,
};
use std::collections::BTreeMap;

/// Items a page of the menu holds, which is the chest minus its footer row.
pub const PAGE_SIZE: u32 = 45;

/// What a condition is evaluated against.
#[derive(Debug, Clone, Default)]
pub struct ConditionContext {
	pub status: String,
	pub server_name: String,
	pub host_name: String,
	pub server_display: String,
	pub online_players: u32,
	pub max_players: u32,
	pub whitelist_enabled: bool,
	pub no_permission: bool,
	pub tps: f64,
	pub cpu_usage: f64,
	pub latency_millis: u64,
	pub ram_percent: f64,
}

/// A value a condition can hold; `Missing` is what an unknown name resolves to.
#[derive(Debug, Clone, PartialEq)]
enum Value {
	Text(String),
	Number(f64),
	Bool(bool),
	Missing,
}

impl Value {
	/// Whether this counts as true on its own, with no comparison.
	fn truthy(&self) -> bool {
		match self {
			Self::Bool(value) => *value,
			Self::Number(value) => *value != 0.0,
			Self::Text(value) => !value.trim().is_empty() && !value.eq_ignore_ascii_case("false"),
			Self::Missing => false,
		}
	}

	fn as_number(&self) -> Option<f64> {
		match self {
			Self::Number(value) => Some(*value),
			Self::Bool(value) => Some(f64::from(u8::from(*value))),
			Self::Text(value) => value.trim().parse().ok(),
			Self::Missing => None,
		}
	}

	fn as_text(&self) -> String {
		match self {
			Self::Text(value) => value.clone(),
			Self::Number(value) => format!("{value}"),
			Self::Bool(value) => value.to_string(),
			Self::Missing => String::new(),
		}
	}
}

/// What one name means in this context.
fn variable(name: &str, context: &ConditionContext) -> Value {
	let status_is = |wanted: &str| Value::Bool(context.status.eq_ignore_ascii_case(wanted));

	match name.trim().to_ascii_lowercase().as_str() {
		"status" | "server_status" => Value::Text(context.status.clone()),
		"server_name" => Value::Text(context.server_name.clone()),
		"luna_host_name" | "luna_server_name" => Value::Text(context.host_name.clone()),
		"server_display" => Value::Text(context.server_display.clone()),
		"online" => Value::Number(f64::from(context.online_players)),
		"max" => Value::Number(f64::from(context.max_players)),
		"whitelist" | "maint" => Value::Bool(context.whitelist_enabled),
		"no_permission" | "nop" => Value::Bool(context.no_permission),
		"has_permission" => Value::Bool(!context.no_permission),
		"tps" => Value::Number(context.tps),
		"cpu_usage" => Value::Number(context.cpu_usage),
		"latency_ms" => Value::Number(context.latency_millis as f64),
		"ram_percent" => Value::Number(context.ram_percent),
		"is_online" => status_is(STATUS_ONLINE),
		"is_offline" => status_is(STATUS_OFFLINE),
		"is_maint" => status_is(STATUS_MAINT),
		"is_nop" => status_is(STATUS_NOP),
		_ => Value::Missing,
	}
}

/// Evaluate one condition expression.
///
/// An empty or unparseable expression is false rather than true: an override
/// that fires because nobody could read it would silently restyle every item.
#[must_use]
pub fn evaluate_condition(expression: &str, context: &ConditionContext) -> bool {
	if expression.trim().is_empty() {
		return false;
	}

	split_outside_quotes(expression, "||")
		.into_iter()
		.any(|clause| {
			split_outside_quotes(&clause, "&&")
				.into_iter()
				.all(|predicate| evaluate_predicate(&predicate, context))
		})
}

fn evaluate_predicate(raw: &str, context: &ConditionContext) -> bool {
	let predicate = trim_parentheses(raw.trim());

	if predicate.is_empty() {
		return false;
	}

	if let Some(rest) = predicate.strip_prefix('!') {
		return !evaluate_predicate(rest, context);
	}

	if predicate.eq_ignore_ascii_case("true") {
		return true;
	}

	if predicate.eq_ignore_ascii_case("false") {
		return false;
	}

	let Some((name, operator, right)) = split_comparison(&predicate) else {
		return variable(&predicate, context).truthy();
	};

	compare(&variable(&name, context), &operator, &literal(&right, context))
}

/// A right-hand side: a quoted string, a number, a bool, a variable, or text.
fn literal(raw: &str, context: &ConditionContext) -> Value {
	let trimmed = raw.trim();

	if (trimmed.starts_with('\'') && trimmed.ends_with('\'') && trimmed.len() >= 2)
		|| (trimmed.starts_with('"') && trimmed.ends_with('"') && trimmed.len() >= 2)
	{
		return Value::Text(trimmed[1..trimmed.len() - 1].to_owned());
	}

	if let Ok(number) = trimmed.parse::<f64>() {
		return Value::Number(number);
	}

	if trimmed.eq_ignore_ascii_case("true") {
		return Value::Bool(true);
	}

	if trimmed.eq_ignore_ascii_case("false") {
		return Value::Bool(false);
	}

	// `status == OFFLINE` compares against the bare word, so an unknown name is
	// text rather than nothing; that is the form the production config uses
	match variable(trimmed, context) {
		Value::Missing => Value::Text(trimmed.to_owned()),
		found => found,
	}
}

fn compare(left: &Value, operator: &str, right: &Value) -> bool {
	if let (Some(left), Some(right)) = (left.as_number(), right.as_number()) {
		return match operator {
			"==" => (left - right).abs() < f64::EPSILON,
			"!=" => (left - right).abs() >= f64::EPSILON,
			">" => left > right,
			"<" => left < right,
			">=" => left >= right,
			"<=" => left <= right,
			_ => false,
		};
	}

	// text compares case-insensitively, because the statuses are written both
	// ways across the configs and `status == offline` plainly means the same
	let (left, right) = (left.as_text(), right.as_text());

	match operator {
		"==" => left.eq_ignore_ascii_case(&right),
		"!=" => !left.eq_ignore_ascii_case(&right),
		_ => false,
	}
}

/// `name <op> rest`, when the predicate is a comparison at all.
fn split_comparison(predicate: &str) -> Option<(String, String, String)> {
	// longest first, or `>=` would split as `>` with a stray `=`
	for operator in ["==", "!=", ">=", "<=", ">", "<"] {
		let Some(at) = predicate.find(operator) else {
			continue;
		};

		let name = predicate[..at].trim();

		if name.is_empty() || !is_identifier(name) {
			continue;
		}

		return Some((
			name.to_owned(),
			(*operator).to_owned(),
			predicate[at + operator.len()..].trim().to_owned(),
		));
	}

	None
}

fn is_identifier(value: &str) -> bool {
	let mut chars = value.chars();

	chars
		.next()
		.is_some_and(|first| first.is_ascii_alphabetic() || first == '_')
		&& chars.all(|ch| ch.is_ascii_alphanumeric() || ch == '_')
}

/// Split on an operator, ignoring anything inside quotes.
fn split_outside_quotes(expression: &str, operator: &str) -> Vec<String> {
	let mut parts = Vec::new();
	let mut current = String::new();
	let mut quote: Option<char> = None;
	let chars: Vec<char> = expression.chars().collect();
	let needle: Vec<char> = operator.chars().collect();
	let mut index = 0;

	while index < chars.len() {
		let ch = chars[index];

		if let Some(open) = quote {
			current.push(ch);

			if ch == open {
				quote = None;
			}

			index += 1;
			continue;
		}

		if ch == '\'' || ch == '"' {
			quote = Some(ch);
			current.push(ch);
			index += 1;
			continue;
		}

		if chars[index..].starts_with(&needle[..]) {
			parts.push(current.trim().to_owned());
			current.clear();
			index += needle.len();
			continue;
		}

		current.push(ch);
		index += 1;
	}

	parts.push(current.trim().to_owned());
	parts
}

/// Peel off parentheses that wrap the whole expression.
fn trim_parentheses(value: &str) -> String {
	let mut current = value.trim().to_owned();

	while current.starts_with('(') && current.ends_with(')') && wrapped_by_one_pair(&current) {
		current = current[1..current.len() - 1].trim().to_owned();
	}

	current
}

fn wrapped_by_one_pair(value: &str) -> bool {
	let mut depth = 0i32;
	let mut quote: Option<char> = None;
	let last = value.chars().count() - 1;

	for (index, ch) in value.chars().enumerate() {
		if let Some(open) = quote {
			if ch == open {
				quote = None;
			}

			continue;
		}

		if ch == '\'' || ch == '"' {
			quote = Some(ch);
			continue;
		}

		if ch == '(' {
			depth += 1;
		} else if ch == ')' {
			depth -= 1;

			if depth == 0 && index < last {
				return false;
			}
		}
	}

	true
}

/// One server as it will be drawn.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RenderedItem {
	pub title: String,
	pub lore: Vec<String>,
	pub material: String,
	pub glint: Option<bool>,
	pub status: String,
}

/// One occupied slot: what the proxy says about it, and what the registry does.
#[derive(Debug, Clone)]
pub struct RenderEntry {
	pub entry: ServerEntry,
	pub status: Option<BackendStatus>,
}

/// Place every server on a page and a slot.
///
/// A server the proxy pinned keeps its slot; the rest are packed into whatever
/// is free, page by page. The proxy's page numbers are 1-based and the menu's
/// are 0-based, which is the one conversion in here worth knowing about.
#[must_use]
pub fn layout_by_page(
	layout: &SelectorLayout,
	registry: &[BackendStatus],
) -> BTreeMap<u32, BTreeMap<u32, RenderEntry>> {
	let by_name: BTreeMap<String, &BackendStatus> = registry
		.iter()
		.map(|status| (status.name.to_ascii_lowercase(), status))
		.collect();

	let mut pages: BTreeMap<u32, BTreeMap<u32, RenderEntry>> = BTreeMap::new();
	let mut unplaced = Vec::new();

	for entry in layout.servers.values() {
		let render = RenderEntry {
			status: by_name
				.get(&entry.backend_name.to_ascii_lowercase())
				.map(|status| (*status).clone()),
			entry: entry.clone(),
		};

		match (entry.slot, entry.page) {
			(Some(slot), Some(page)) if slot < PAGE_SIZE && page >= 1 => {
				pages.entry(page - 1).or_default().insert(slot, render);
			}
			_ => unplaced.push(render),
		}
	}

	let mut page = 0;

	for render in unplaced {
		loop {
			let occupied = pages.entry(page).or_default();

			let Some(slot) = (0..PAGE_SIZE).find(|slot| !occupied.contains_key(slot)) else {
				page += 1;
				continue;
			};

			occupied.insert(slot, render);
			break;
		}
	}

	pages
}

/// Draw one server's item.
#[must_use]
pub fn render_item(
	layout: &SelectorLayout,
	entry: &ServerEntry,
	status: Option<&BackendStatus>,
	no_permission: bool,
) -> RenderedItem {
	let online = status.is_some_and(|status| status.online);
	let whitelisted = status.is_some_and(|status| status.whitelist_enabled);
	let state = resolve_status(online, whitelisted, no_permission).to_owned();

	let display = pick(
		&entry.display_name,
		&[
			status.map_or("", BackendStatus::display),
			status.map_or("", |status| status.name.as_str()),
		],
	);

	let context = ConditionContext {
		status: state.clone(),
		server_name: status.map_or(String::new(), |status| status.name.clone()),
		host_name: pick(&entry.host_name, &[status.map_or("", |s| s.name.as_str())]),
		server_display: display.clone(),
		online_players: status.map_or(0, |status| status.online_players),
		max_players: status.map_or(0, |status| status.max_players),
		whitelist_enabled: whitelisted,
		no_permission,
		tps: status.map_or(0.0, |status| status.tps),
		cpu_usage: status.map_or(0.0, |status| status.cpu_percent),
		latency_millis: status.map_or(0, |status| status.latency_millis),
		ram_percent: status.map_or(0.0, ram_percent),
	};

	let conditional = resolve_conditional(entry, &context);
	let mut template = layout.resolve_template(Some(entry), &state);

	if let Some(over) = conditional.as_ref().and_then(|found| found.template.as_ref()) {
		template = template.with_override(Some(over));
	}

	let values = placeholders(layout, entry, status, &display, &state, &context);
	let mut lore = Vec::new();

	for line in &template.header_lines {
		lore.push(apply_template(line, &values));
	}

	let description = conditional
		.as_ref()
		.and_then(|found| found.description.as_deref())
		.unwrap_or_else(|| entry.description_for(&state));

	for line in description {
		let mut with_line = values.clone();

		with_line.insert("line".to_owned(), line.clone());
		lore.push(apply_template(&template.body_line, &with_line));
	}

	for line in &template.footer_lines {
		lore.push(apply_template(line, &values));
	}

	RenderedItem {
		title: apply_template(&template.name, &values),
		lore,
		material: resolve_material(entry, conditional.as_ref(), &template, &state),
		glint: conditional
			.as_ref()
			.and_then(|found| found.glint)
			.or_else(|| entry.glint_for(&state)),
		status: state,
	}
}

/// Every conditional whose expression holds, merged in order.
fn resolve_conditional(
	entry: &ServerEntry,
	context: &ConditionContext,
) -> Option<ConditionalOverride> {
	let mut merged: Option<ConditionalOverride> = None;

	for over in &entry.conditional {
		if over.condition.trim().is_empty() || !evaluate_condition(&over.condition, context) {
			continue;
		}

		merged = Some(match merged {
			None => over.clone(),
			Some(previous) => ConditionalOverride {
				condition: over.condition.clone(),
				material: over.material.clone().or(previous.material),
				glint: over.glint.or(previous.glint),
				description: over.description.clone().or(previous.description),
				template: over.template.clone().or(previous.template),
			},
		});
	}

	merged
}

fn resolve_material(
	entry: &ServerEntry,
	conditional: Option<&ConditionalOverride>,
	template: &Template,
	status: &str,
) -> String {
	if let Some(material) = conditional.and_then(|found| found.material.as_ref())
		&& !material.trim().is_empty()
	{
		return material.clone();
	}

	let from_entry = entry.material_for(status);

	if !from_entry.trim().is_empty() {
		return from_entry.to_owned();
	}

	template.material.clone()
}

/// Substitute `%name%` placeholders, leaving unknown ones alone.
///
/// Leaving them is deliberate and matches the JVM: a template naming a value
/// this build does not fill should read as an obviously unfilled slot rather
/// than quietly vanish.
#[must_use]
pub fn apply_template(text: &str, values: &BTreeMap<String, String>) -> String {
	let mut out = String::with_capacity(text.len());
	let mut rest = text;

	while let Some(open) = rest.find('%') {
		out.push_str(&rest[..open]);

		let after = &rest[open + 1..];

		let Some(close) = after.find('%') else {
			out.push_str(&rest[open..]);

			return out;
		};

		let name = &after[..close];

		match values.get(name) {
			Some(value) => out.push_str(value),
			None => {
				out.push('%');
				out.push_str(name);
				out.push('%');
			}
		}

		rest = &after[close + 1..];
	}

	out.push_str(rest);
	out
}

fn placeholders(
	layout: &SelectorLayout,
	entry: &ServerEntry,
	status: Option<&BackendStatus>,
	display: &str,
	state: &str,
	context: &ConditionContext,
) -> BTreeMap<String, String> {
	let mut values = BTreeMap::new();
	let mut set = |key: &str, value: String| {
		values.insert(key.to_owned(), value);
	};

	set("server_status", state.to_owned());
	set("server_status_color", layout.status_color(state).to_owned());
	set("server_status_icon", layout.status_icon(state).to_owned());
	set("server_display", display.to_owned());
	set("server_name", context.server_name.clone());
	set("luna_host_name", context.host_name.clone());
	set("server_accent_color", entry.accent_color.clone());
	set("online_players", context.online_players.to_string());
	set("max_players", context.max_players.to_string());
	set("server_software", status.map_or(String::new(), |s| s.software.clone()));
	set("server_version", status.map_or(String::new(), |s| s.version.clone()));
	set("server_version_full", status.map_or(String::new(), |s| s.version.clone()));
	set("server_motd", status.map_or(String::new(), |s| s.motd.clone()));
	set("tps", format!("{:.2}", context.tps));
	set("cpu_usage", format!("{:.1}", context.cpu_usage));
	set("latency_ms", context.latency_millis.to_string());
	set("ram_percent", format!("{:.1}", context.ram_percent));

	values
}

fn ram_percent(status: &BackendStatus) -> f64 {
	if status.ram_max_bytes == 0 {
		return 0.0;
	}

	((status.ram_used_bytes as f64 * 100.0) / status.ram_max_bytes as f64).min(100.0)
}

/// The first non-blank of a preferred value and its fallbacks.
fn pick(preferred: &str, fallbacks: &[&str]) -> String {
	if !preferred.trim().is_empty() {
		return preferred.to_owned();
	}

	fallbacks
		.iter()
		.find(|value| !value.trim().is_empty())
		.map_or(String::new(), |value| (*value).to_owned())
}

#[cfg(test)]
mod tests {
	use super::*;
	use crate::selector::default_status_colors;

	fn context() -> ConditionContext {
		ConditionContext {
			status: STATUS_ONLINE.to_owned(),
			server_name: "lobby".to_owned(),
			online_players: 4,
			max_players: 100,
			tps: 19.5,
			..ConditionContext::default()
		}
	}

	/// The exact expression the production config uses on every server.
	#[test]
	fn the_production_condition_works() {
		let mut context = context();

		assert!(!evaluate_condition("status == OFFLINE", &context));

		context.status = STATUS_OFFLINE.to_owned();
		assert!(evaluate_condition("status == OFFLINE", &context));
	}

	#[test]
	fn a_number_comparison_reads_as_a_number() {
		let context = context();

		assert!(evaluate_condition("online > 0", &context));
		assert!(evaluate_condition("online >= 4", &context));
		assert!(!evaluate_condition("online > 4", &context));
		assert!(evaluate_condition("tps < 20", &context));
	}

	/// `>=` must not split as `>`, or the right side becomes `=4`.
	#[test]
	fn the_longer_operator_wins() {
		let context = context();

		assert!(evaluate_condition("online <= 4", &context));
		assert!(evaluate_condition("online != 5", &context));
	}

	#[test]
	fn and_or_and_not_compose() {
		let context = context();

		assert!(evaluate_condition("online > 0 && is_online", &context));
		assert!(!evaluate_condition("online > 0 && status == OFFLINE", &context));
		assert!(evaluate_condition("status == OFFLINE || online > 0", &context));
		assert!(evaluate_condition("!is_offline", &context));
		assert!(evaluate_condition("(online > 0)", &context));
	}

	#[test]
	fn a_bare_variable_is_its_own_truth() {
		let mut context = context();

		assert!(evaluate_condition("is_online", &context));
		assert!(!evaluate_condition("is_offline", &context));

		context.no_permission = true;
		assert!(evaluate_condition("nop", &context));
		assert!(!evaluate_condition("has_permission", &context));
	}

	#[test]
	fn an_empty_or_unreadable_condition_is_false() {
		let context = context();

		assert!(!evaluate_condition("", &context));
		assert!(!evaluate_condition("   ", &context));
		assert!(!evaluate_condition("nonsense_name", &context));
	}

	/// A trap the JVM has and this keeps, because keeping it is the parity.
	///
	/// The right-hand side of a comparison is resolved as a variable before it
	/// is taken as text, and `ONLINE` lowercases to `online` - the player count.
	/// So `status == ONLINE` compares a word against a number and is always
	/// false, on Paper exactly as here. `status == OFFLINE` is fine because
	/// there is no `offline` variable, which is why the production config works;
	/// `is_online` is the spelling that means what it looks like.
	#[test]
	fn the_right_hand_side_resolves_as_a_variable_first() {
		let context = context();

		assert!(!evaluate_condition("status == ONLINE", &context));
		assert!(evaluate_condition("is_online", &context));
		assert!(evaluate_condition("status == 'ONLINE'", &context));
	}

	#[test]
	fn a_quoted_value_keeps_its_spaces() {
		let mut context = context();
		context.server_display = "Sảnh Chính".to_owned();

		assert!(evaluate_condition("server_display == 'Sảnh Chính'", &context));
	}

	#[test]
	fn a_pinned_slot_is_kept_and_the_page_is_one_based() {
		let mut layout = SelectorLayout::default();

		layout.servers.insert(
			"lobby".to_owned(),
			ServerEntry {
				backend_name: "lobby".to_owned(),
				slot: Some(10),
				page: Some(1),
				..ServerEntry::default()
			},
		);

		let pages = layout_by_page(&layout, &[]);

		assert!(pages[&0].contains_key(&10));
	}

	#[test]
	fn an_unpinned_server_is_packed_into_the_first_free_slot() {
		let mut layout = SelectorLayout::default();

		for name in ["a", "b"] {
			layout.servers.insert(
				name.to_owned(),
				ServerEntry {
					backend_name: name.to_owned(),
					..ServerEntry::default()
				},
			);
		}

		let pages = layout_by_page(&layout, &[]);

		assert_eq!(pages[&0].len(), 2);
		assert!(pages[&0].contains_key(&0));
		assert!(pages[&0].contains_key(&1));
	}

	#[test]
	fn more_servers_than_a_page_holds_spill_onto_the_next() {
		let mut layout = SelectorLayout::default();

		for index in 0..(PAGE_SIZE + 3) {
			let name = format!("s{index:03}");

			layout.servers.insert(
				name.clone(),
				ServerEntry {
					backend_name: name,
					..ServerEntry::default()
				},
			);
		}

		let pages = layout_by_page(&layout, &[]);

		assert_eq!(pages[&0].len(), PAGE_SIZE as usize);
		assert_eq!(pages[&1].len(), 3);
	}

	#[test]
	fn a_slot_out_of_range_is_packed_rather_than_dropped() {
		let mut layout = SelectorLayout::default();

		layout.servers.insert(
			"lobby".to_owned(),
			ServerEntry {
				backend_name: "lobby".to_owned(),
				slot: Some(999),
				page: Some(1),
				..ServerEntry::default()
			},
		);

		let pages = layout_by_page(&layout, &[]);

		assert!(pages[&0].contains_key(&0));
	}

	#[test]
	fn placeholders_are_substituted_and_unknown_ones_left_alone() {
		let values = [("server_display".to_owned(), "Sảnh".to_owned())]
			.into_iter()
			.collect();

		assert_eq!(apply_template("<b>%server_display%</b>", &values), "<b>Sảnh</b>");
		assert_eq!(apply_template("%nope%", &values), "%nope%");
		assert_eq!(apply_template("100%", &values), "100%");
		assert_eq!(apply_template("no markers", &values), "no markers");
	}

	#[test]
	fn an_item_renders_its_title_lore_and_status() {
		let mut layout = SelectorLayout::default();

		layout.status_colors = default_status_colors();
		layout.template = Template {
			name: "%server_status_color%%server_display%".to_owned(),
			header_lines: vec!["<gray>%online_players%/%max_players%</gray>".to_owned()],
			body_line: "%line%".to_owned(),
			footer_lines: vec![],
			material: "minecraft:stone".to_owned(),
			by_status: BTreeMap::new(),
		};

		let entry = ServerEntry {
			backend_name: "lobby".to_owned(),
			display_name: "Sảnh".to_owned(),
			description: vec!["<gray>vào đây</gray>".to_owned()],
			..ServerEntry::default()
		};

		let status = BackendStatus {
			name: "lobby".to_owned(),
			online: true,
			online_players: 7,
			max_players: 50,
			..BackendStatus::default()
		};

		let item = render_item(&layout, &entry, Some(&status), false);

		assert_eq!(item.status, STATUS_ONLINE);
		assert_eq!(item.title, "<green>Sảnh");
		assert_eq!(item.lore, ["<gray>7/50</gray>", "<gray>vào đây</gray>"]);
		assert_eq!(item.material, "minecraft:stone");
	}

	/// The whole point of the conditional in the production config: an offline
	/// server turns red and stops glinting.
	#[test]
	fn a_conditional_override_applies_when_its_condition_holds() {
		let layout = SelectorLayout::default();
		let entry = ServerEntry {
			backend_name: "lobby".to_owned(),
			material: "minecraft:grass_block".to_owned(),
			glint: Some(true),
			conditional: vec![ConditionalOverride {
				condition: "status == OFFLINE".to_owned(),
				material: Some("minecraft:red_concrete".to_owned()),
				glint: Some(false),
				..ConditionalOverride::default()
			}],
			..ServerEntry::default()
		};

		let online = render_item(&layout, &entry, Some(&BackendStatus {
			name: "lobby".to_owned(),
			online: true,
			..BackendStatus::default()
		}), false);

		assert_eq!(online.material, "minecraft:grass_block");
		assert_eq!(online.glint, Some(true));

		let offline = render_item(&layout, &entry, None, false);

		assert_eq!(offline.status, STATUS_OFFLINE);
		assert_eq!(offline.material, "minecraft:red_concrete");
		assert_eq!(offline.glint, Some(false));
	}

	#[test]
	fn a_player_without_permission_sees_the_nop_status() {
		let layout = SelectorLayout::default();
		let entry = ServerEntry {
			backend_name: "lobby".to_owned(),
			permission: "luna.server.lobby".to_owned(),
			..ServerEntry::default()
		};

		let item = render_item(&layout, &entry, Some(&BackendStatus {
			name: "lobby".to_owned(),
			online: true,
			..BackendStatus::default()
		}), true);

		assert_eq!(item.status, STATUS_NOP);
	}

	#[test]
	fn a_per_status_description_reaches_the_lore() {
		let layout = SelectorLayout::default();
		let entry = ServerEntry {
			backend_name: "lobby".to_owned(),
			description: vec!["mở".to_owned()],
			description_by_status: [(STATUS_OFFLINE.to_owned(), vec!["đóng".to_owned()])]
				.into_iter()
				.collect(),
			..ServerEntry::default()
		};

		assert_eq!(render_item(&layout, &entry, None, false).lore, ["đóng"]);
	}
}
