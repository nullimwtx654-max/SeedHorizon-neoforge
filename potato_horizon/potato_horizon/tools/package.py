#!/usr/bin/env python3
"""Reproducible .hnmod packager for PotatoHorizon.

Produces target/potato_horizon-<version>.hnmod containing:
  - horizon-native.json
  - mixins/potato_horizon/*
"""

from __future__ import annotations

import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPAT_PATH = ROOT / "compatibility.json"
MIXIN_DIR = ROOT / "mixins" / "potato_horizon"
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)


def build_hnmod() -> pathlib.Path:
    compat = json.loads(COMPAT_PATH.read_text(encoding="utf-8"))
    mod_id = compat["mod_id"]
    version = compat["version"]

    out_dir = ROOT / "target"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{mod_id}-{version}.hnmod"

    native_manifest = {
        "schema_version": 1,
        "id": mod_id,
        "name": "PotatoHorizon",
        "version": version,
        "description": (
            "Ultra-lightweight 6-to-16 chunk LOD mod for low-end PCs on Horizon."
        ),
        "hooks": compat.get("hooks", 1),
        "horizon_revision": compat["horizon_revision"],
        "horizon_mixins_revision": compat["horizon_mixins_revision"],
        "bundle": f"mixins/{mod_id}",
    }

    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo("horizon-native.json", date_time=FIXED_ZIP_TIME)
        zf.writestr(info, json.dumps(native_manifest, indent=2) + "\n")

        for file_path in sorted(MIXIN_DIR.rglob("*")):
            if not file_path.is_file():
                continue
            rel = file_path.relative_to(ROOT).as_posix()
            zinfo = zipfile.ZipInfo(rel, date_time=FIXED_ZIP_TIME)
            zinfo.external_attr = 0o644 << 16
            zf.writestr(zinfo, file_path.read_bytes())

    return out_path


if __name__ == "__main__":
    archive = build_hnmod()
    print(f"Packaged: {archive}")
    sys.exit(0)
