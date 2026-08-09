//! Messages held for a player whose connection is not ready yet.
//!
//! A client discards a plugin message on a channel it has not registered, and it
//! registers channels a moment after login. Anything sent inside that window is
//! therefore held rather than dropped - and held *in order*, because re-sending
//! later behind messages that were queued first would deliver a stale value
//! last, which for a tab-list prefix or a placeholder is exactly the bug the
//! resend was meant to avoid.
//!
//! This is the pure half of that: a bounded, ordered queue per player. Whether a
//! send succeeded is the platform's business; keeping the order straight when it
//! does not is this.

use std::collections::{HashMap, VecDeque};

/// One held message: the channel it goes on, and its bytes.
pub type Held = (String, Vec<u8>);

/// Held messages, per player.
#[derive(Debug, Default)]
pub struct PendingMessages {
	queues: HashMap<String, VecDeque<Held>>,
	capacity: usize,
}

impl PendingMessages {
	/// A queue holding at most `capacity` messages per player.
	///
	/// The cap is not an optimisation: a player whose window somehow never closes
	/// would otherwise accumulate one for as long as they stay connected.
	#[must_use]
	pub fn new(capacity: usize) -> Self {
		Self {
			queues: HashMap::new(),
			capacity: capacity.max(1),
		}
	}

	/// Hold one message. Returns the message dropped to make room, if any.
	///
	/// The *oldest* is what goes: at the cap something has already gone wrong,
	/// and the stalest value is the one least worth delivering.
	pub fn push(&mut self, id: &str, channel: &str, payload: &[u8]) -> Option<Held> {
		let queue = self.queues.entry(id.to_owned()).or_default();
		let dropped = if queue.len() >= self.capacity {
			queue.pop_front()
		} else {
			None
		};

		queue.push_back((channel.to_owned(), payload.to_vec()));

		dropped
	}

	/// Take the next message for this player, oldest first.
	pub fn pop(&mut self, id: &str) -> Option<Held> {
		let queue = self.queues.get_mut(id)?;
		let next = queue.pop_front();

		if queue.is_empty() {
			self.queues.remove(id);
		}

		next
	}

	/// Put one back at the front, for a send that failed after `pop`.
	pub fn unpop(&mut self, id: &str, message: Held) {
		self.queues.entry(id.to_owned()).or_default().push_front(message);
	}

	/// Drop everything held for a player who left.
	pub fn forget(&mut self, id: &str) {
		self.queues.remove(id);
	}

	/// Players with something waiting.
	#[must_use]
	pub fn waiting(&self) -> Vec<String> {
		self.queues.keys().cloned().collect()
	}

	#[must_use]
	pub fn is_empty(&self) -> bool {
		self.queues.is_empty()
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn drain(pending: &mut PendingMessages, id: &str) -> Vec<String> {
		let mut seen = Vec::new();

		while let Some((channel, _)) = pending.pop(id) {
			seen.push(channel);
		}

		seen
	}

	#[test]
	fn messages_come_back_in_the_order_they_went_in() {
		let mut pending = PendingMessages::new(8);

		pending.push("a", "one", b"1");
		pending.push("a", "two", b"2");
		pending.push("a", "three", b"3");

		assert_eq!(drain(&mut pending, "a"), ["one", "two", "three"]);
	}

	#[test]
	fn a_failed_send_goes_back_to_the_front() {
		let mut pending = PendingMessages::new(8);

		pending.push("a", "one", b"1");
		pending.push("a", "two", b"2");

		let taken = pending.pop("a").expect("something was queued");

		pending.unpop("a", taken);

		assert_eq!(drain(&mut pending, "a"), ["one", "two"]);
	}

	#[test]
	fn at_the_cap_the_stalest_message_is_what_goes() {
		let mut pending = PendingMessages::new(2);

		assert!(pending.push("a", "one", b"1").is_none());
		assert!(pending.push("a", "two", b"2").is_none());

		let dropped = pending.push("a", "three", b"3");

		assert_eq!(dropped.map(|(channel, _)| channel), Some("one".to_owned()));
		assert_eq!(drain(&mut pending, "a"), ["two", "three"]);
	}

	#[test]
	fn one_players_queue_is_not_anothers() {
		let mut pending = PendingMessages::new(8);

		pending.push("a", "for-a", b"1");
		pending.push("b", "for-b", b"2");

		assert_eq!(drain(&mut pending, "a"), ["for-a"]);
		assert_eq!(drain(&mut pending, "b"), ["for-b"]);
	}

	#[test]
	fn an_emptied_queue_leaves_nothing_behind() {
		let mut pending = PendingMessages::new(8);

		pending.push("a", "one", b"1");
		pending.pop("a");

		assert!(pending.is_empty(), "an emptied player's entry should be gone");
		assert!(pending.waiting().is_empty());
	}

	#[test]
	fn a_player_who_left_takes_their_queue_with_them() {
		let mut pending = PendingMessages::new(8);

		pending.push("a", "one", b"1");
		pending.forget("a");

		assert!(pending.is_empty());
	}

	#[test]
	fn a_capacity_of_zero_still_holds_something() {
		let mut pending = PendingMessages::new(0);

		pending.push("a", "one", b"1");

		assert_eq!(drain(&mut pending, "a"), ["one"]);
	}
}
