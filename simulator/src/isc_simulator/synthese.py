"""Reader of config/synthese.yaml, the synthesis parameters of the generator (T3-05a).

Stdlib only (ADR-033): the file is a STRICT subset of YAML (mappings by two-space indentation,
scalar values, `#` comments). Anything else is refused rather than guessed. The reference
profile it points to (profil_reel_v1.1.json) is loaded here too, so the generator reads its
calibrated laws from one place.
"""

from __future__ import annotations

import json
import re
from functools import cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONFIG = ROOT / "config" / "synthese.yaml"
_LINE = re.compile(r"^( *)([A-Za-z_][A-Za-z0-9_]*):(?: +(.*?))?\s*$")


def _scalar(raw: str):
    if raw[0] in "[{&*|>!'":
        raise ValueError(f"unsupported YAML value (flow, anchor, block or tag): {raw!r}")
    if raw.startswith('"') and raw.endswith('"') and len(raw) >= 2:
        return raw[1:-1]
    if re.fullmatch(r"-?\d+", raw):
        return int(raw)
    if re.fullmatch(r"-?\d+\.\d+", raw):
        return float(raw)
    if raw in ("true", "false"):
        return raw == "true"
    return raw


def parse(text: str) -> dict:
    """Parse the strict YAML subset: nested mappings of scalars."""
    root: dict = {}
    stack: list[tuple[int, dict]] = [(-1, root)]
    for number, line in enumerate(text.splitlines(), 1):
        content = line.split(" #", 1)[0].rstrip() if not line.lstrip().startswith("#") else ""
        if not content.strip():
            continue
        m = _LINE.match(content)
        if m is None:
            raise ValueError(f"synthese.yaml:{number}: unsupported syntax: {line!r}")
        indent, key, value = len(m.group(1)), m.group(2), m.group(3)
        if indent % 2:
            raise ValueError(f"synthese.yaml:{number}: indentation must be a multiple of 2")
        while stack and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]
        if key in parent:
            raise ValueError(f"synthese.yaml:{number}: duplicate key {key!r}")
        if value is None or value == "":
            child: dict = {}
            parent[key] = child
            stack.append((indent, child))
        else:
            parent[key] = _scalar(value)
    return root


@cache
def load(path: Path = CONFIG) -> dict:
    """Synthesis parameters, validated, with the reference profile attached under `etalon_data`."""
    cfg = parse(path.read_text(encoding="utf-8"))["synthese"]
    mix = cfg["mix"]
    if abs(sum(mix.values()) - 1.0) > 1e-5:
        raise ValueError(f"synthese.yaml: mix shares sum to {sum(mix.values())}, not 1")
    etalon = json.loads((ROOT / cfg["etalon"]).read_text(encoding="utf-8"))
    k = etalon["f_v1_1"]["montants_masses_ponctuelles"]["k"]
    if k != cfg["montants"]["point_masses_k"]:
        raise ValueError(
            f"synthese.yaml: point_masses_k={cfg['montants']['point_masses_k']} != {k}"
        )
    return {**cfg, "etalon_data": etalon}
