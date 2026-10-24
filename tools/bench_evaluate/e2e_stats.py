"""Statistiques du générateur de bout en bout (e2e_load.py).

Usage : python tools/bench_evaluate/e2e_stats.py <dossier contenant e2e-*.csv>

Par fichier (un fichier = un passage) : décisions rendues, erreurs, puis médiane, p95, p99 et
maximum de la latence comptée depuis l'instant PRÉVU d'envoi (celle qui répond à ENF-01), et de la
durée depuis l'envoi réel ; enfin la latence minute par minute. Centile : rang supérieur, sans
interpolation, comme stats.py.
"""

import csv
import math
import sys
from collections import Counter
from pathlib import Path


def pct(xs: list[int], p: float) -> int:
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)]


def line(label: str, xs: list[int]) -> str:
    return (
        f"{label:22} {len(xs):6} {pct(xs, 50) / 1000:7.1f} {pct(xs, 95) / 1000:7.1f}"
        f" {pct(xs, 99) / 1000:7.1f} {max(xs) / 1000:8.1f}"
    )


def main() -> int:
    for f in sorted(Path(sys.argv[1]).glob("e2e-*.csv")):
        with f.open(encoding="utf-8") as fh:
            rows = list(csv.DictReader(fh))
        ok = [r for r in rows if r["status"] == "200"]
        print(f"== {f.name} : {len(rows)} requêtes, {len(rows) - len(ok)} erreur(s)")
        print("   décisions :", dict(Counter((r["kind"], r["decision"]) for r in rows)))
        print(f"   {'mesure':22} {'n':>6}  med_ms  p95_ms  p99_ms   max_ms")
        for kind in ("ALLOW", "FREEZE", None):
            sel = [r for r in ok if kind is None or r["kind"] == kind]
            name = kind or "tout"
            print("   " + line(f"{name} depuis prévu", [int(r["fromDueMicros"]) for r in sel]))
            print("   " + line(f"{name} depuis envoi", [int(r["fromSendMicros"]) for r in sel]))
        print("   minute par minute (tout, depuis prévu)")
        minutes: dict[int, list[int]] = {}
        for r in ok:
            minutes.setdefault(int(r["dueAtMs"]) // 60_000, []).append(int(r["fromDueMicros"]))
        for m in sorted(minutes):
            print("   " + line(f"minute {m}", minutes[m]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
