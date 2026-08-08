//! A permission service for Pumpkin backends, shaped like LuckPerms' read side.
//!
//! Pumpkin's own `permission.wit` is vanilla op levels plus node declarations:
//! there are no groups, no inheritance, no prefixes. Everything luna displays
//! about a player - their rank in the tab list, the colour on their name, who
//! may open the server selector - is built on those, so a Pumpkin backend needs
//! them from somewhere.
//!
//! This provides the **checking** half only, deliberately. Editing groups stays
//! where it already works: the cluster's own tooling writes the file, and a
//! backend reads it. That keeps this crate small enough to be obviously correct,
//! and it avoids a second source of truth for who is in which group.
//!
//! The resolution rules follow LuckPerms closely enough that a config written
//! for one reads the same here:
//!
//!  - a group may inherit other groups, and inheritance is transitive
//!  - a more **specific** node beats a less specific one, so `luna.admin.kick`
//!    overrides `luna.admin.*`, which overrides `*`
//!  - at equal specificity the **heavier** group wins, and a user's own nodes
//!    outweigh any group's
//!  - a node prefixed with `-` is a denial, and denials win ties

pub mod config;

use std::collections::{BTreeMap, HashMap, HashSet};

/// A group's definition: what it grants, what it looks like, what it inherits.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Group {
	pub name: String,
	/// Higher wins when two groups disagree at the same specificity.
	pub weight: i32,
	pub prefix: String,
	pub suffix: String,
	/// Permission nodes, a leading `-` meaning denied.
	pub permissions: Vec<String>,
	/// Groups this one inherits, by name.
	pub inherits: Vec<String>,
}

/// One player's own entry.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct User {
	pub groups: Vec<String>,
	pub permissions: Vec<String>,
}

/// Everything the service knows.
#[derive(Debug, Clone, Default)]
pub struct PermissionStore {
	groups: BTreeMap<String, Group>,
	users: HashMap<String, User>,
	default_group: String,
}

/// How a node matched, so the strongest answer can be picked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Match {
	/// Segments matched literally; a wildcard contributes none.
	specificity: usize,
	weight: i32,
	granted: bool,
	/// A user's own node outranks every group's.
	from_user: bool,
}

impl PermissionStore {
	#[must_use]
	pub fn new(default_group: impl Into<String>) -> Self {
		Self {
			groups: BTreeMap::new(),
			users: HashMap::new(),
			default_group: default_group.into(),
		}
	}

	pub fn insert_group(&mut self, group: Group) -> &mut Self {
		self.groups.insert(group.name.to_lowercase(), group);
		self
	}

	pub fn insert_user(&mut self, id: impl Into<String>, user: User) -> &mut Self {
		self.users.insert(id.into().to_lowercase(), user);
		self
	}

	#[must_use]
	pub fn group(&self, name: &str) -> Option<&Group> {
		self.groups.get(&name.to_lowercase())
	}

	/// Whether `id` holds `node`.
	///
	/// Unknown players are not a special case: they resolve through the default
	/// group like everyone else, which is what makes a fresh join behave.
	#[must_use]
	pub fn has_permission(&self, id: &str, node: &str) -> bool {
		self.resolve(id, node).is_some_and(|found| found.granted)
	}

	/// What this store has to say about `node`, or `None` when nothing matched.
	///
	/// The absent case is the one that matters: a store that mentions a node
	/// nowhere must not be read as denying it, or wiring this in front of the
	/// server's own permission check would strip every player of the vanilla op
	/// levels luna never had an opinion about.
	#[must_use]
	pub fn decide(&self, id: &str, node: &str) -> Option<bool> {
		self.resolve(id, node).map(|found| found.granted)
	}

	/// The player's strongest group, which is what a rank is displayed from.
	#[must_use]
	pub fn primary_group(&self, id: &str) -> Option<&Group> {
		self.groups_of(id)
			.into_iter()
			.filter_map(|name| self.groups.get(&name))
			.max_by_key(|group| group.weight)
	}

	/// The prefix to draw before this player's name, or empty.
	#[must_use]
	pub fn prefix(&self, id: &str) -> String {
		self.primary_group(id)
			.map(|group| group.prefix.clone())
			.unwrap_or_default()
	}

	/// The suffix to draw after this player's name, or empty.
	#[must_use]
	pub fn suffix(&self, id: &str) -> String {
		self.primary_group(id)
			.map(|group| group.suffix.clone())
			.unwrap_or_default()
	}

