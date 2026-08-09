//! Platform-free pieces of luna's backend core, shared by every Pumpkin plugin.
//!
//! This mirrors `luna-core-api` on the JVM side and exists for the same reason:
//! the wire formats, the identity rule and the config shape are the cluster's,
//! not one platform's, and a second copy is how two backends start disagreeing.
//!
//! Nothing here touches the game or the network, so it builds and tests on the
//! host as well as on `wasm32-wasip2`.

pub mod auth;
pub mod channel;
pub mod countdown;
pub mod envelope;
pub mod heartbeat;
pub mod host_metrics;
pub mod identity;
pub mod messaging;
pub mod messenger;
pub mod palette;
pub mod pending;
pub mod permissions;
pub mod progress_bar;
pub mod registry;
pub mod selector;
pub mod selector_engine;
pub mod text;
pub mod throttle;
pub mod wire;
