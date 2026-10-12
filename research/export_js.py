"""Convert LightGBM boosters into a compact JSON the browser can evaluate
(js/gbm.js). Each tree is stored as parallel arrays:
  f feature index, t threshold, d default-left (0/1), m missing type
  (0 none, 1 zero, 2 nan), l/r child (>= 0 internal node, < 0 => leaf ~c), v leaf values.

    python research/export_js.py research/models/latest models/flip.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

MISSING = {"None": 0, "Zero": 1, "NaN": 2}


def compact_tree(node: dict) -> dict:
    out = {k: [] for k in "ftdmlrv"}

    def walk(n):
        if "leaf_value" in n:
            out["v"].append(round(n["leaf_value"], 7))
            return ~(len(out["v"]) - 1)
        i = len(out["f"])
        out["f"].append(n["split_feature"])
        out["t"].append(float(n["threshold"]))
        out["d"].append(1 if n.get("default_left", True) else 0)
        out["m"].append(MISSING.get(n.get("missing_type", "None"), 0))
        out["l"].append(0)
        out["r"].append(0)
        out["l"][i] = walk(n["left_child"])
        out["r"][i] = walk(n["right_child"])
        return i

    root = walk(node)
    if root < 0:  # single-leaf tree
        out["f"] = []
    return out


def compact_booster(dump: dict) -> dict:
    obj = dump.get("objective", "")
    return {
        "transform": "sigmoid" if obj.startswith("binary") else "identity",
        "trees": [compact_tree(t["tree_structure"]) for t in dump["tree_info"]],
    }


def export(model_dir: Path, out: Path, keep: list[str] | None = None) -> None:
    meta = json.loads((model_dir / "meta.json").read_text())
    boosters = {}
    for p in sorted(model_dir.glob("*.json")):
        if p.name == "meta.json" or (keep and p.stem not in keep):
            continue
        boosters[p.stem] = compact_booster(json.loads(p.read_text()))
    doc = {"features": meta["features"], "boosters": boosters,
           "meta": {k: v for k, v in meta.items() if k not in ("features", "report")}}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, separators=(",", ":")))
    print(f"wrote {out} ({out.stat().st_size / 1e6:.1f} MB, {len(boosters)} boosters)")


if __name__ == "__main__":
    export(Path(sys.argv[1]), Path(sys.argv[2]))