	/// The group name to display, falling back to the default group's name.
	#[must_use]
	pub fn group_name(&self, id: &str) -> String {
		self.primary_group(id)
			.map(|group| group.name.clone())
			.unwrap_or_else(|| self.default_group.clone())
	}

	fn resolve(&self, id: &str, node: &str) -> Option<Match> {
		let wanted = node.to_lowercase();
		let mut best: Option<Match> = None;

		if let Some(user) = self.users.get(&id.to_lowercase()) {
			for entry in &user.permissions {
				consider(&mut best, entry, &wanted, i32::MAX, true);
			}
		}

		for name in self.groups_of(id) {
			let Some(group) = self.groups.get(&name) else {
				continue;
			};

			for entry in &group.permissions {
				consider(&mut best, entry, &wanted, group.weight, false);
			}
		}

		best
	}

	/// Every group this player is in, including inherited ones.
	fn groups_of(&self, id: &str) -> HashSet<String> {
		let mut names: Vec<String> = self
			.users
			.get(&id.to_lowercase())
			.map(|user| user.groups.iter().map(|g| g.to_lowercase()).collect())
			.unwrap_or_default();

		if names.is_empty() && !self.default_group.is_empty() {
			names.push(self.default_group.to_lowercase());
		}

		let mut seen: HashSet<String> = HashSet::new();

		// breadth-first over inheritance; `seen` is also the cycle guard, so a
		// config where two groups inherit each other resolves instead of hanging
		while let Some(name) = names.pop() {
			if !seen.insert(name.clone()) {
				continue;
			}

			if let Some(group) = self.groups.get(&name) {
				for parent in &group.inherits {
					names.push(parent.to_lowercase());
				}
			}
		}

		seen
	}
}

/// Score one declared node against the one being asked about.
fn consider(best: &mut Option<Match>, entry: &str, wanted: &str, weight: i32, from_user: bool) {
	let (granted, pattern) = match entry.strip_prefix('-') {
		Some(rest) => (false, rest.to_lowercase()),
		None => (true, entry.to_lowercase()),
	};

	let Some(specificity) = match_specificity(&pattern, wanted) else {
		return;
	};

	let candidate = Match {
		specificity,
		weight,
		granted,
		from_user,
	};

	if best.is_none_or(|current| beats(candidate, current)) {
		*best = Some(candidate);
	}
}

/// Whether `candidate` should replace `current`.
fn beats(candidate: Match, current: Match) -> bool {
	// a user's own node settles it regardless of how broad it is
	if candidate.from_user != current.from_user {
		return candidate.from_user;
	}

	if candidate.specificity != current.specificity {
		return candidate.specificity > current.specificity;
	}

	if candidate.weight != current.weight {
		return candidate.weight > current.weight;
	}

	// a tie means the config says both; refusing is the safer reading
	!candidate.granted && current.granted
}

/// How specifically `pattern` matches `node`, or `None` if it does not.
///
/// The score is the count of literally matched segments, so `a.b.c` (3) beats
/// `a.b.*` (2) beats `a.*` (1) beats `*` (0).
fn match_specificity(pattern: &str, node: &str) -> Option<usize> {
	if pattern == "*" {
		return Some(0);
	}

	if pattern == node {
		return Some(node.split('.').count());
	}

	let prefix = pattern.strip_suffix(".*")?;

	if node == prefix || node.starts_with(&format!("{prefix}.")) {
		return Some(prefix.split('.').count());
	}

	None
}

#[cfg(test)]
mod tests {
	use super::*;

	fn store() -> PermissionStore {
		let mut store = PermissionStore::new("default");

		store.insert_group(Group {
			name: "default".into(),
			weight: 0,
			prefix: "&7".into(),
			suffix: String::new(),
			permissions: vec!["luna.selector.open".into()],
			inherits: vec![],
		});

		store.insert_group(Group {
			name: "mod".into(),
			weight: 50,
			prefix: "&a[Mod] ".into(),
			suffix: String::new(),
			permissions: vec!["luna.admin.*".into(), "-luna.admin.stop".into()],
			inherits: vec!["default".into()],
		});

		store.insert_group(Group {
			name: "admin".into(),
			weight: 100,
			prefix: "&c[Admin] ".into(),
			suffix: " &7★".into(),
			permissions: vec!["*".into()],
			inherits: vec!["mod".into()],
		});

		store
	}

