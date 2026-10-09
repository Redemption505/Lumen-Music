from __future__ import annotations

import argparse

from .providers import BilibiliProvider, DemoProvider
from .server import create_server


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the Lumen Music local service")
    parser.add_argument("--port", type=int, default=8765, help="TCP port (default: 8765)")
    parser.add_argument(
        "--provider",
        choices=("bilibili", "demo"),
        default="bilibili",
        help="metadata provider (default: bilibili)",
    )
    args = parser.parse_args()

    provider = BilibiliProvider() if args.provider == "bilibili" else DemoProvider()
    server = create_server(port=args.port, provider=provider)
    print(f"Lumen service listening on http://127.0.0.1:{server.server_port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
