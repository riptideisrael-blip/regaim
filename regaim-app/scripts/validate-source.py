#!/usr/bin/env python3
"""Static integrity checks. These do not replace Android UI or hardware tests."""
import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sources = list((root / "app/src/main/java").rglob("*.kt"))
checks = []

def check(name, condition, detail=""):
    checks.append((name, bool(condition), detail))

xml_files = list((root / "app/src/main/res").rglob("*.xml")) + [root / "app/src/main/AndroidManifest.xml"]
parsed = {}
for path in xml_files:
    try:
        parsed[path] = ET.parse(path)
    except ET.ParseError as error:
        check("XML syntax", False, f"{path.relative_to(root)}: {error}")
check("XML syntax", len(parsed) == len(xml_files), f"{len(xml_files)} files")
manifest = parsed[root / "app/src/main/AndroidManifest.xml"]
ns = "{http://schemas.android.com/apk/res/android}"
all_text = "\n".join(path.read_text() for path in sources)
classes = set(re.findall(r"\bclass\s+(\w+)", all_text))
for kind in ("activity", "service"):
    for component in manifest.findall(f"application/{kind}"):
        name = component.get(ns + "name", "")
        check("Manifest class", name.split(".")[-1] in classes, name)
for name in ("FamilyChatActivity", "MediaImportActivity"):
    match = manifest.find(f"application/activity[@{ns}name='.{name}']")
    check("Private activity", match is not None and match.get(ns + "exported") == "false", name)

resources = {key: set() for key in ("id", "layout", "drawable", "color", "string", "anim", "xml", "mipmap")}
for path in xml_files:
    if path.parent.name != "values":
        category = path.parent.name.split("-")[0]
        if category in resources:
            resources[category].add(path.stem)
    for node in parsed.get(path, ET.ElementTree(ET.Element("empty"))).iter():
        if node.get("name") and node.tag in resources:
            resources[node.tag].add(node.get("name"))
        if node.tag == "item" and node.get("type") in resources and node.get("name"):
            resources[node.get("type")].add(node.get("name"))
        for value in node.attrib.values():
            for name in re.findall(r"@\+id/(\w+)", value):
                resources["id"].add(name)
for path in sources:
    for category, name in re.findall(r"(?<!android\.)\bR\.(id|layout|drawable|color|string|anim|xml|mipmap)\.(\w+)", path.read_text()):
        check("Resource reference", name in resources[category], f"{path.name}: R.{category}.{name}")

parser = argparse.ArgumentParser()
parser.add_argument("--original-zip", type=Path)
args = parser.parse_args()
if args.original_zip:
    # The recorder, rolling queue, bubble and detection implementations are preserved.
    unchanged = ("CaptureService.kt", "Core.kt", "LaughDetector.kt", "KeywordSpotter.kt", "FaceWatcher.kt", "LiveBubbleView.kt", "Preview.kt", "People.kt")
    with zipfile.ZipFile(args.original_zip) as archive:
        for name in unchanged:
            suffix = f"app/src/main/java/com/regaim/app/{name}"
            original = next(item for item in archive.namelist() if item.endswith(suffix))
            check("Capture preservation", hashlib.sha256(archive.read(original)).digest() == hashlib.sha256((root / suffix).read_bytes()).digest(), name)
    check("YAMNet packaged", (root / "app/src/main/assets/yamnet.tflite").is_file())

failed = [item for item in checks if not item[1]]
for name, _, detail in failed:
    print(f"FAIL: {name}: {detail}")
print(f"Static source validation: {len(checks) - len(failed)}/{len(checks)} passed")
print("Scope: XML, registered Activities/services, resource references, optional original-capture hash comparison. No device/AI/backend runtime claim.")
sys.exit(bool(failed))
