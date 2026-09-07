#!/usr/bin/env python3
"""Convert the copywriter-friendly Markdown file into the bot XML resource."""

from __future__ import annotations

import shutil
import sys
import re
from pathlib import Path
from xml.etree import ElementTree as ET


def read_editor_file(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    sections: dict[str, str] = {}
    current: str | None = None
    body: list[str] = []
    for line in text.split("\n"):
        if line.startswith("## "):
            if current is not None:
                sections[current] = "\n".join(body).strip()
            current = line[3:].strip()
            body = []
        elif current is not None:
            body.append(line)
    if current is not None:
        sections[current] = "\n".join(body).strip()
    return sections


def main() -> int:
    if len(sys.argv) != 3:
        print("Использование: python3 tools/import_copy_editor.py seaBattleBot_text_for_editor.md copy.xml")
        return 2
    editor, target = map(Path, sys.argv[1:])
    source = read_editor_file(editor)
    root = ET.parse(target).getroot()
    keys = [element.tag for element in root]
    missing = [key for key in keys if key not in source]
    invalid = [key for key in source if not re.fullmatch(r"[a-z][a-z0-9_]*", key)]
    if missing or invalid:
        if missing:
            print("Не найдены ключи: " + ", ".join(missing))
        if invalid:
            print("Недопустимые новые ключи: " + ", ".join(invalid))
        return 1
    backup = target.with_suffix(target.suffix + ".bak")
    shutil.copy2(target, backup)
    for element in root:
        element.text = source[element.tag]
    for key, value in source.items():
        if key not in keys:
            element = ET.SubElement(root, key)
            element.text = value
    ET.indent(ET.ElementTree(root), space="  ")
    ET.ElementTree(root).write(target, encoding="utf-8", xml_declaration=True)
    print(f"Готово: {target}. Резервная копия: {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
