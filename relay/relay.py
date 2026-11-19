#!/usr/bin/env python3
"""AgentPilot own relay, version 0.6.22.

A dumb pipe between two WebSocket clients: the phone and the cloud.
Both sides dial OUT to this relay (NAT always allows outbound), so
neither side needs a public address, a port forward, a VPN app, or
any outside tunnel service. The relay only forwards frames between
the two clients that present the same room (the MCP token, shared
once through the app's Share flow). It holds no secrets and no state
beyond the live connections: no disk, no log of rooms or payloads,
no accounts, no third-party service.

Plain Python 3 standard library only, no pip packages. Run it on
any machine with a public address and Python 3: RELAY_HOST and
RELAY_PORT set the listen address (defaults 0.0.0.0:8766). For a
production relay set RELAY_CERT and RELAY_KEY (PEM files) and it
serves wss; without them it serves plain ws, for local testing
only, because over plain ws the room token in the URL travels in
cleartext. See README.md for hosts and setup.
"""
import base64
import hashlib
import os
import socket
import ssl
import struct
import threading
import time
from urllib.parse import parse_qs, urlparse

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_PAYLOAD = 1024 * 1024  # Same cap as the MCP server body limit.
MAX_HEADERS = 8192
HANDSHAKE_TIMEOUT = 10.0

# room -> the one client waiting for a partner. A room holds at most
# two clients: while a pair is live the entry still names the first
# one, so a third client is refused. The room id is only a dict key
# in memory, never logged and never written anywhere.
rooms = {}
rooms_lock = threading.Lock()


def log(msg):
    print(time.strftime("%H:%M:%S") + " " + msg, flush=True)