	#[test]
	fn an_unknown_player_gets_the_default_group() {
		let store = store();

		assert!(store.has_permission("nobody", "luna.selector.open"));
		assert!(!store.has_permission("nobody", "luna.admin.kick"));
		assert_eq!(store.group_name("nobody"), "default");
	}

	#[test]
	fn a_wildcard_grants_everything_under_it() {
		let mut store = store();
		store.insert_user("mod-1", User {
			groups: vec!["mod".into()],
			permissions: vec![],
		});

		assert!(store.has_permission("mod-1", "luna.admin.kick"));
	}

	#[test]
	fn a_more_specific_denial_beats_a_broad_grant() {
		let mut store = store();
		store.insert_user("mod-1", User {
			groups: vec!["mod".into()],
			permissions: vec![],
		});

		// luna.admin.* grants, -luna.admin.stop is more specific and denies
		assert!(!store.has_permission("mod-1", "luna.admin.stop"));
	}

	#[test]
	fn inheritance_is_transitive() {
		let mut store = store();
		store.insert_user("admin-1", User {
			groups: vec!["admin".into()],
			permissions: vec![],
		});

		// admin inherits mod inherits default
		assert!(store.has_permission("admin-1", "luna.selector.open"));
	}

	#[test]
	fn a_heavier_group_wins_at_equal_specificity() {
		let mut store = store();
		store.insert_user("admin-1", User {
			groups: vec!["admin".into()],
			permissions: vec![],
		});

		// mod denies luna.admin.stop at specificity 3; admin's `*` is 0, so the
		// denial still stands - weight only settles a tie
		assert!(!store.has_permission("admin-1", "luna.admin.stop"));
	}

	#[test]
	fn a_users_own_node_outranks_every_group() {
		let mut store = store();
		store.insert_user("mod-1", User {
			groups: vec!["mod".into()],
			permissions: vec!["luna.admin.stop".into()],
		});

		assert!(store.has_permission("mod-1", "luna.admin.stop"));
	}

	#[test]
	fn the_primary_group_is_the_heaviest_one() {
		let mut store = store();
		store.insert_user("admin-1", User {
			groups: vec!["admin".into(), "mod".into()],
			permissions: vec![],
		});

		assert_eq!(store.group_name("admin-1"), "admin");
		assert_eq!(store.prefix("admin-1"), "&c[Admin] ");
		assert_eq!(store.suffix("admin-1"), " &7★");
	}

	#[test]
	fn a_node_nobody_mentions_has_no_opinion() {
		let store = store();

		// nothing in the config says anything about a vanilla node, so the
		// server's own answer has to survive
		assert_eq!(store.decide("nobody", "minecraft.command.gamemode"), None);
		assert_eq!(store.decide("nobody", "luna.selector.open"), Some(true));
	}

	#[test]
	fn a_denial_is_an_opinion_rather_than_an_absence() {
		let mut store = store();
		store.insert_user("mod-1", User {
			groups: vec!["mod".into()],
			permissions: vec![],
		});

		assert_eq!(store.decide("mod-1", "luna.admin.stop"), Some(false));
	}

	#[test]
	fn a_cycle_in_inheritance_resolves_instead_of_hanging() {
		let mut store = PermissionStore::new("a");
		store.insert_group(Group {
			name: "a".into(),
			inherits: vec!["b".into()],
			permissions: vec!["x".into()],
			..Group::default()
		});
		store.insert_group(Group {
			name: "b".into(),
			inherits: vec!["a".into()],
			permissions: vec!["y".into()],
			..Group::default()
		});

		assert!(store.has_permission("anyone", "x"));
		assert!(store.has_permission("anyone", "y"));
	}

	#[test]
	fn node_matching_scores_by_literal_segments() {
		assert_eq!(match_specificity("*", "a.b.c"), Some(0));
		assert_eq!(match_specificity("a.*", "a.b.c"), Some(1));
		assert_eq!(match_specificity("a.b.*", "a.b.c"), Some(2));
		assert_eq!(match_specificity("a.b.c", "a.b.c"), Some(3));
		assert_eq!(match_specificity("a.b.*", "a.b"), Some(2));
		assert_eq!(match_specificity("a.b", "a.b.c"), None);
		assert_eq!(match_specificity("x.*", "a.b.c"), None);
	}

	#[test]
	fn a_prefix_wildcard_does_not_match_a_longer_sibling() {
		// `luna.admin.*` must not answer for `luna.administration`
		assert_eq!(match_specificity("luna.admin.*", "luna.administration"), None);
	}
}
