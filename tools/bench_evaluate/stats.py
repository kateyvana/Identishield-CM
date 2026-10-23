"""Médiane, p95, p99 et maximum par phase, décision et commit ; débit saturé (loi de Little).

Usage : python tools/bench_evaluate/stats.py <dossier contenant result-<commit>-<tour>.csv>

Centile : rang supérieur (plus petite valeur dont le rang couvre p % des échantillons), sans
interpolation. Débit en saturation : 16 fils en boucle fermée, X = 16 / latence moyenne.
Si les fichiers portent la colonne atMs (depuis le 2026-10-23), la phase sq30 est aussi
découpée minute par minute, depuis son premier échantillon : c'est là qu'un bridage se verrait.
"""

import csv
import math
import sys
from pathlib import Path

SAT_THREADS = 16


def pct(xs: list[int], p: float) -> int:
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)]


def main() -> int:
    groups: dict[tuple[str, ...], list[int]] = {}
    minutes: dict[tuple[str, int], list[int]] = {}
    for f in sorted(Path(sys.argv[1]).glob("result-*.csv")):
        commit, rnd = f.stem.removeprefix("result-").rsplit("-", 1)
        with f.open(encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
            sq30 = [int(r["atMs"]) for r in rows if r["phase"] == "sq30" and r.get("atMs")]
            first = min(sq30) if sq30 else 0
            for r in rows:
                us = int(r["micros"])
                groups.setdefault((r["phase"], r["kind"], commit, "tous"), []).append(us)
                groups.setdefault((r["phase"], r["kind"], commit, rnd), []).append(us)
                if r["phase"] == "sq30" and r.get("atMs"):
                    minute = (int(r["atMs"]) - first) // 60_000
                    minutes.setdefault((commit, minute), []).append(us)

    print("phase  cas     commit   tour     n  med_ms  p95_ms  p99_ms   max_ms")
    for k in sorted(groups):
        xs = groups[k]
        print(
            f"{k[0]:6} {k[1]:7} {k[2]:8} {k[3]:4} {len(xs):5} {pct(xs, 50) / 1000:7.1f}"
            f" {pct(xs, 95) / 1000:7.1f} {pct(xs, 99) / 1000:7.1f} {max(xs) / 1000:8.1f}"
        )

    print()
    print("débit saturé (sat16, ALLOW + FREEZE)")
    rounds = sorted({(k[2], k[3]) for k in groups if k[0] == "sat16" and k[3] != "tous"})
    for commit, rnd in rounds:
        xs = groups[("sat16", "ALLOW", commit, rnd)] + groups[("sat16", "FREEZE", commit, rnd)]
        mean_s = sum(xs) / len(xs) / 1e6
        print(
            f"{commit:8} tour {rnd} : moyenne {mean_s * 1000:6.1f} ms, ~{SAT_THREADS / mean_s:5.1f} req/s"
        )
    if minutes:
        print()
        print("sq30 minute par minute (ALLOW + FREEZE, deux tours)")
        print("commit   minute     n  med_ms  p95_ms  p99_ms   max_ms")
        for (commit, minute), xs in sorted(minutes.items()):
            print(
                f"{commit:8} {minute:6} {len(xs):5} {pct(xs, 50) / 1000:7.1f}"
                f" {pct(xs, 95) / 1000:7.1f} {pct(xs, 99) / 1000:7.1f} {max(xs) / 1000:8.1f}"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