def read_exact(conn, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return bytes(buf)


def read_http(conn):
    """Read an HTTP request head. Returns (method, target, headers)."""
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        if len(buf) > MAX_HEADERS:
            raise ValueError("headers too large")
        chunk = conn.recv(4096)
        if not chunk:
            raise ConnectionError("closed before request")
        buf += chunk
    head, _ = bytes(buf).split(b"\r\n\r\n", 1)
    lines = head.decode("latin-1").split("\r\n")
    method, target, _ = lines[0].split(" ", 2)
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return method, target, headers


def send_frame(conn, opcode, payload=b"", fin=True):
    """One server frame: never masked, per RFC 6455."""
    out = bytearray()
    out.append((0x80 if fin else 0x00) | opcode)
    n = len(payload)
    if n < 126:
        out.append(n)
    elif n < 65536:
        out.append(126)
        out += struct.pack(">H", n)
    else:
        out.append(127)
        out += struct.pack(">Q", n)
    out += payload
    conn.sendall(bytes(out))


def read_frame(conn):
    """One client frame. Returns (fin, opcode, payload). Client
    frames must be masked; an unmasked frame is a protocol error."""
    b1, b2 = read_exact(conn, 2)
    fin = bool(b1 & 0x80)
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack(">H", read_exact(conn, 2))[0]
    elif n == 127:
        n = struct.unpack(">Q", read_exact(conn, 8))[0]
    if n > MAX_PAYLOAD:
        raise ValueError("frame too large")
    if not masked:
        raise ValueError("client frame not masked")
    mask = read_exact(conn, 4)
    payload = bytearray(read_exact(conn, n))
    for i in range(len(payload)):
        payload[i] ^= mask[i % 4]
    return fin, opcode, bytes(payload)


def http_reply(conn, code, reason, body=b""):
    head = ("HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\n"
            "Content-Length: %d\r\nConnection: close\r\n\r\n"
            % (code, reason, len(body)))
    conn.sendall(head.encode("latin-1") + body)


class Client:
    def __init__(self, conn):
        self.conn = conn
        self.peer = None
        self.room = None
        self.alive = True
        self.closed = False
        self.send_lock = threading.Lock()

    def send(self, opcode, payload=b"", fin=True):
        with self.send_lock:
            send_frame(self.conn, opcode, payload, fin)

    def close(self):
        # Idempotent: the peer's unpair and this client's own
        # finally can both reach close, and the second call must
        # be a no-op, not a second shutdown on a dead socket.
        if self.closed:
            return
        self.closed = True
        self.alive = False
        try:
            self.conn.shutdown(socket.SHUT_RDWR)
        except OSError as e:
            # The socket is already dead: worth one log line,
            # because a shutdown failure is never expected here.
            log("shutdown failed: %s" % e)
        try:
            self.conn.close()
        except OSError as e:
            log("close failed: %s" % e)


def unpair(client):
    """A client left. The pair is broken either way, so drop the
    room entry and close the peer: both sides reconnect and pair
    again, which keeps the state unambiguous (connected means
    paired, never half-open)."""
    peer = None
    with rooms_lock:
        if client.room is not None:
            rooms.pop(client.room, None)
        peer = client.peer
        client.peer = None
    if peer is not None:
        peer.peer = None
        try:
            peer.send(8, struct.pack(">H", 1000))
        except (OSError, ValueError) as e:
            # Best effort: the peer may already be gone, and the
            # pair is broken either way, so log it and move on.
            log("could not tell peer: %s" % e)
        peer.close()


def forward_loop(client):
    """Read frames from one client and hand them to its peer."""
    try:
        while client.alive:
            fin, opcode, payload = read_frame(client.conn)
            if opcode == 9:  # ping: answer here, do not forward.
                client.send(10, payload)
                continue
            if opcode == 10:  # pong: ignore.
                continue
            if opcode == 8:  # close: tell the peer, then end both.
                peer = client.peer
                if peer is not None:
                    try:
                        peer.send(8, payload)
                    except (OSError, ValueError) as e:
                        log("could not forward close: %s" % e)
                break
            peer = client.peer
            if peer is None or not peer.alive:
                # No partner yet: the sender should retry once the
                # other side is connected. The phone holds a
                # persistent connection, so this is the rare case
                # of a request sent before the phone arrived.
                log("dropped a message, no peer yet")
                continue
            try:
                peer.send(opcode, payload, fin)
            except (OSError, ValueError):
                break
    except (ConnectionError, ValueError, OSError) as e:
        # A deliberate close (the peer left and unpair shut this
        # socket down) is not an error worth logging: alive is
        # already False then. Only an unexpected drop is logged.
        if client.alive:
            log("client left: %s" % e)
    finally:
        unpair(client)
        client.close()
        log("connection closed")


def handle(conn, addr):
    try:
        conn.settimeout(HANDSHAKE_TIMEOUT)
        method, target, headers = read_http(conn)
        if method == "GET" and headers.get("upgrade", "").lower() != "websocket":
            # Plain health check: GET / answers 200, anything else 404.
            if urlparse(target).path in ("/", "/health"):
                http_reply(conn, 200, "OK", b"agentpilot-relay ok\n")
            else:
                http_reply(conn, 404, "Not Found")
            return
        if (method != "GET"
                or headers.get("upgrade", "").lower() != "websocket"
                or "upgrade" not in headers.get("connection", "").lower()
                or headers.get("sec-websocket-version", "") != "13"
                or "sec-websocket-key" not in headers):
            http_reply(conn, 400, "Bad Request")
            return
        room = parse_qs(urlparse(target).query).get("room", [None])[0]
        if not room:
            http_reply(conn, 400, "Bad Request", b"room required\n")
            return
        # Decide the pairing BEFORE the 101: a third client in a
        # full room gets a plain HTTP 409, never a WebSocket.
        client = Client(conn)
        client.room = room
        paired = False
        with rooms_lock:
            waiting = rooms.get(room)
            if waiting is None:
                rooms[room] = client
            elif waiting.peer is not None:
                http_reply(conn, 409, "Conflict", b"room full\n")
                return
            else:
                client.peer = waiting
                waiting.peer = client
                paired = True
        key = headers["sec-websocket-key"].strip()
        accept = base64.b64encode(
            hashlib.sha1((key + GUID).encode("latin-1")).digest()
        ).decode("latin-1")
        conn.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                      "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                      "Sec-WebSocket-Accept: %s\r\n\r\n" % accept
                      ).encode("latin-1"))
        conn.settimeout(None)
        log("pair ready" if paired else "client waiting for a partner")
        forward_loop(client)
    except (ConnectionError, ValueError, OSError) as e:
        log("handshake failed: %s" % e)
    finally:
        try:
            conn.close()
        except OSError as e:
            log("close failed: %s" % e)


def main():
    host = os.environ.get("RELAY_HOST", "0.0.0.0")
    port = int(os.environ.get("RELAY_PORT", "8766"))
    cert = os.environ.get("RELAY_CERT", "")
    key = os.environ.get("RELAY_KEY", "")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if cert and key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        sock = ctx.wrap_socket(sock, server_side=True)
        log("serving wss on %s:%d" % (host, port))
    else:
        log("serving ws on %s:%d (local testing only, use wss in production)"
            % (host, port))
    sock.bind((host, port))
    sock.listen(16)
    log("relay up, waiting for clients")
    try:
        while True:
            conn, addr = sock.accept()
            threading.Thread(target=handle, args=(conn, addr),
                             daemon=True).start()
    except KeyboardInterrupt:
        log("relay stopping")
    finally:
        sock.close()


if __name__ == "__main__":
    main()
