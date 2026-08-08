//! One AMQP 0-9-1 connection: handshake, topology, publish and delivery.
//!
//! Only what luna's plugin messaging actually uses is here. That is the whole
//! point of writing it rather than pulling a client in: the cluster needs one
//! exchange, one queue, one consumer and a publish, and that surface is small
//! enough to drive synchronously from a game tick.
//!
//! The topology matches what the JVM backends declare, because either side may
//! start first and an identical declare is a no-op.

use super::codec::FrameIo;
use amq_protocol::frame::{AMQPFrame, ProtocolVersion};
use amq_protocol::protocol::basic::AMQPProperties;
use amq_protocol::protocol::{AMQPClass, basic, channel, connection, exchange, queue};
use amq_protocol::types::{AMQPValue, FieldTable, LongString, ShortString};
use amq_protocol::uri::{AMQPScheme, AMQPUri};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

/// The one channel every operation runs on; luna needs no more.
const CHANNEL: u16 = 1;

/// Frame size to ask for when the broker leaves the choice open.
const DEFAULT_FRAME_MAX: u32 = 131_072;

/// Heartbeat to negotiate, capped so an idle backend is not dropped.
///
/// The broker closes a connection it has heard nothing on, and an idle backend
/// publishes nothing for hours; a heartbeat frame from the pump is what keeps it.
const WANTED_HEARTBEAT: u16 = 60;

/// How long the TCP connect itself may take.
const CONNECT_TIMEOUT: Duration = Duration::from_millis(2000);

/// A connected, opened, declared session ready to carry messages.
pub struct Session {
	io: FrameIo,
	/// Largest frame the broker accepts, so a body is split to fit.
	frame_max: u32,
	/// Negotiated heartbeat interval; `None` when the broker disabled it.
	heartbeat: Option<Duration>,
	last_write: Instant,
	/// A delivery's body accumulates here until it reaches its declared size.
	partial: Option<Partial>,
}

/// A delivery being reassembled across header and body frames.
struct Partial {
	expected: u64,
	body: Vec<u8>,
}

impl Session {
	/// Connect, authenticate and declare everything this backend needs.
	///
	/// Blocking on purpose: a handshake is a fixed request/response sequence, and
	/// on a healthy broker it finishes in about a millisecond. Every read is
	/// bounded by the codec's timeout, so an unresponsive broker fails rather
	/// than holding the tick.
	pub fn open(
		uri_text: &str,
		exchange_name: &str,
		queue_name: &str,
		consumer_tag: &str,
	) -> Result<Self, String> {
		let uri: AMQPUri = uri_text
			.parse()
			.map_err(|error| format!("URI không hợp lệ: {error}"))?;

		if uri.scheme != AMQPScheme::AMQP {
			// TLS would mean a whole certificate story inside the sandbox, and the
			// broker is reached over the cluster's own network
			return Err("chỉ hỗ trợ amqp:// (chưa hỗ trợ amqps://)".to_owned());
		}

		let mut session = Self::connect(&uri)?;

		session.handshake(&uri)?;
		session.declare(exchange_name, queue_name)?;
		session.consume(queue_name, consumer_tag)?;

		// everything above is request/response; from here the tick pump takes
		// whatever has arrived and never waits
		session.io.set_polling()?;

		Ok(session)
	}

	fn connect(uri: &AMQPUri) -> Result<Self, String> {
		let target = (uri.authority.host.as_str(), uri.authority.port);
		let address = target
			.to_socket_addrs()
			.map_err(|error| format!("không phân giải được host: {error}"))?
			.next()
			.ok_or_else(|| "host không phân giải ra địa chỉ nào".to_owned())?;

		let socket = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT)
			.map_err(|error| format!("không kết nối được: {error}"))?;

