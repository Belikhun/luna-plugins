//! Reading and writing AMQP frames on a plain socket.
//!
//! `amq-protocol` owns the wire format itself; this is only the plumbing around
//! it, and it exists because the sandbox rules out every client that would have
//! provided it. There are no threads in a WASM component, so a client whose IO
//! loop owns one cannot connect at all - which is exactly what happens to lapin,
//! whose `IoLoop::start` spawns an OS thread and gets `ENOTSUP` here.
//!
//! The socket is therefore driven directly, in two modes. During the handshake
//! it blocks with a short read timeout, because a handshake is a strict
//! request/response sequence and a state machine would buy nothing. Once the
//! connection is up it goes non-blocking, and [`FrameIo::poll`] takes whatever
//! has arrived and returns, so a tick never waits on the broker.

use amq_protocol::frame::{AMQPFrame, WriteContext, gen_frame, parse_frame};
use std::io::{ErrorKind, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// How long a single handshake read may block a tick.
///
/// Generous for a broker on the same host or LAN, and short enough that one
/// that accepts the socket without speaking AMQP cannot stall the server.
const HANDSHAKE_READ_TIMEOUT: Duration = Duration::from_millis(250);

/// Starting size of the read buffer; it grows to whatever `frame_max` needs.
const READ_CHUNK: usize = 8192;

/// A socket carrying AMQP frames, plus whatever bytes arrived mid-frame.
pub struct FrameIo {
	socket: TcpStream,
	/// Bytes received but not yet forming a whole frame.
	pending: Vec<u8>,
}

impl FrameIo {
	/// Wrap a freshly connected socket, in blocking handshake mode.
	pub fn new(socket: TcpStream) -> Result<Self, String> {
		socket
			.set_read_timeout(Some(HANDSHAKE_READ_TIMEOUT))
			.map_err(|error| format!("không đặt được read timeout: {error}"))?;

		// every frame is written in one go and they are small, so Nagle would only
		// add latency to a publish
		let _ = socket.set_nodelay(true);

		Ok(Self {
			socket,
			pending: Vec::with_capacity(READ_CHUNK),
		})
	}

	/// Switch to the non-blocking mode the tick pump needs.
	pub fn set_polling(&self) -> Result<(), String> {
		self.socket
			.set_nonblocking(true)
			.map_err(|error| format!("không chuyển được sang non-blocking: {error}"))
	}

	/// Serialize one frame and put it on the wire.
	pub fn write(&mut self, frame: &AMQPFrame) -> Result<(), String> {
		let (buffer, _) = gen_frame(frame)(WriteContext::from(Vec::new()))
			.map_err(|error| format!("không mã hoá được frame: {error}"))?
			.into_inner();

		self.socket
			.write_all(&buffer)
			.map_err(|error| format!("không gửi được frame: {error}"))
	}

	/// Block until one frame arrives, for the handshake.
	pub fn read(&mut self) -> Result<AMQPFrame, String> {
		loop {
			if let Some(frame) = self.take_frame()? {
				return Ok(frame);
			}

			let mut chunk = [0u8; READ_CHUNK];

			match self.socket.read(&mut chunk) {
				Ok(0) => return Err("broker đã đóng kết nối".to_owned()),
				Ok(read) => self.pending.extend_from_slice(&chunk[..read]),
				Err(error) if error.kind() == ErrorKind::Interrupted => continue,
				Err(error) => return Err(format!("không đọc được frame: {error}")),
			}
		}
	}

	/// Take every frame that has already arrived, without waiting for more.
	///
	/// An empty result means the broker has sent nothing since the last tick,
	/// which is the normal case and not a condition worth reporting.
	pub fn poll(&mut self) -> Result<Vec<AMQPFrame>, String> {
		let mut chunk = [0u8; READ_CHUNK];

		loop {
			match self.socket.read(&mut chunk) {
				Ok(0) => return Err("broker đã đóng kết nối".to_owned()),
				Ok(read) => self.pending.extend_from_slice(&chunk[..read]),
				Err(error)
					if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) =>
				{
					break;
				}
				Err(error) if error.kind() == ErrorKind::Interrupted => continue,
				Err(error) => return Err(format!("không đọc được frame: {error}")),
			}
		}

		let mut frames = Vec::new();

		while let Some(frame) = self.take_frame()? {
			frames.push(frame);
		}

		Ok(frames)
	}

	/// Pull one frame out of the buffered bytes, if a whole one is there.
	fn take_frame(&mut self) -> Result<Option<AMQPFrame>, String> {
		if self.pending.is_empty() {
			return Ok(None);
		}

		match parse_frame(self.pending.as_slice()) {
			Ok((rest, frame)) => {
				// what the parser did not consume is the start of the next frame
				let consumed = self.pending.len() - rest.len();
				self.pending.drain(..consumed);

				Ok(Some(frame))
			}
			Err(error) if error.is_incomplete() => Ok(None),
			Err(error) => Err(format!("frame không hợp lệ: {error}")),
		}
	}
}
