"""Bounded public-audio probe with process-local proxy settings and isolated data."""
from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import urllib.request
from urllib.error import URLError


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--service-root", type=Path, required=True)
    parser.add_argument("--scenario", choices=("default", "direct", "unavailable-proxy"), default="default")
    parser.add_argument("--probe-current-proxy", action="store_true")
    parser.add_argument("--force-direct-failure", action="store_true")
    args = parser.parse_args()
    sys.path.insert(0, str(args.service_root.resolve()))
    original_getproxies = urllib.request.getproxies
    if args.probe_current_proxy:
        started = time.monotonic()
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler(original_getproxies()))
            with opener.open(urllib.request.Request(
                    "https://api.bilibili.com/x/player/pagelist?bvid=BV1BZbSzZEGT",
                    headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.bilibili.com/"}), timeout=4) as response:
                payload = json.loads(response.read(100000))
                print(json.dumps({"system_proxy": response.status, "api_code": payload.get("code"),
                                  "seconds": round(time.monotonic() - started, 3)}), flush=True)
        except Exception as error:
            print(json.dumps({"system_proxy_error": type(error).__name__,
                              "seconds": round(time.monotonic() - started, 3)}), flush=True)
    if args.scenario == "direct":
        urllib.request.getproxies = lambda: {}
    elif args.scenario == "unavailable-proxy":
        urllib.request.getproxies = lambda: {"http": "http://127.0.0.1:1", "https": "http://127.0.0.1:1"}
    if args.force_direct_failure:
        original_build_opener = urllib.request.build_opener

        class UnavailableDirect:
            def open(self, request, timeout):
                raise URLError(ConnectionRefusedError("probe: direct route unavailable"))

        def probe_factory(*handlers):
            for handler in handlers:
                if isinstance(handler, urllib.request.ProxyHandler) and not handler.proxies:
                    return UnavailableDirect()
            return original_build_opener(*handlers)

        urllib.request.build_opener = probe_factory

    from lumen_service.history import HistoryStore
    from lumen_service.library import LibraryStore
    from lumen_service.providers import BilibiliProvider
    from lumen_service.server import create_server
    from lumen_service.streaming import BilibiliAudioProxy

    print(json.dumps({"service_root": str(args.service_root), "scenario": args.scenario,
                      "direct_failure_simulated": args.force_direct_failure}), flush=True)
    with tempfile.TemporaryDirectory(prefix="lumen-network-") as directory:
        data_root = Path(directory)
        os.environ["LUMEN_AI_SETTINGS_PATH"] = str(data_root / "ai-settings.json")
        provider = BilibiliProvider(timeout_seconds=4)
        server = create_server(
            0, provider=provider, audio_proxy=BilibiliAudioProxy(provider, timeout_seconds=6),
            library_store=LibraryStore(data_root / "library.json"),
            history_store=HistoryStore(data_root / "history.json"),
        )
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for method, headers in (("HEAD", {}), ("GET", {"Range": "bytes=100-199"})):
                started = time.monotonic()
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=20)
                try:
                    connection.request(method, "/api/tracks/bilibili%3ABV1BZbSzZEGT%3Ap1/stream", headers=headers)
                    response = connection.getresponse()
                    body = response.read(2000)
                    result = {"method": method, "status": response.status,
                              "content_length": response.getheader("Content-Length"),
                              "content_range": response.getheader("Content-Range"),
                              "error": response.getheader("X-Lumen-Error-Code"),
                              "bytes": len(body), "seconds": round(time.monotonic() - started, 3)}
                    print(json.dumps(result), flush=True)
                    if response.status != (200 if method == "HEAD" else 206):
                        return 1
                    if method == "GET" and len(body) != 100:
                        return 1
                finally:
                    connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
