//! The figures a sandboxed backend cannot measure about itself.
//!
//! A JVM backend reads its heap and its process load from inside and puts them
//! straight on its heartbeat. A Pumpkin plugin is a wasm component with exactly
//! one preopened directory and no `/proc`, so nothing in the sandbox can see
//! them - `get-sys-info` reports the whole *machine*, which would have a
//! backend claiming the host's entire memory.
//!
//! The luna daemon already samples both, per instance, for the console's own
//! columns. It leaves the latest sample in the plugin's data folder, which is
//! the one directory the sandbox may read, and this reads it back.
//!
//! The file is form-encoded with the heartbeat's own field names, because that
//! is what it is: the missing half of a heartbeat, filled in from outside. The
//! decoder is therefore [`crate::heartbeat::decode_form`] rather than a second
//! format to keep in step.

use crate::heartbeat::decode_form;

/// How long a sample stands for.
///
/// Long enough to ride out a daemon restart at a five-second cadence, short
/// enough that a machine whose daemon died stops claiming a load it no longer
/// measures. Reporting nothing is honest; reporting a frozen number is not.
pub const FRESH_FOR_MILLIS: u64 = 30_000;

/// What the daemon last measured about this instance.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct HostMetrics {
	pub sampled_at_millis: u64,
	/// Whole machine, 0-100.
	pub system_cpu_percent: f64,
	/// This instance's share of the whole machine, 0-100.
	pub process_cpu_percent: f64,
	pub ram_used_bytes: u64,
	/// The instance's configured size: a native server has no other ceiling.
	pub ram_max_bytes: u64,
}

impl HostMetrics {
	/// Whether this sample is recent enough to report.
	///
	/// A clock that went backwards counts as stale rather than as
	/// impossibly-fresh, which is what a wrapping subtraction would give.
	#[must_use]
	pub fn is_fresh(&self, now_millis: u64) -> bool {
		if self.sampled_at_millis == 0 || now_millis < self.sampled_at_millis {
			return false;
		}

		now_millis - self.sampled_at_millis <= FRESH_FOR_MILLIS
	}

	/// What is left of the configured size, which the heartbeat also carries.
	#[must_use]
	pub fn ram_free_bytes(&self) -> u64 {
		self.ram_max_bytes.saturating_sub(self.ram_used_bytes)
	}
}

/// Read a sample the daemon left behind.
///
/// A file with none of the fields in it decodes to a sample dated zero, which
/// is never fresh, so a truncated or half-written file reads as "nothing known"
/// rather than as a machine at 0%.
#[must_use]
pub fn decode_host_metrics(body: &str) -> HostMetrics {
	let fields = decode_form(body);
	let number = |key: &str| -> f64 {
		fields
			.get(key)
			.and_then(|value| value.trim().parse::<f64>().ok())
			.filter(|value| value.is_finite() && *value >= 0.0)
			.unwrap_or(0.0)
	};

	HostMetrics {
		sampled_at_millis: number("sampledAtEpochMillis") as u64,
		system_cpu_percent: number("systemCpuUsagePercent"),
		process_cpu_percent: number("processCpuUsagePercent"),
		ram_used_bytes: number("ramUsedBytes") as u64,
		ram_max_bytes: number("ramMaxBytes") as u64,
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	/// A body in the shape the daemon writes it.
	const SAMPLE: &str = "sampledAtEpochMillis=1786190000000&systemCpuUsagePercent=7.4\
		&processCpuUsagePercent=2.1&ramUsedBytes=402653184&ramMaxBytes=1073741824";

	#[test]
	fn a_written_sample_reads_back() {
		let metrics = decode_host_metrics(SAMPLE);

		assert_eq!(metrics.sampled_at_millis, 1_786_190_000_000);
		assert!((metrics.system_cpu_percent - 7.4).abs() < f64::EPSILON);
		assert!((metrics.process_cpu_percent - 2.1).abs() < f64::EPSILON);
		assert_eq!(metrics.ram_used_bytes, 402_653_184);
		assert_eq!(metrics.ram_max_bytes, 1_073_741_824);
	}

	#[test]
	fn free_memory_is_what_the_ceiling_leaves() {
		let metrics = decode_host_metrics(SAMPLE);

		assert_eq!(metrics.ram_free_bytes(), 1_073_741_824 - 402_653_184);
	}

	/// A used size above the configured one is not impossible - the ceiling is
	/// luna's declaration, not a limit the kernel enforces on a native server.
	#[test]
	fn free_memory_never_goes_negative() {
		let metrics = HostMetrics {
			ram_used_bytes: 200,
			ram_max_bytes: 100,
			..HostMetrics::default()
		};

		assert_eq!(metrics.ram_free_bytes(), 0);
	}

	#[test]
	fn a_recent_sample_is_fresh() {
		let metrics = decode_host_metrics(SAMPLE);

		assert!(metrics.is_fresh(1_786_190_000_000));
		assert!(metrics.is_fresh(1_786_190_000_000 + FRESH_FOR_MILLIS));
	}

	/// The case this exists for: the daemon stopped, and the last figures it
	/// wrote must not go on being reported as current.
	#[test]
	fn a_sample_from_a_dead_daemon_goes_stale() {
		let metrics = decode_host_metrics(SAMPLE);

		assert!(!metrics.is_fresh(1_786_190_000_000 + FRESH_FOR_MILLIS + 1));
	}

	#[test]
	fn a_clock_that_went_backwards_is_stale_rather_than_fresh() {
		let metrics = decode_host_metrics(SAMPLE);

		assert!(!metrics.is_fresh(1_786_180_000_000));
	}

	#[test]
	fn an_empty_or_broken_file_is_never_fresh() {
		for body in ["", "garbage", "sampledAtEpochMillis=", "ramUsedBytes=12"] {
			assert!(!decode_host_metrics(body).is_fresh(1_786_190_000_000), "{body}");
		}
	}

	#[test]
	fn a_negative_or_infinite_reading_is_dropped_rather_than_carried() {
		let metrics = decode_host_metrics(
			"sampledAtEpochMillis=1&systemCpuUsagePercent=-5&processCpuUsagePercent=inf",
		);

		assert!((metrics.system_cpu_percent - 0.0).abs() < f64::EPSILON);
		assert!((metrics.process_cpu_percent - 0.0).abs() < f64::EPSILON);
	}
}
