"""Run the existing Java/Python demo contract with isolated state and owned cleanup."""

import argparse
import errno
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener
import uuid
from xml.etree import ElementTree


def main():
    # Native Maven output may contain text outside a Windows console's code page.
    sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use cached Maven dependencies")
    parser.add_argument("--artifact-directory", default="build/ci-demo")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    artifact_base = Path(args.artifact_directory)
    if not artifact_base.is_absolute():
        artifact_base = root / artifact_base
    run_root = artifact_base / uuid.uuid4().hex
    run_root.mkdir(parents=True)
    env = os.environ.copy()
    for name in ("LUMEN_AI_API_KEY", "OPENAI_API_KEY", "LUMEN_AI_CHAT_COMPLETIONS_URL",
                 "LUMEN_AI_MODEL", "LUMEN_AI_ENABLED"):
        env.pop(name, None)
    env.update({
        "LOCALAPPDATA": str(run_root), "APPDATA": str(run_root / "roaming"),
        "PYTHONPATH": str(root / "service"), "PYTHONPYCACHEPREFIX": str(run_root / "pycache"),
        "LUMEN_LIBRARY_PATH": str(run_root / "library.json"),
        "LUMEN_HISTORY_PATH": str(run_root / "history.json"),
        "LUMEN_PREFERENCES_PATH": str(run_root / "preferences.properties"),
        "LUMEN_AI_SETTINGS_PATH": str(run_root / "ai-settings.json"),
        "LUMEN_PROVIDER": "demo", "LUMEN_PYTHON": sys.executable,
    })
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    url = f"http://127.0.0.1:{port}"
    env.update({"LUMEN_SERVICE_URL": url, "LUMEN_STREAM_URL": url})
    service = None
    evidence = {"schema": 1, "platform": sys.platform, "python": sys.version.split()[0],
                "port": port, "health_verified": False, "maven_exit_code": None}
    try:
        with (run_root / "service.log").open("w", encoding="utf-8") as service_log:
            service = subprocess.Popen(
                [sys.executable, "-u", "-m", "lumen_service", "--provider", "demo", "--port", str(port)],
                cwd=root, env=env, stdout=service_log, stderr=subprocess.STDOUT)
            evidence["service_pid"] = service.pid
            opener = build_opener(ProxyHandler({}))
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if service.poll() is not None:
                    raise RuntimeError("Demo service exited before becoming ready; inspect service.log")
                try:
                    with opener.open(url + "/api/health", timeout=1) as response:
                        health = ElementTree.fromstring(response.read())
                    evidence["health"] = {"tag": health.tag, **health.attrib}
                    if health.tag == "health" and health.get("status") == "ok" and health.get("provider") == "demo":
                        evidence["health_verified"] = True
                        break
                except (URLError, TimeoutError, ElementTree.ParseError) as error:
                    evidence["last_health_error"] = str(error)
                time.sleep(0.1)
            if not evidence["health_verified"]:
                raise RuntimeError("Demo service readiness timed out; inspect service.log")
            wrapper = root / "desktop" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            command = [str(wrapper), "-B", "-ntp", "-f", str(root / "desktop/pom.xml")]
            if args.offline:
                command.append("-o")
            command.extend(["test-compile", "org.codehaus.mojo:exec-maven-plugin:3.6.2:exec",
                            "-Dexec.executable=java", "-Dexec.classpathScope=test",
                            "-Dexec.args=-cp %classpath com.lumenmusic.SmokeTest"])
            with (run_root / "maven.log").open("w", encoding="utf-8") as maven_log:
                result = subprocess.run(command, cwd=root, env=env, stdout=maven_log,
                                        stderr=subprocess.STDOUT, timeout=300, check=False)
            evidence["maven_exit_code"] = result.returncode
            print((run_root / "maven.log").read_text(encoding="utf-8", errors="replace"))
            if result.returncode:
                raise RuntimeError(f"Java/Python demo contract failed: Maven exit {result.returncode}")
    finally:
        if service is not None:
            if service.poll() is None:
                service.terminate()
                try:
                    service.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    service.kill()
                    service.wait(timeout=5)
            evidence["service_exit_code"] = service.returncode
            evidence["owned_service_exited"] = service.poll() is not None
        with socket.socket() as probe:
            probe.settimeout(3)
            connection_error = probe.connect_ex(("127.0.0.1", port))
            evidence["port_probe_error"] = connection_error
            evidence["port_released"] = (True if connection_error in (errno.ECONNREFUSED, 10061)
                                         else False if connection_error == 0 else None)
        (run_root / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"CI demo evidence: {run_root}")
    if not evidence["port_released"]:
        raise RuntimeError("Demo service port is still occupied after cleanup")
    print("PASS: isolated Java/Python demo integration and owned service cleanup")


if __name__ == "__main__":
    main()
