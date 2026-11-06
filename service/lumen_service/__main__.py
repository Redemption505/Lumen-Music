from __future__ import annotations

import argparse

from .providers import BilibiliProvider, DemoProvider
from .release_scope import FULL_PROFILE, LOCAL_FIRST_PROFILE, LocalOnlyProvider
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
    parser.add_argument(
        "--release-profile",
        choices=(FULL_PROFILE, LOCAL_FIRST_PROFILE),
        default=FULL_PROFILE,
        help="release feature scope (default: full)",
    )
    args = parser.parse_args()

    provider = (LocalOnlyProvider() if args.release_profile == LOCAL_FIRST_PROFILE else
                BilibiliProvider() if args.provider == "bilibili" else DemoProvider())
    server = create_server(port=args.port, provider=provider,
                           release_profile=args.release_profile)
    print(f"Lumen service listening on http://127.0.0.1:{server.server_port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
