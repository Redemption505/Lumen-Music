"""Local, backup-friendly AI preferences and a separate credential file."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlsplit

from .ai_intent import AiIntentConfig


class AiSettingsError(ValueError):
    pass


class AiSettingsStore:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.secret_path = self.path.with_name("ai-secret.json")
        self.recovery_path = self.path.with_name(".ai-settings-recovery.json")

    def read(self) -> dict:
        self._recover()
        if not self.path.exists():
            inherited = AiIntentConfig.from_env()
            return {"version": 1, "enabled": bool(inherited and inherited.is_valid()),
                    "provider": "openai-compatible",
                    "model": inherited.model if inherited else "",
                    "baseUrl": inherited.chat_completions_url if inherited else ""}
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, ValueError) as exc:
            raise AiSettingsError("AI 配置文件无法读取或已损坏") from exc
        if not isinstance(value, dict) or value.get("version") != 1:
            raise AiSettingsError("AI 配置版本不受支持")
        enabled = value.get("enabled")
        model = value.get("model", "")
        base_url = value.get("baseUrl", "")
        if (type(enabled) is not bool or value.get("provider") != "openai-compatible"
                or not isinstance(model, str) or not isinstance(base_url, str)):
            raise AiSettingsError("AI 配置字段无效")
        if enabled:
            self._validate_endpoint(model, base_url)
        return {"version": 1, "enabled": enabled, "provider": "openai-compatible",
                "model": model, "baseUrl": base_url}

    @staticmethod
    def _validate_endpoint(model: str, base_url: str) -> None:
        try:
            parsed = urlsplit(base_url)
        except ValueError as exc:
            raise AiSettingsError("AI 服务地址无效") from exc
        if (not model.strip() or len(model) > 128 or not base_url.strip()
                or len(base_url) > 512 or parsed.scheme != "https"
                or not parsed.hostname or parsed.username or parsed.password):
            raise AiSettingsError("启用 AI 需要模型名称和 HTTPS Chat Completions 地址")

    @staticmethod
    def _atomic_json(path: Path, value: dict) -> None:
        AiSettingsStore._atomic_bytes(path, json.dumps(value, ensure_ascii=False).encode("utf-8"))

    @staticmethod
    def _atomic_bytes(path: Path, value: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix=".ai-", suffix=".tmp", dir=path.parent)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(value)
                output.flush()
                os.fsync(output.fileno())
            os.replace(name, path)
        finally:
            Path(name).unlink(missing_ok=True)

    def _recover(self) -> None:
        """Restore an interrupted pair before exposing either configuration.

        The local journal contains credentials, just like ai-secret.json; it is
        never returned over HTTP or included in the preferences backup.
        """
        if not self.recovery_path.exists():
            return
        try:
            journal = json.loads(self.recovery_path.read_text(encoding="utf-8"))
            if not isinstance(journal, dict) or journal.get("version") != 1:
                raise ValueError("invalid journal")
            entries = journal["files"]
            if not isinstance(entries, list) or len(entries) != 2:
                raise ValueError("invalid journal")
            pairs = []
            for path, entry in zip((self.path, self.secret_path), entries):
                old, new = (base64.b64decode(entry[field], validate=True)
                            if entry[field] is not None else None for field in ("old", "new"))
                current = path.read_bytes() if path.exists() else None
                # Preserve a later external edit rather than overwriting unknown data.
                if current != old and current != new:
                    raise ValueError("external change")
                pairs.append((path, old, current))
            for path, old, current in pairs:
                if old == current:
                    continue
                if old is None:
                    path.unlink(missing_ok=True)
                else:
                    self._atomic_bytes(path, old)
            self.recovery_path.unlink()
        except (OSError, ValueError, TypeError, KeyError, UnicodeError) as exc:
            raise AiSettingsError("AI 配置恢复未完成；旧配置与密钥保留在 .ai-settings-recovery.json，请恢复文件访问后重试") from exc

    def _secret(self) -> dict:
        try:
            value = json.loads(self.secret_path.read_text(encoding="utf-8"))
            if (isinstance(value, dict) and value.get("version") == 1
                    and isinstance(value.get("apiKey"), str)
                    and isinstance(value.get("baseUrl", ""), str)):
                return value
        except (OSError, UnicodeError, ValueError):
            pass
        return {}

    def _key(self, settings: dict) -> str:
        target = settings["baseUrl"]
        env_target = os.environ.get("LUMEN_AI_CHAT_COMPLETIONS_URL", "").strip()
        env_key = os.environ.get("LUMEN_AI_API_KEY", "").strip()
        if target and target == env_target and env_key:
            return env_key
        secret = self._secret()
        if secret and "baseUrl" not in secret:
            # Adopt a legacy secret only for the old, valid configured endpoint.
            # Persist the binding before use so later backup restores cannot rebind it.
            try:
                self._validate_endpoint(settings["model"], target)
            except AiSettingsError:
                return ""
            secret = dict(secret, baseUrl=target)
            self._atomic_json(self.secret_path, secret)
        return secret.get("apiKey", "").strip() if target and secret.get("baseUrl") == target else ""

    def preserve_credential_target(self) -> None:
        """Bind a legacy key before a preferences-only backup can replace its URL.

        A damaged or empty old configuration supplies no ownership evidence. The
        credential is retained with an empty target and requires explicit entry
        before reuse; restoring preferences must not adopt it for a new server.
        """
        self._recover()
        secret = self._secret()
        if not secret or "baseUrl" in secret:
            return
        try:
            previous = self.read()
            self._validate_endpoint(previous["model"], previous["baseUrl"])
            target = previous["baseUrl"]
        except AiSettingsError:
            target = ""
        self._atomic_json(self.secret_path, dict(secret, baseUrl=target))

    def save(self, *, enabled: bool, model: str, base_url: str,
             api_key: str | None = None) -> dict:
        if type(enabled) is not bool:
            raise AiSettingsError("AI 开关无效")
        model, base_url = model.strip(), base_url.strip()
        if enabled:
            self._validate_endpoint(model, base_url)
        elif len(model) > 128 or len(base_url) > 512:
            raise AiSettingsError("AI 配置过长")
        if api_key is not None and (not api_key.strip() or len(api_key) > 4096):
            raise AiSettingsError("AI 密钥无效")
        previous = self.read()
        settings = {"version": 1, "enabled": enabled,
                    "provider": "openai-compatible"}
        if model:
            settings["model"] = model
        if base_url:
            settings["baseUrl"] = base_url
        secret = self._secret()
        if api_key is not None:
            # A supplied credential must always have a valid target, even when disabled.
            self._validate_endpoint(model, base_url)
            secret = {"version": 1, "apiKey": api_key.strip(), "baseUrl": base_url}
        elif secret and "baseUrl" not in secret:
            try:
                self._validate_endpoint(previous["model"], previous["baseUrl"])
                old_target = previous["baseUrl"]
            except AiSettingsError:
                # Old disabled preferences may have no endpoint. Keep the key
                # recoverable, but do not guess which server owns it.
                old_target = ""
            secret = dict(secret, baseUrl=old_target)
        old = [path.read_bytes() if path.exists() else None
               for path in (self.path, self.secret_path)]
        new = [json.dumps(settings, ensure_ascii=False).encode("utf-8"),
               json.dumps(secret, ensure_ascii=False).encode("utf-8") if secret else old[1]]
        journal = {"version": 1, "files": [
            {field: base64.b64encode(value).decode("ascii") if value is not None else None
             for field, value in (("old", before), ("new", after))}
            for before, after in zip(old, new)]}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # Publish a complete, fsynced journal before either data-file replacement.
        # An interruption during journal creation leaves the previous pair usable.
        if self.recovery_path.exists():
            raise AiSettingsError("AI 配置恢复记录已存在，请重试恢复后保存") from None
        self._atomic_json(self.recovery_path, journal)
        try:
            for path, before, after in zip((self.path, self.secret_path), old, new):
                if after != before and after is not None:
                    self._atomic_bytes(path, after)
            self.recovery_path.unlink()
        except OSError:
            self._recover()
            raise
        return self.read()

    def has_key(self) -> bool:
        return bool(self._key(self.read()))

    def config(self) -> AiIntentConfig | None:
        self._recover()
        if not self.path.exists():
            return AiIntentConfig.from_env()
        settings = self.read()
        if not settings["enabled"]:
            return None
        key = self._key(settings)
        if not key:
            return None
        return AiIntentConfig(settings["baseUrl"], settings["model"], key)
