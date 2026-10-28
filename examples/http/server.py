#!/usr/bin/env python3
"""The local server the HTTP self-test talks to (#90). Loopback only, never anything else.

    python3 server.py <port-file>

Binds 127.0.0.1 on a free port, writes the port to <port-file>, and serves until killed.
The iOS simulator shares the Mac's loopback, so the app reaches it at 127.0.0.1 too.

  GET  /hello          200, "hello, thicket"
  POST /echo           200, the request body back; X-Method and the X-Req headers it received
  GET  /redirect       302 to /hello
  GET  /missing        404, "no such thing"
  GET  /cookies        200, two Set-Cookie (one with a comma in its Expires) and two X-Multi
  GET  /slow?ms=N      200 after N ms; notices a client that hangs up meanwhile
  GET  /reset          no response: the connection is reset (RST), as by a server that crashed
  GET  /stats          what /slow saw: started, completed, aborted (the client hung up)
  POST /graphql        {"data":{"greeting":"hello, <name>"}} for `greeting(name:"<name>")`
"""
import json
import re
import select
import socket
import socketserver
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

stats = {"started": 0, "completed": 0, "aborted": 0, "graphql": []}
stats_lock = threading.Lock()


def bump(key):
    with stats_lock:
        stats[key] += 1


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("server: " + (fmt % args) + "\n")

    def reply(self, code, body, headers=()):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/hello":
            self.reply(200, "hello, thicket", [("Content-Type", "text/plain; charset=utf-8")])
        elif url.path == "/redirect":
            self.reply(302, "", [("Location", "/hello")])
        elif url.path == "/missing":
            self.reply(404, "no such thing", [("Content-Type", "text/plain")])
        elif url.path == "/cookies":
            self.reply(200, "cookies", [
                ("Set-Cookie", "a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/"),
                ("Set-Cookie", "b=2; Path=/"),
                ("X-Multi", "one"),
                ("X-Multi", "two"),
            ])
        elif url.path == "/slow":
            self.slow(int(parse_qs(url.query).get("ms", ["3000"])[0]))
        elif url.path == "/reset":
            # SO_LINGER with a zero timeout: close() sends RST, so the client reads "connection reset".
            self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            self.close_connection = True
            self.connection.close()
        elif url.path == "/stats":
            with stats_lock:
                self.reply(200, json.dumps(stats), [("Content-Type", "application/json")])
        else:
            self.reply(404, "unknown path")

    def slow(self, ms):
        """Waits, watching the socket: a client that cancels closes it, which reads as EOF."""
        bump("started")
        deadline = time.monotonic() + ms / 1000
        sock = self.connection
        while time.monotonic() < deadline:
            readable, _, _ = select.select([sock], [], [], 0.02)
            if readable:
                try:
                    if sock.recv(1, socket.MSG_PEEK) == b"":
                        bump("aborted")
                        self.close_connection = True
                        return
                except OSError:
                    bump("aborted")
                    self.close_connection = True
                    return
        try:
            self.reply(200, "slow, after %d ms" % ms)
            bump("completed")
        except OSError:
            bump("aborted")

    def do_POST(self):
        url = urlparse(self.path)
        data = self.body()
        if url.path == "/echo":
            req = self.headers.get_all("X-Req") or []
            self.reply(200, data, [
                ("Content-Type", self.headers.get("Content-Type") or "application/octet-stream"),
                ("X-Method", self.command),
                ("X-Req-Count", str(len(req))),
                ("X-Req-Values", "|".join(req)),
            ])
        elif url.path == "/graphql":
            query = json.loads(data or b"{}").get("query", "")
            with stats_lock:
                stats["graphql"].append(query)
            m = re.search(r'greeting\s*\(\s*name\s*:\s*"([^"]*)"\s*\)', query)
            if m:
                out = {"data": {"greeting": "hello, " + m.group(1)}}
            else:
                out = {"errors": [{"message": "unsupported query: " + query}]}
            self.reply(200, json.dumps(out), [("Content-Type", "application/json")])
        else:
            self.reply(404, "unknown path")

    do_PUT = do_POST


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self):
        # HTTPServer.server_bind asks socket.getfqdn() for a name, a reverse DNS lookup that
        # took over 5 s on the Mac. Loopback needs no name.
        socketserver.TCPServer.server_bind(self)
        self.server_name = "127.0.0.1"
        self.server_port = self.server_address[1]


def main():
    server = Server(("127.0.0.1", 0), Handler)

    with open(sys.argv[1], "w") as f:
        f.write(str(server.server_address[1]))
    server.serve_forever()


if __name__ == "__main__":
    main()
