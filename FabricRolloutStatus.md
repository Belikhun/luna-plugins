# Fabric Rollout Status

## Scope
- Modules: luna-core-fabric, luna-countdown-fabric, luna-messenger-fabric.
- Families: mc1165, mc1182, mc119x, mc1201, mc121x.
- Transport target: RabbitMQ primary, plugin messaging fallback.

## Implemented
- Fabric module family-variant build layout and family artifact naming.
- Official Mojang mappings enabled for all Fabric modules.
- Core transport orchestration with AMQP-first and fallback plugin messaging.
- AMQP outage/reconnect failover tests for core plugin messaging bus.
- Messenger Fabric gateway baseline:
	- command channel registration and command envelope dispatch
	- result/presence channel handling
	- placeholder-resolution pipeline for outbound command content
	- pending request tracking and timeout eviction helpers
	- command facade layer for nw/sv/msg/r/poke/chat style operations
	- runtime timeout monitor that actively evicts and logs expired pending requests
- Countdown Fabric runtime baseline:
	- countdown lifecycle service with start/update/complete/stop hooks
	- shared duration parsing and readable-time formatting utilities
	- command facade layer for start/stop/stopall flow
- Family binding extension points:
	- SPI interfaces for messenger/countdown family-specific command/event wiring
	- concrete binding classes for all families (mc1165, mc1182, mc119x, mc1201, mc121x)
	- runtime reflection warnings removed for missing legacy-family bindings
- Active command registration:
	- mc1201 and mc121x now register brigadier commands for countdown and messenger through Fabric command callback
	- commands are wired into runtime command-service facades
	- mc1165, mc1182, mc119x now also register active command bindings via family binders
	- command execution now sends source-aware success/failure feedback messages
- Placeholder export parity hardening:
	- Fabric backend resolver now supports optional platform placeholder bridge detection
	- nested placeholder resolution and runtime token discovery are aligned with Paper-side export behavior
	- exported values now include discovered platform tokens when bridge resolution is available
- Messenger event-side parity:
	- Fabric messenger now hooks server chat events and forwards chat payloads to proxy messenger gateway
	- when cancellable message events are available, local vanilla broadcast is suppressed after successful proxy dispatch
- Countdown event-side parity:
	- Fabric countdown now hooks player join/disconnect lifecycle events across families
	- active runtime audience state is synchronized from connection events for countdown flow parity with Paper player-event handling
- Countdown command metadata hardening:
	- title metadata is now removed automatically when countdown completes/stops
- Countdown shutdown parity:
	- Fabric now exposes `/shutdown <time> [reason]` and `/shutdown cancel`
	- shutdown flow reuses the countdown boss bar, broadcast, cancellation, and player-sync behavior
	- completion invokes the vanilla Fabric server halt path after the Paper-style completion delay
- Optional ecosystem diagnostics:
	- FabricProxy-Lite
		- startup detection now checks real forwarding secret sources via config/FabricProxy-Lite.toml, FABRIC_PROXY_SECRET, and FABRIC_PROXY_SECRET_FILE
		- compatibility log now reports forwarding-secret readiness and relevant hack flags for switch/chat troubleshooting
	- Text Placeholder API
	- LuckPerms
	- spark
	- SkinsRestorer
	- voicechat

## Verification Commands
- Build distributables: `./gradlew.bat shadowJar`
- Fabric parity checks: `./gradlew.bat verifyFabricParity`
- Module tests:
	- `./gradlew.bat :luna-core-fabric:test`
	- `./gradlew.bat :luna-messenger-fabric:test`
	- `./gradlew.bat :luna-countdown-fabric:test`

## Remaining Gaps
- Cross-proxy integration verification with real FabricProxy-Lite + Velocity should be run as environment tests.
