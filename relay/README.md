# AgentPilot own relay

The relay is the middle of our own connector. The phone and the
cloud both dial OUT to it (NAT always allows outbound), so neither
side needs a public address, a port forward, a VPN app, or any
outside tunnel service. It only forwards WebSocket frames between
the two clients that present the same room. The room is the MCP
bearer token, shared once through the app's Share flow. The relay
holds no secrets and no state beyond the live connections: no disk,
no log of rooms or payloads, no accounts, no third-party service.

## Run it

Plain Python 3 standard library only, no pip packages. Any machine
with a public address and Python 3:

```
RELAY_PORT=8766 python3 relay.py
```

Environment: `RELAY_HOST` (default `0.0.0.0`), `RELAY_PORT`
(default `8766`), `RELAY_CERT` + `RELAY_KEY` (PEM files) to serve
`wss`. Without a certificate it serves plain `ws`, for local
testing only: over plain `ws` the room token in the URL travels in
cleartext, so a production relay MUST be `wss` with a real
certificate (Let's Encrypt is free).

Protocol: a client connects to `ws(s)://host:port/?room=<token>`.
The relay pairs the two clients in the same room and forwards
every text/binary frame between them. Messages are the same
JSON-RPC payloads the MCP server handles. A third client in a full
room gets HTTP 409. A message sent with no partner yet is dropped;
the sender retries. `GET /` answers `agentpilot-relay ok` for a
health check. Ping is answered by the relay itself.

## Host: where it runs

The relay is host-agnostic by design (plain Python, no proprietary
service), so it can move to any host. Checked 19 Nov 2026:

1. **Oracle Cloud Always Free, recommended.** The only big provider
   giving a real always-on VM for $0: Ampere A1 ARM, 2 OCPUs and
   12 GB RAM, no expiry, far more than this relay needs. Setup the
   owner does once: create an Oracle Cloud account, pick a home
   region, create an Ampere A1 VM (Ubuntu), open the relay port in
   the firewall, install Python 3 (already on Ubuntu), run
   `relay.py` as a service, and get a free Let's Encrypt
   certificate for `wss`. Caveats, read first: signup needs a
   credit or debit card for identity verification (a temporary
   hold only, no charge for Always Free; virtual, prepaid and
   PIN-debit cards are refused), and Oracle reclaims VMs idle for
   7 days (CPU, network and memory all under 20%) — a quiet relay
   can trip that, so this is the main risk to watch.
2. **Render free, fallback.** $0, 750 hours a month. But a free
   web service spins down after 15 minutes without traffic and
   takes about a minute to wake, which drops the relay connection;
   a keep-alive ping every 10 minutes is tolerated by Render but
   not guaranteed. Simpler signup than Oracle (the sources read
   did not mention a card for the free tier; confirm at signup),
   less reliable as an always-on relay.
3. **IPv6 direct, no host at all, unproven.** If the phone has a
   global IPv6 address, the cloud could dial it directly with
   nothing in the middle. The app's connector TEST screen lists
   the phone's IPv6 addresses so we can see whether this is even
   possible; the VM's egress is a proxy, so a direct TCP dial is
   likely blocked. Test before relying on it.
4. **Fly.io, ruled out.** No free tier for new accounts since
   October 2024; only a $1 / 7-day trial, then about $2.19 a month
   for the smallest always-on machine. Not $0.

No account was created for any of these, and no payment details
were entered anywhere. The app points at the relay through one
config value (`DriveService.RELAY_URL`), so the host can change
without touching the connector code.

## Security

- The relay never logs the room or any payload, and never writes
  anything to disk. The room id lives only as a dict key in memory
  for the duration of a pairing.
- The room IS the MCP token: only someone holding the token can
  join the room. The token is never sent in a message, only in the
  WebSocket URL, which is why production must be `wss`.
- Frames over 1 MB are refused, same cap as the MCP server, so a
  client cannot force a huge allocation.
- The relay does not parse the messages. It cannot see logins,
  screens or files; it only moves frames between two endpoints
  that already trust each other.
