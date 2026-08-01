#!/usr/bin/env python3
"""Load src/main/resources/merged-pois.json into Redis as key=poi:<id>, value=JSON blob.

Speaks the Redis protocol (RESP) directly over a socket, so no redis-cli
or pip dependency is required. Usage:

    python3 scripts/load_pois_into_redis.py [host] [port] [path-to-json]
"""
import json
import socket
import sys

KEY_PREFIX = "poi:"
DEFAULT_HOST = "localhost"
DEFAULT_PORT = 6379
DEFAULT_PATH = "src/main/resources/merged-pois.json"


def encode_command(*args):
    parts = [f"*{len(args)}\r\n".encode()]
    for arg in args:
        encoded = arg.encode("utf-8")
        parts.append(f"${len(encoded)}\r\n".encode())
        parts.append(encoded)
        parts.append(b"\r\n")
    return b"".join(parts)


def read_reply(sock):
    line = b""
    while not line.endswith(b"\r\n"):
        line += sock.recv(1)
    if not line.startswith(b"+"):
        raise RuntimeError(f"Unexpected Redis reply: {line!r}")


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_PORT
    path = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_PATH

    with open(path) as f:
        pois = json.load(f)

    with socket.create_connection((host, port)) as sock:
        for i, poi in enumerate(pois, 1):
            key = KEY_PREFIX + poi["id"]
            value = json.dumps(poi)
            sock.sendall(encode_command("SET", key, value))
            read_reply(sock)
            if i % 5000 == 0 or i == len(pois):
                print(f"Loaded {i}/{len(pois)}")

    print(f"Done. Loaded {len(pois)} records into Redis at {host}:{port}")


if __name__ == "__main__":
    main()
