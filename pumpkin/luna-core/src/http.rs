//! Just enough HTTP/1.1 to hold the heartbeat conversation.
//!
//! This speaks the protocol over `std::net::TcpStream` rather than going through
//! `wasi:http`, for two reasons. The AMQP transport needs raw TCP anyway, so
//! this way the plugin asks for **one** capability (`network.tcp.connect`)
//! instead of two, and the whole exchange stays a plain socket the same shim
//! drives. The bodies involved are small form-encoded posts, so none of what
//! `wasi:http` adds - chunked bodies, redirects, connection pooling - would be
//! used.
//!
//! Every request sends `Connection: close` and reads to EOF. That costs a socket
//! per beat, which at one beat every five seconds is not worth optimising, and
//! it removes any question about framing a keep-alive response.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// What came back.
#[derive(Debug, Clone)]
pub struct HttpResponse {
	pub status: u16,
	pub body: Vec<u8>,
}

impl HttpResponse {
	#[must_use]
	pub fn is_ok(&self) -> bool {
		self.status == 200
	}

	#[must_use]
	pub fn body_text(&self) -> String {
		String::from_utf8_lossy(&self.body).into_owned()
	}
}

/// A URL split into the pieces a request needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestTarget {
	pub host: String,
	pub port: u16,
	pub path: String,
}

/// Split an `http://host:port/path` URL.
///
/// Only `http` is handled: TLS would need a whole crypto stack inside the
/// sandbox, and the proxy is reached over the cluster's own network.
pub fn parse_url(url: &str) -> Result<RequestTarget, String> {
	let rest = url
		.strip_prefix("http://")
		.ok_or_else(|| format!("chỉ hỗ trợ http://, nhận được: {url}"))?;

	let (authority, path) = match rest.find('/') {
		Some(index) => (&rest[..index], &rest[index..]),
		None => (rest, "/"),
	};

	if authority.is_empty() {
		return Err(format!("URL thiếu host: {url}"));
	}

	let (host, port) = match authority.rsplit_once(':') {
		Some((host, port)) => (
			host,
			port.parse::<u16>()
				.map_err(|_| format!("cổng không hợp lệ trong URL: {url}"))?,
		),
		None => (authority, 80),
	};

	Ok(RequestTarget {
		host: host.to_owned(),
		port,
		path: path.to_owned(),
	})
}

/// Send one request and read the whole response.
pub fn request(
	method: &str,
	url: &str,
	headers: &[(&str, &str)],
	body: Option<&[u8]>,
	timeout: Duration,
) -> Result<HttpResponse, String> {
	let target = parse_url(url)?;

	let mut stream = TcpStream::connect((target.host.as_str(), target.port))
		.map_err(|error| format!("không kết nối được {}:{}: {error}", target.host, target.port))?;

	// a beat that hangs would stall the tick it runs on, so both directions are
	// bounded rather than left to the OS default
	stream
		.set_read_timeout(Some(timeout))
		.and_then(|()| stream.set_write_timeout(Some(timeout)))
		.map_err(|error| format!("không đặt được timeout: {error}"))?;

	let mut request = String::new();
	request.push_str(&format!("{method} {} HTTP/1.1\r\n", target.path));
	request.push_str(&format!("Host: {}:{}\r\n", target.host, target.port));
	request.push_str("Connection: close\r\n");

	for (name, value) in headers {
		request.push_str(&format!("{name}: {value}\r\n"));
	}

	request.push_str(&format!(
		"Content-Length: {}\r\n\r\n",
		body.map_or(0, <[u8]>::len)
	));

	stream
		.write_all(request.as_bytes())
		.map_err(|error| format!("không gửi được request: {error}"))?;

	if let Some(payload) = body {
		stream
			.write_all(payload)
			.map_err(|error| format!("không gửi được body: {error}"))?;
	}

	stream
		.flush()
		.map_err(|error| format!("không flush được request: {error}"))?;

	let mut raw = Vec::new();
	stream
		.read_to_end(&mut raw)
		.map_err(|error| format!("không đọc được response: {error}"))?;

	parse_response(&raw)
}

fn parse_response(raw: &[u8]) -> Result<HttpResponse, String> {
	let separator = find_header_end(raw).ok_or_else(|| "response thiếu phần header".to_owned())?;
	let head = String::from_utf8_lossy(&raw[..separator]);
	let mut lines = head.split("\r\n");

	let status_line = lines.next().ok_or_else(|| "response rỗng".to_owned())?;
	let status = status_line
		.split_whitespace()
		.nth(1)
		.and_then(|code| code.parse::<u16>().ok())
		.ok_or_else(|| format!("status line không hợp lệ: {status_line}"))?;

	Ok(HttpResponse {
		status,
		body: raw[separator + 4..].to_vec(),
	})
}

fn find_header_end(raw: &[u8]) -> Option<usize> {
	raw.windows(4).position(|window| window == b"\r\n\r\n")
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn splits_a_url_with_a_port() {
		assert_eq!(
			parse_url("http://127.0.0.1:32452/api/heartbeat/lobby"),
			Ok(RequestTarget {
				host: "127.0.0.1".into(),
				port: 32452,
				path: "/api/heartbeat/lobby".into(),
			})
		);
	}

	#[test]
	fn defaults_the_port_and_the_path() {
		assert_eq!(
			parse_url("http://proxy.internal"),
			Ok(RequestTarget {
				host: "proxy.internal".into(),
				port: 80,
				path: "/".into(),
			})
		);
	}

	#[test]
	fn refuses_https_rather_than_pretending() {
		assert!(parse_url("https://proxy.internal/x").is_err());
	}

	#[test]
	fn reads_a_status_and_body() {
		let raw = b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
		let response = parse_response(raw).expect("parses");

		assert!(response.is_ok());
		assert_eq!(response.body_text(), "hello");
	}

	#[test]
	fn reads_a_non_200_without_treating_it_as_success() {
		let raw = b"HTTP/1.1 401 Unauthorized\r\n\r\n";
		let response = parse_response(raw).expect("parses");

		assert_eq!(response.status, 401);
		assert!(!response.is_ok());
	}

	#[test]
	fn refuses_a_response_with_no_header_break() {
		assert!(parse_response(b"HTTP/1.1 200 OK").is_err());
	}
}
