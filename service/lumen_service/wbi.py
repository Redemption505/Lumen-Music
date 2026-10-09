"""Bilibili WBI query signing using standard-library primitives."""

from __future__ import annotations

import hashlib
from urllib.parse import quote, urlencode, urlsplit


_MIXIN_ORDER = (
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
)
_FILTER = str.maketrans("", "", "!'()*")


def mixin_key(img_url: str, sub_url: str) -> str:
    keys = []
    for url in (img_url, sub_url):
        key = urlsplit(url).path.rsplit("/", 1)[-1].split(".", 1)[0]
        if len(key) != 32 or any(char not in "0123456789abcdefABCDEF" for char in key):
            raise ValueError("invalid WBI image key")
        keys.append(key)
    combined = "".join(keys)
    return "".join(combined[index] for index in _MIXIN_ORDER)


def sign_query(params: dict[str, object], key: str, timestamp: int) -> str:
    values = {name: str(value).translate(_FILTER) for name, value in params.items()
              if name not in ("w_rid", "wts")}
    values["wts"] = str(timestamp)
    query = urlencode(sorted(values.items()), quote_via=quote)
    digest = hashlib.md5((query + key).encode("utf-8"), usedforsecurity=False).hexdigest()
    return f"{query}&w_rid={digest}"
