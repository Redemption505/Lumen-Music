"""Process-local network scenarios for installed-service acceptance only."""
from __future__ import annotations

import runpy
import sys
import urllib.request


scenario = sys.argv.pop(1)
if scenario == "DirectOnly":
    urllib.request.getproxies = lambda: {}
elif scenario == "UnavailableProxy":
    urllib.request.getproxies = lambda: {
        "http": "http://127.0.0.1:1", "https": "http://127.0.0.1:1",
    }
else:
    raise ValueError(f"Unknown smoke network scenario: {scenario}")

print(f"Installed service network scenario: {scenario}", flush=True)
sys.argv[0] = "lumen_service"
runpy.run_module("lumen_service", run_name="__main__")
