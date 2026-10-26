#!/usr/bin/env python3
import sys
from pathlib import Path


def build(project_root: Path, output_file: Path) -> None:
    sections: list[str] = []
    for path in sorted(project_root.rglob("*")):
        if path.is_file() and path.resolve() != output_file.resolve():
            sections.append(f"[{path.relative_to(project_root)}]")
            sections.append(path.read_text(encoding="utf-8").rstrip())
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text("\n".join(sections) + "\n", encoding="utf-8")


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
