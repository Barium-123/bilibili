#!/usr/bin/env python3
"""Offline sanity checks for the project (no Android SDK required).

Checks:
  * every XML file is well formed
  * every @type/name reference resolves to a defined resource
  * every R.type.name used from Java resolves
  * every layout id used with findViewById exists in a layout
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")

REF_RE = re.compile(r"@(string|color|drawable|mipmap|style|xml|layout)/([A-Za-z0-9_]+)")
R_RE = re.compile(r"\bR\.(string|color|drawable|mipmap|style|xml|layout|id)\.([A-Za-z0-9_]+)")

errors = []
defined = {t: set() for t in ("string", "color", "drawable", "mipmap", "style", "xml", "layout", "id")}


def add(kind, name):
    defined[kind].add(name)


def scan_values():
    for folder in ("values", "values-zh"):
        path = os.path.join(RES, folder)
        if not os.path.isdir(path):
            continue
        for name in os.listdir(path):
            if not name.endswith(".xml"):
                continue
            tree = ET.parse(os.path.join(path, name))
            for child in tree.getroot():
                tag = child.tag
                res_name = child.get("name")
                if not res_name:
                    continue
                if tag == "string":
                    add("string", res_name)
                elif tag == "color":
                    add("color", res_name)
                elif tag == "style":
                    add("style", res_name)


def scan_files():
    for folder, kind in (
        ("drawable", "drawable"), ("mipmap-mdpi", "mipmap"), ("mipmap-hdpi", "mipmap"),
        ("mipmap-xhdpi", "mipmap"), ("mipmap-xxhdpi", "mipmap"), ("mipmap-xxxhdpi", "mipmap"),
        ("layout", "layout"), ("xml", "xml"), ("color", "color"),
    ):
        path = os.path.join(RES, folder)
        if not os.path.isdir(path):
            continue
        for name in os.listdir(path):
            if name.endswith(".xml") or name.endswith(".png"):
                add(kind, os.path.splitext(name)[0])


def check_xml():
    for base, _, files in os.walk(RES):
        for name in files:
            if not name.endswith(".xml"):
                continue
            path = os.path.join(base, name)
            try:
                ET.parse(path)
            except ET.ParseError as exc:
                errors.append(f"XML 解析失败 {os.path.relpath(path, ROOT)}: {exc}")
                continue
            text = open(path, encoding="utf-8").read()
            for kind, ref in REF_RE.findall(text):
                if ref not in defined[kind]:
                    errors.append(f"缺少资源 @{kind}/{ref} (引用于 {os.path.relpath(path, ROOT)})")
            for ref in re.findall(r"@\+id/([A-Za-z0-9_]+)", text):
                add("id", ref)


def check_manifest():
    try:
        ET.parse(MANIFEST)
    except ET.ParseError as exc:
        errors.append(f"XML 解析失败 AndroidManifest.xml: {exc}")


def check_java():
    for base, _, files in os.walk(JAVA):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            text = open(path, encoding="utf-8").read()
            for kind, ref in R_RE.findall(text):
                if kind == "id":
                    if ref not in defined["id"]:
                        errors.append(f"缺少 id R.id.{ref} ({name})")
                elif ref not in defined[kind]:
                    errors.append(f"缺少资源 R.{kind}.{ref} ({name})")


def main():
    scan_values()
    scan_files()
    check_xml()
    check_manifest()
    check_java()

    print(f"strings={len(defined['string'])} colors={len(defined['color'])} "
          f"drawables={len(defined['drawable'])} ids={len(defined['id'])}")
    if errors:
        print("\n".join("ERROR: " + e for e in errors))
        return 1
    print("OK: all XML well-formed and all resource references resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
