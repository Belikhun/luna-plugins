//! Per-player rate limiting for work that runs on every tick or every event.
//!
//! The auth backend is built out of these. A move event fires many times a
//! second per player, and the things it has to do - re-assert the lock, log
//! that a move was refused, drag somebody back to the spawn point - are all
//! worth doing occasionally and ruinous to do each time. The JVM plugin carries
//! eight `ConcurrentMap<UUID, Long>` for exactly this; here they are one type
//! with the comparison written once.
//!
//! Time is passed in rather than read, so the behaviour is testable without
//! sleeping and so a caller that already knows "now" does not read the clock
//! again per key.

use std::collections::BTreeMap;

/// Remembers when each key last ran, and answers whether it may run again.
#[derive(Debug, Clone)]
pub struct Throttle {
	interval_millis: u64,
	last: BTreeMap<String, u64>,
}

impl Throttle {
	#[must_use]
	pub fn new(interval_millis: u64) -> Self {
		Self {
			interval_millis,
			last: BTreeMap::new(),
		}
	}

	/// Whether this key may run now, recording the time when it may.
	///
	/// The first call for a key always passes: a throttle is there to stop a
	/// repeat, and making the first one wait would delay every player's join by
	/// the interval.
	pub fn due(&mut self, key: &str, now_millis: u64) -> bool {
		// A clock that went backwards lets the key run: the alternative is
		// refusing it until the clock catches up, which on a machine that just
		// stepped its time back an hour means an hour of silence.
		if let Some(last) = self.last.get(key)
			&& now_millis >= *last
			&& now_millis - *last < self.interval_millis
		{
			return false;
		}

		self.last.insert(key.to_owned(), now_millis);

		true
	}

	/// Let this key run again immediately, without waiting out the interval.
	pub fn reset(&mut self, key: &str) {
		self.last.remove(key);
	}

	/// Drop a key entirely, which is what keeps the map bounded by the roster.
	pub fn forget(&mut self, key: &str) {
		self.last.remove(key);
	}

	/// How many keys are remembered; the tests and nothing else care.
	#[must_use]
	pub fn tracked(&self) -> usize {
		self.last.len()
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn the_first_run_is_never_delayed() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 0));
	}

	#[test]
	fn a_repeat_inside_the_interval_is_refused() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 5_000));
		assert!(!throttle.due("a", 5_999));
		assert!(throttle.due("a", 6_000));
	}

	/// A refused call must not push the deadline out, or a player generating
	/// events faster than the interval would never be let through at all.
	#[test]
	fn a_refused_call_does_not_extend_the_wait() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 0));
		assert!(!throttle.due("a", 900));
		assert!(throttle.due("a", 1_000));
	}

	#[test]
	fn keys_are_throttled_independently() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 0));
		assert!(throttle.due("b", 0));
		assert!(!throttle.due("a", 500));
	}

	#[test]
	fn resetting_lets_a_key_run_at_once() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 0));
		throttle.reset("a");
		assert!(throttle.due("a", 1));
	}

	#[test]
	fn forgetting_a_key_stops_tracking_it() {
		let mut throttle = Throttle::new(1_000);

		throttle.due("a", 0);
		assert_eq!(throttle.tracked(), 1);

		throttle.forget("a");
		assert_eq!(throttle.tracked(), 0);
	}

	/// A clock that jumps backwards - which a wall clock does - must not lock a
	/// key out until it catches up.
	#[test]
	fn a_clock_that_went_backwards_does_not_wedge_a_key() {
		let mut throttle = Throttle::new(1_000);

		assert!(throttle.due("a", 10_000));
		assert!(throttle.due("a", 1_000));
	}
}
