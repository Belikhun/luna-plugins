//! Permission-node shapes shared between platforms.
//!
//! The nodes themselves are luna's own and are the same everywhere: a group on a
//! Paper backend and the same group on a Pumpkin one both hold
//! `luna.countdown.manage`. What differs is how the host hands a node back when
//! it asks whether a player holds it, which is what this module normalises.

/// Strip a plugin's own namespace off a node, leaving anyone else's on.
///
/// Pumpkin qualifies a plugin's command permission with that plugin's name, so
/// registering `luna.countdown.manage` produces the check
/// `luna-core:luna.countdown.manage`. The permission store deliberately knows
/// nothing about that: its nodes are the ones LuckPerms holds on the JVM
/// backends, and a group that resolves differently per platform is not a group.
/// So the qualifier is undone at the boundary that introduced it.
///
/// A foreign namespace is left alone. `minecraft:command.gamemode` is vanilla's
/// node, not luna's, and reducing it to `command.gamemode` would invite the
/// store to answer for something nobody asked it about.
#[must_use]
pub fn unqualify<'a>(node: &'a str, namespace: &str) -> &'a str {
	node.strip_prefix(namespace)
		.and_then(|rest| rest.strip_prefix(':'))
		.unwrap_or(node)
}

#[cfg(test)]
mod tests {
	use super::*;

	const OURS: &str = "luna-core";

	#[test]
	fn our_own_namespace_comes_back_off() {
		assert_eq!(
			unqualify("luna-core:luna.countdown.manage", OURS),
			"luna.countdown.manage"
		);
	}

	#[test]
	fn someone_elses_namespace_stays_on() {
		assert_eq!(unqualify("minecraft:command.gamemode", OURS), "minecraft:command.gamemode");
		assert_eq!(unqualify("pumpkin:command.pumpkin", OURS), "pumpkin:command.pumpkin");
	}

	#[test]
	fn an_unqualified_node_passes_through() {
		assert_eq!(unqualify("luna.countdown.manage", OURS), "luna.countdown.manage");
	}

	/// A plugin whose name merely starts with ours must keep its qualifier: the
	/// colon is what ends a namespace, not the prefix match on its own.
	#[test]
	fn a_near_miss_is_not_stripped() {
		assert_eq!(unqualify("luna-core-extra:thing", OURS), "luna-core-extra:thing");
	}
}