		Ok(Self {
			io: FrameIo::new(socket)?,
			frame_max: DEFAULT_FRAME_MAX,
			heartbeat: None,
			last_write: Instant::now(),
			partial: None,
		})
	}

	/// Protocol header, SASL PLAIN, tuning, and opening the vhost and channel.
	fn handshake(&mut self, uri: &AMQPUri) -> Result<(), String> {
		self.send(AMQPFrame::ProtocolHeader(ProtocolVersion::amqp_0_9_1()))?;

		match self.expect_method()? {
			AMQPClass::Connection(connection::AMQPMethod::Start(_)) => {}
			other => return Err(format!("chờ connection.start, nhận {other:?}")),
		}

		// PLAIN is "\0user\0password"; the broker offered mechanisms in Start, but
		// every broker luna targets offers PLAIN and nothing here can do SASL
		let credentials = format!(
			"\0{}\0{}",
			uri.authority.userinfo.username, uri.authority.userinfo.password
		);

		self.send_method(
			0,
			AMQPClass::Connection(connection::AMQPMethod::StartOk(connection::StartOk {
				client_properties: client_properties(),
				mechanism: ShortString::from("PLAIN"),
				response: LongString::from(credentials.into_bytes()),
				locale: ShortString::from("en_US"),
			})),
		)?;

		let tune = match self.expect_method()? {
			AMQPClass::Connection(connection::AMQPMethod::Tune(tune)) => tune,
			other => return Err(format!("chờ connection.tune, nhận {other:?}")),
		};

		// zero from the broker means "no limit", in which case our own value stands
		self.frame_max = if tune.frame_max == 0 {
			DEFAULT_FRAME_MAX
		} else {
			tune.frame_max.min(DEFAULT_FRAME_MAX)
		};

		let heartbeat = if tune.heartbeat == 0 {
			0
		} else {
			tune.heartbeat.min(WANTED_HEARTBEAT)
		};

		self.heartbeat = (heartbeat > 0).then(|| Duration::from_secs(u64::from(heartbeat)));

		self.send_method(
			0,
			AMQPClass::Connection(connection::AMQPMethod::TuneOk(connection::TuneOk {
				channel_max: tune.channel_max,
				frame_max: self.frame_max,
				heartbeat,
			})),
		)?;

		self.send_method(
			0,
			AMQPClass::Connection(connection::AMQPMethod::Open(connection::Open {
				virtual_host: ShortString::from(uri.vhost.as_str()),
			})),
		)?;

		match self.expect_method()? {
			AMQPClass::Connection(connection::AMQPMethod::OpenOk(_)) => {}
			other => return Err(format!("chờ connection.open-ok, nhận {other:?}")),
		}

		self.send_method(
			CHANNEL,
			AMQPClass::Channel(channel::AMQPMethod::Open(channel::Open {})),
		)?;

		match self.expect_method()? {
			AMQPClass::Channel(channel::AMQPMethod::OpenOk(_)) => Ok(()),
			other => Err(format!("chờ channel.open-ok, nhận {other:?}")),
		}
	}

	/// Declare the exchange, this backend's queue, and the binding between them.
	fn declare(&mut self, exchange_name: &str, queue_name: &str) -> Result<(), String> {
		self.send_method(
			CHANNEL,
			AMQPClass::Exchange(exchange::AMQPMethod::Declare(exchange::Declare {
				exchange: ShortString::from(exchange_name),
				kind: ShortString::from("direct"),
				passive: false,
				durable: true,
				auto_delete: false,
				internal: false,
				nowait: false,
				arguments: FieldTable::default(),
			})),
		)?;

		match self.expect_method()? {
			AMQPClass::Exchange(exchange::AMQPMethod::DeclareOk(_)) => {}
			other => return Err(format!("chờ exchange.declare-ok, nhận {other:?}")),
		}

		self.send_method(
			CHANNEL,
			AMQPClass::Queue(queue::AMQPMethod::Declare(queue::Declare {
				queue: ShortString::from(queue_name),
				passive: false,
				durable: true,
				exclusive: false,
				auto_delete: false,
				nowait: false,
				arguments: FieldTable::default(),
			})),
		)?;

		match self.expect_method()? {
			AMQPClass::Queue(queue::AMQPMethod::DeclareOk(_)) => {}
			other => return Err(format!("chờ queue.declare-ok, nhận {other:?}")),
		}

		// the routing key is the queue's own name, which is what makes the direct
		// exchange address exactly one backend
		self.send_method(
			CHANNEL,
			AMQPClass::Queue(queue::AMQPMethod::Bind(queue::Bind {
				queue: ShortString::from(queue_name),
				exchange: ShortString::from(exchange_name),
				routing_key: ShortString::from(queue_name),
				nowait: false,
				arguments: FieldTable::default(),
			})),
		)?;

		match self.expect_method()? {
			AMQPClass::Queue(queue::AMQPMethod::BindOk(_)) => Ok(()),
			other => Err(format!("chờ queue.bind-ok, nhận {other:?}")),
		}
	}

	/// Start consuming, without acknowledgements.
	///
	/// `no_ack` matches the JVM transports: a plugin message is only useful the
	/// moment it arrives, so redelivering one after a reconnect would replay a
	/// stale event rather than recover anything.
	fn consume(&mut self, queue_name: &str, consumer_tag: &str) -> Result<(), String> {
		self.send_method(
			CHANNEL,
			AMQPClass::Basic(basic::AMQPMethod::Consume(basic::Consume {
				queue: ShortString::from(queue_name),
				consumer_tag: ShortString::from(consumer_tag),
				no_local: false,
				no_ack: true,
				exclusive: false,
				nowait: false,
				arguments: FieldTable::default(),
			})),
		)?;

		match self.expect_method()? {
			AMQPClass::Basic(basic::AMQPMethod::ConsumeOk(_)) => Ok(()),
			other => Err(format!("chờ basic.consume-ok, nhận {other:?}")),
		}
	}

	/// Send one body to a queue, through the default exchange.
	pub fn publish(&mut self, queue_name: &str, body: &[u8]) -> Result<(), String> {
		self.send_method(
			CHANNEL,
			AMQPClass::Basic(basic::AMQPMethod::Publish(basic::Publish {
				exchange: ShortString::from(""),
				routing_key: ShortString::from(queue_name),
				mandatory: false,
				immediate: false,
			})),
		)?;

		self.send(AMQPFrame::Header(
			CHANNEL,
			60,
			Box::new(amq_protocol::frame::AMQPContentHeader {
				class_id: 60,
				body_size: body.len() as u64,
				properties: AMQPProperties::default().with_delivery_mode(2),
			}),
		))?;

		// a body larger than the negotiated frame goes out in pieces; the 8 bytes
		// are the frame's own header and end marker
		let limit = (self.frame_max as usize).saturating_sub(8).max(1);

		for chunk in body.chunks(limit) {
			self.send(AMQPFrame::Body(CHANNEL, chunk.to_vec()))?;
		}

		Ok(())
	}

	/// Take everything the broker has sent since the last call.
	///
	/// Returns the bodies of completed deliveries. Anything else - the broker's
	/// own heartbeats, flow control, acknowledgements - is consumed here, because
	/// the caller only ever wanted the messages.
	pub fn poll(&mut self) -> Result<Vec<Vec<u8>>, String> {
		let frames = self.io.poll()?;
		let mut bodies = Vec::new();

		for frame in frames {
			match frame {
				AMQPFrame::Method(_, AMQPClass::Basic(basic::AMQPMethod::Deliver(_))) => {
					// the header and body frames that describe it follow
					self.partial = None;
				}
				AMQPFrame::Header(_, _, header) => {
					self.partial = Some(Partial {
						expected: header.body_size,
						body: Vec::with_capacity(header.body_size as usize),
					});

					// a delivery with no body is complete the moment it is announced
					if header.body_size == 0 {
						self.partial = None;
						bodies.push(Vec::new());
					}
				}
				AMQPFrame::Body(_, mut data) => {
					if let Some(partial) = self.partial.as_mut() {
						partial.body.append(&mut data);

						if partial.body.len() as u64 >= partial.expected {
							bodies.push(std::mem::take(&mut partial.body));
							self.partial = None;
						}
					}
				}
				AMQPFrame::Method(_, AMQPClass::Connection(connection::AMQPMethod::Close(close))) => {
					return Err(format!(
						"broker đóng kết nối: {} ({})",
						close.reply_text.as_str(),
						close.reply_code
					));
				}
				AMQPFrame::Method(_, AMQPClass::Channel(channel::AMQPMethod::Close(close))) => {
					return Err(format!(
						"broker đóng channel: {} ({})",
						close.reply_text.as_str(),
						close.reply_code
					));
				}
				_ => {}
			}
		}

		self.beat()?;

		Ok(bodies)
	}

	/// Send a heartbeat if the connection has been quiet long enough.
	fn beat(&mut self) -> Result<(), String> {
		let Some(interval) = self.heartbeat else {
			return Ok(());
		};

		// half the interval, so one lost frame does not trip the broker's timeout
		if self.last_write.elapsed() < interval / 2 {
			return Ok(());
		}

		self.send(AMQPFrame::Heartbeat(0))
	}

	/// Tell the broker we are going, so it does not wait out the heartbeat.
	pub fn close(&mut self) {
		let _ = self.send_method(
			0,
			AMQPClass::Connection(connection::AMQPMethod::Close(connection::Close {
				reply_code: 200,
				reply_text: ShortString::from("closing"),
				class_id: 0,
				method_id: 0,
			})),
		);
	}

	fn send_method(&mut self, channel: u16, method: AMQPClass) -> Result<(), String> {
		self.send(AMQPFrame::Method(channel, method))
	}

	fn send(&mut self, frame: AMQPFrame) -> Result<(), String> {
		self.io.write(&frame)?;
		self.last_write = Instant::now();

		Ok(())
	}

	/// Read frames until a method arrives, skipping the broker's heartbeats.
	fn expect_method(&mut self) -> Result<AMQPClass, String> {
		loop {
			match self.io.read()? {
				AMQPFrame::Method(_, method) => return Ok(method),
				AMQPFrame::Heartbeat(_) => continue,
				other => return Err(format!("chờ một method frame, nhận {other:?}")),
			}
		}
	}
}

/// What this client tells the broker about itself, as seen in its console.
fn client_properties() -> FieldTable {
	let mut table = FieldTable::default();

	table.insert(
		"product".into(),
		AMQPValue::LongString(LongString::from("luna-core")),
	);
	table.insert(
		"platform".into(),
		AMQPValue::LongString(LongString::from("Pumpkin (wasm32-wasip2)")),
	);
	table.insert(
		"version".into(),
		AMQPValue::LongString(LongString::from(env!("CARGO_PKG_VERSION"))),
	);

	table
}
