# Forwarding for the forge 1.12.2 line

How a 1.12.2 backend joins a modern-forwarding Velocity network, and why it takes
anything at all. Milestone 1 shipped without this on purpose: the heartbeat and
the permission mirror are plain HTTP with the `X-Luna-Forwarding-Secret` header,
so a 1.12.2 backend joins luna's **management** plane with no forwarding
whatsoever. This is about the remaining gap, proxied player logins.

## Why it is not free

Velocity's modern forwarding rides on the **login plugin message** exchange:
during login the backend asks on the `velocity:player_info` channel (clientbound
login `0x04`), the proxy answers with the player's identity signed HMAC-SHA256
(serverbound `0x02`), and the backend verifies before letting the login proceed.
Both packets were added in **1.13**; the 1.12.2 protocol (340) has no such
packets.

That single fact produces two gates in stock Velocity:

1. **The client gate.** Under `player-info-forwarding-mode = "modern"`, Velocity
   disconnects every pre-1.13 client at handshake. Verified against our own
   4.1.0-SNAPSHOT with a raw protocol-340 login:
   `{"text":"This server is only compatible with Minecraft 1.13 and above."}`
2. **The packet registry.** `StateRegistry` maps both login-plugin packets from
   `MINECRAFT_1_13` only, so at protocol 340 the proxy can neither decode the
   backend's query nor encode its answer.

## What luna does

**The backend half is ours.** `forge/luna-core-mc12/.../forwarding` implements
the exchange at the netty level - it sits between `splitter` and `decoder`,
withholds the login start, sends the query, verifies the HMAC and hands vanilla a
finished profile. No PCF, no MixinBooter. The signature check and payload parse
live in `luna-legacy-api` and are tested against a golden vector produced by
Velocity's own encoder.

**The proxy half is a java agent**, `velocity/luna-forwarding-agent`. It runs the
stock PaperMC jar and rewrites two `ProtocolVersion` constants as the classes
load, both `MINECRAFT_1_13` → `MINECRAFT_1_7_2`:

- in `StateRegistry`, on the two login-plugin packet registrations, so the
  exchange decodes on the proxy-to-backend leg;
- in the handshake gate, so the test becomes `protocol < 1.7.2` and never fires.

Removing the client gate loses nothing. The same guarantee is enforced per
connection where it matters: `LoginSessionHandler` refuses any backend that
reaches login success without having requested forwarding data
(`MODERN_IP_FORWARDING_FAILURE`). A backend that cannot speak the exchange still
fails, with a more accurate message.

### Why an agent rather than a source patch

Both are "modify Velocity". The patch was written first and worked, but it means
building and shipping our own proxy binary, rebuilt on every upstream release,
sitting at the cluster's single point of failure. The agent runs the stock jar.

### Why not Mixin

Velocity has no Mixin host. Mixin needs a `MixinService` plus a transformer
bootstrapped before the targets load, which in practice means shipping a java
agent anyway - so Mixin would be an extra service layer, refmap and annotation
processor on top of the thing that does the work. Mixin exists to let many
independent mods co-patch one game with conflict resolution and deobfuscation;
here there is one patcher, two call sites and no obfuscation.

### Two traps the agent had to avoid

- **`HandshakeSessionHandler` references `MINECRAFT_1_13` twice.** The second is
  how Velocity recognises a legacy Forge client. A class-wide rewrite would
  silently break Forge 1.8-1.12 detection while appearing to work.
- **The gate is not in `handle`.** It is in a private `handleLogin`, and the
  legacy-Forge check is in a *different* method taking the same argument type, so
  matching on a name or signature can select the wrong one. The agent therefore
  identifies the gate by the `PlayerInfoForwarding.MODERN` comparison it is
  paired with - the thing that actually defines it - and asserts it found exactly
  one.

### Failure behaviour

Every transform asserts the shape it expected. If upstream moves the bytecode,
the agent reports it and the class loads unpatched, which leaves the stock gate
in place: **pre-1.13 clients are refused exactly as before and no other backend
is affected**. It deliberately does not halt the proxy - taking the whole cluster
down over one legacy server is worse than the thing it would be protecting
against. On success it sets the `luna.velocity.legacyForwarding` system property,
so the state is positively observable rather than inferred from a failed login.

## Installing it

The jar is **an agent and a velocity plugin at once**, so luna manages it like
any other addon: `luna luna sync` pools it as `luna-forwarding-agent@velocity`,
`luna plugins deploy` puts it in the proxy's `plugins/`, and updates arrive the
same way they do for everything else. Give it a target once, then attach it:

```
luna plugins apply luna-forwarding-agent@velocity --to proxy
luna instance config proxy javaAgents "addon:luna-forwarding-agent@velocity"
```

Or, in the console, on the proxy's **Configuration** tab under *Java agents*,
where it appears in the picker alongside the proxy's other addons.

Naming the addon rather than a path is what keeps the two facts in one place:
luna resolves it to wherever deploy put the jar, and refuses to start the proxy
if it is not there or is not an agent at all. It renders as
`-javaagent:plugins/luna-forwarding-agent@velocity.jar`.

### What the plugin half does

Nothing to the protocol; by the time a plugin loads, every class the agent
rewrites is either already defined or not yet touched. It exists to be
**manageable** - a lockfile entry can be pooled, updated and deployed, and a
loose jar cannot - and to answer the one question the agent cannot answer about
itself: whether the `-javaagent:` flag was actually set. Without the flag the
proxy boots perfectly and then refuses every pre-1.13 client, which reads as a
broken backend. With the plugin, it says so on startup and prints the command
that fixes it.

It checks `luna.velocity.legacyForwarding.attached`, which `premain` sets before
the proxy's own main method, **not** the ready flag: both patch targets load
lazily (`StateRegistry` is first touched by a Netty worker when a connection
arrives), so testing readiness during startup would warn on every boot of a
working proxy.

## Verified

Stock Velocity 4.1.0-SNAPSHOT plus the agent, protocol-340 login through to a
1.12.2 backend running luna-core:

```
[luna-agent] Đã vá StateRegistry$5: 2 packet mapping(s).
[luna-agent] Đã vá HandshakeSessionHandler: cổng chặn client < 1.13 đã được gỡ.
[luna-agent] Modern forwarding đã sẵn sàng cho client < 1.13.
[velocity]   Belikhun -> mc12test has connected
[lunacore]   Đã xác thực Belikhun (91acb76d-4c4b-4899-9e19-d9d2fd4b0711) từ 127.0.0.1
[minecraft]  Belikhun joined the game
```

`91acb76d-…` is the identity the rest of the cluster knows. Without forwarding
the backend would have invented `198eec0c-16b7-3017-ae36-1da1fdf28261` from the
name, and the player would have been a stranger to every other server. A real
1.12.2 client has also joined through this path.

## Sources

- PCF (`adde0109/Proxy-Compatible-Forge`), whose compatibility table documents
  that 1.7.2-1.12.2 modern forwarding needs "a modified Velocity proxy" without
  saying what the modification is. This is what it is.
- Velocity `StateRegistry`, `HandshakeSessionHandler`, `LoginSessionHandler` and
  `PlayerDataForwarding` at commit `00759e52`, the build luna runs.
