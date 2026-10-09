"""Small, embedded playlist metadata without external image dependencies."""

import base64
import binascii
import struct
import zlib

MAX_COVER_BYTES = 512 * 1024
# Base64 expansion plus XML, description and name overhead.
MAX_METADATA_BODY_BYTES = 704 * 1024


def description(value: object) -> str:
    if not isinstance(value, str) or len(value) > 280:
        raise ValueError("歌单描述必须是最多 280 字的字符串")
    return value


def cover_data_uri(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("歌单封面必须是 PNG/JPEG 的 base64 data URI 或空字符串")
    if not value:
        return ""
    header, separator, encoded = value.partition(",")
    if not separator or header not in ("data:image/png;base64", "data:image/jpeg;base64"):
        raise ValueError("歌单封面仅支持 PNG/JPEG 的 base64 data URI")
    if len(encoded) > 4 * ((MAX_COVER_BYTES + 2) // 3):
        raise ValueError("歌单封面不得超过 512 KiB")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("歌单封面 base64 无效") from exc
    if not data or len(data) > MAX_COVER_BYTES:
        raise ValueError("歌单封面不得为空或超过 512 KiB")
    # Reject non-canonical padding, mixed MIME types and truncated containers.
    if base64.b64encode(data).decode("ascii") != encoded:
        raise ValueError("歌单封面 base64 无效")
    valid = _png(data) if header == "data:image/png;base64" else _jpeg(data)
    if not valid:
        raise ValueError("歌单封面图片格式无效或与 MIME 类型不符")
    return value


def _png(data: bytes) -> bool:
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    offset, first, image_data = 8, True, False
    while offset + 12 <= len(data):
        size = int.from_bytes(data[offset:offset + 4], "big")
        end = offset + 12 + size
        if end > len(data):
            return False
        kind, content = data[offset + 4:offset + 8], data[offset + 8:end - 4]
        if zlib.crc32(kind + content) != int.from_bytes(data[end - 4:end], "big"):
            return False
        if first:
            if kind != b"IHDR" or size != 13:
                return False
            width, height, depth, color, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", content)
            depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8),
                      4: (8, 16), 6: (8, 16)}
            if not width or not height or depth not in depths.get(color, ()) \
                    or compression != 0 or filtering != 0 or interlace not in (0, 1):
                return False
            first = False
        elif kind == b"IHDR":
            return False
        if kind == b"IDAT":
            image_data = image_data or size > 0
        if kind == b"IEND":
            return size == 0 and image_data and end == len(data)
        offset = end
    return False


def _jpeg(data: bytes) -> bool:
    if not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
        return False
    offset, dimensions = 2, False
    while offset < len(data) - 2:
        if data[offset] != 0xff:
            return False
        while offset < len(data) and data[offset] == 0xff:
            offset += 1
        if offset >= len(data):
            return False
        marker = data[offset]
        offset += 1
        if marker in (0, 0xd8, 0xd9) or offset + 2 > len(data):
            return False
        size = int.from_bytes(data[offset:offset + 2], "big")
        if size < 2 or offset + size > len(data) - 2:
            return False
        if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                      0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
            if size < 8 or not int.from_bytes(data[offset + 3:offset + 5], "big") \
                    or not int.from_bytes(data[offset + 5:offset + 7], "big"):
                return False
            dimensions = True
        if marker == 0xda:
            return dimensions and size >= 6 and offset + size < len(data) - 2
        offset += size
    return False
