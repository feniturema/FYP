#!/usr/bin/env python3
"""Minimal MCP streamable-HTTP client (standard library only) for acceptance A5 (docs/phases/P4b.md §9).

    python3 scripts/p4b/mcp_probe.py --url http://127.0.0.1:58082/mcp --call get_stock '{"productId":1}'

Runs initialize -> notifications/initialized -> tools/list [-> tools/call] and prints one JSON object:
{"protocolVersion", "serverInfo", "tools": [sorted names], "call": {"name", "arguments", "isError", "text"}}.
Exits 1 on any HTTP or JSON-RPC error.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2025-06-18"


class Session:
    def __init__(self, url: str, timeout: float):
        self.url, self.timeout, self.session_id, self.next_id = url, timeout, None, 1

    def _post(self, payload: dict):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        req = urllib.request.Request(self.url, data=json.dumps(payload).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            self.session_id = r.headers.get("Mcp-Session-Id") or self.session_id
            return r.status, r.headers.get("Content-Type", ""), r.read().decode("utf-8")

    def notify(self, method: str, params: dict | None = None) -> None:
        payload = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        self._post(payload)

    def request(self, method: str, params: dict) -> dict:
        rid = self.next_id
        self.next_id += 1
        _, ctype, text = self._post({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        for msg in _messages(ctype, text):
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: JSON-RPC error {msg['error']}")
                return msg["result"]
        raise RuntimeError(f"{method}: no response with id {rid}")


def _messages(ctype: str, text: str) -> list:
    if "text/event-stream" not in ctype:
        return [json.loads(text)] if text.strip() else []
    out, data = [], []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if line == "":
            if data:
                out.append(json.loads("\n".join(data)))
            data = []
        elif line.startswith("data:"):
            data.append(line[5:].removeprefix(" "))
    if data:
        out.append(json.loads("\n".join(data)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", required=True, help="MCP endpoint, e.g. http://127.0.0.1:58082/mcp")
    ap.add_argument("--call", nargs=2, metavar=("TOOL", "ARGS_JSON"), help="also call one tool")
    ap.add_argument("--timeout", type=float, default=30)
    a = ap.parse_args()

    s = Session(a.url, a.timeout)
    try:
        init = s.request("initialize", {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                                        "clientInfo": {"name": "ftsm-mcp-probe", "version": "1.0.0"}})
        s.notify("notifications/initialized")
        tools = s.request("tools/list", {})["tools"]
        out = {"protocolVersion": init.get("protocolVersion"), "serverInfo": init.get("serverInfo"),
               "tools": sorted(t["name"] for t in tools)}
        if a.call:
            name, args = a.call[0], json.loads(a.call[1])
            res = s.request("tools/call", {"name": name, "arguments": args})
            text = "".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
            out["call"] = {"name": name, "arguments": args, "isError": bool(res.get("isError")), "text": text}
    except urllib.error.HTTPError as e:
        print(f"mcp_probe: HTTP {e.code} from {a.url}", file=sys.stderr)
        return 1
    except (urllib.error.URLError, OSError, RuntimeError, ValueError, KeyError) as e:
        print(f"mcp_probe: {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    print(json.dumps(out, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
