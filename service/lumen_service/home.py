"""Explicit versioned home layout preferences, shared by storage and HTTP."""

from __future__ import annotations

from typing import Any, Mapping
from xml.etree import ElementTree

DEFAULT_SECTIONS = ("continue", "pinned", "reunion", "discover")
MAX_PINNED = 6


def defaults() -> dict[str, Any]:
    return {"version": 1, "sections": list(DEFAULT_SECTIONS), "pinned": []}


def normalize(value: Any, playlists: list[dict], smart: list[dict]) -> dict[str, Any]:
    if not isinstance(value, Mapping) or set(value) != {"version", "sections", "pinned"}:
        raise ValueError("首页偏好格式无效")
    if type(value["version"]) is not int or value["version"] != 1:
        raise ValueError("不支持的首页偏好版本")
    sections, pins = value["sections"], value["pinned"]
    if not isinstance(sections, list) or len(sections) != len(DEFAULT_SECTIONS) or any(
            not isinstance(item, str) for item in sections) or set(sections) != set(DEFAULT_SECTIONS):
        raise ValueError("首页区域必须是四个区域的完整排列")
    if not isinstance(pins, list) or len(pins) > MAX_PINNED:
        raise ValueError("首页常驻最多六个歌单")
    known = {"playlist": {item["id"] for item in playlists},
             "smart": {item["id"] for item in smart}}
    seen: set[tuple[str, str]] = set()
    result = []
    for pin in pins:
        if not isinstance(pin, Mapping) or set(pin) != {"kind", "id"}:
            raise ValueError("首页常驻格式无效")
        kind, identifier = pin["kind"], pin["id"]
        if not isinstance(kind, str) or kind not in known or not isinstance(identifier, str) \
                or identifier not in known[kind]:
            raise ValueError("首页常驻指向不存在的普通或智能歌单")
        if (kind, identifier) in seen:
            raise ValueError("首页常驻歌单重复")
        seen.add((kind, identifier))
        result.append({"kind": kind, "id": identifier})
    return {"version": 1, "sections": list(sections), "pinned": result}


def from_xml(node: ElementTree.Element) -> dict[str, Any]:
    if node.tag != "home" or set(node.attrib) != {"version"} or node.get("version") != "1":
        raise ValueError("需要版本为 1 的 home 元素")
    children = list(node)
    if len(children) != 2 or [child.tag for child in children] != ["sections", "pinned"] \
            or any(child.attrib for child in children):
        raise ValueError("首页偏好需要 sections 和 pinned 元素")
    sections, pinned = children
    if any(child.tag != "section" or set(child.attrib) != {"id"} or list(child)
           for child in sections) or any(
            child.tag != "pin" or set(child.attrib) != {"kind", "id"} or list(child)
            for child in pinned):
        raise ValueError("首页区域或常驻格式无效")
    return {"version": 1, "sections": [child.get("id") for child in sections],
            "pinned": [dict(child.attrib) for child in pinned]}


def to_xml(value: Mapping[str, Any]) -> bytes:
    root = ElementTree.Element("home", {"version": str(value["version"])})
    sections = ElementTree.SubElement(root, "sections")
    for identifier in value["sections"]:
        ElementTree.SubElement(sections, "section", {"id": identifier})
    pinned = ElementTree.SubElement(root, "pinned")
    for pin in value["pinned"]:
        ElementTree.SubElement(pinned, "pin", pin)
    return ElementTree.tostring(root, encoding="utf-8", xml_declaration=True)
