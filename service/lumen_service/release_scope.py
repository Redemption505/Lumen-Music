from __future__ import annotations

from collections.abc import Sequence

from .models import Track
from .providers import ProviderError


LOCAL_FIRST_PROFILE = "local-first"
FULL_PROFILE = "full"


class LocalOnlyProvider:
    """Provider identity for local-first releases; online routes are rejected by the server."""

    @property
    def name(self) -> str:
        return "local"

    def search(self, query: str) -> Sequence[Track]:
        raise ProviderError("LOCAL_FIRST_UNAVAILABLE", "本地优先版本不提供在线曲目搜索", 503)

    def music_popular(self) -> Sequence[Track]:
        raise ProviderError("LOCAL_FIRST_UNAVAILABLE", "本地优先版本不提供在线发现", 503)


def unavailable_error(message: str = "该在线功能在本地优先版本中不可用") -> ProviderError:
    return ProviderError("LOCAL_FIRST_UNAVAILABLE", message, 503)
