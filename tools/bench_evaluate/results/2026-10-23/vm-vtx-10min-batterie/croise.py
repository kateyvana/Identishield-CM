"""Croise, minute par minute, la latence sq30 avec la fréquence effective et l'utilisation du poste.

L'instant absolu d'un échantillon est reconstruit depuis la fin du fichier (mtime VM, + décalage
d'horloge VM -> poste) : t = fin - (max(atMs) - atMs).
"""

import csv
import math
import sys
from datetime import UTC, datetime, timedelta

ends_vm = {  # mtime des fichiers dans la VM (UTC VM)
    "c4a1167-1": "2026-10-23 15:06:17",
    "ab44b64-1": "2026-10-23 15:16:39",
    "c4a1167-2": "2026-10-23 15:27:11",
    "ab44b64-2": "2026-10-23 15:38:11",
}
OFFSET = timedelta(seconds=27)  # horloge VM en retard de ~27 s sur le poste
res_dir, freq_csv = sys.argv[1], sys.argv[2]


def pct(xs, p):
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)]


freq = []
with open(freq_csv, encoding="ascii") as fh:
    for r in csv.DictReader(fh):
        t = datetime.strptime(r["utc"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
        freq.append((t, float(r["effectif_mhz"]), float(r["utilisation_pct"])))

print("passage     minute  heure(poste)  p95_ms   p99_ms   MHz_moy  MHz_min  util_moy")
for run in ["c4a1167-1", "ab44b64-1", "c4a1167-2", "ab44b64-2"]:
    with open(f"{res_dir}/result-{run}.csv", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    end = datetime.strptime(ends_vm[run], "%Y-%m-%d %H:%M:%S").replace(tzinfo=UTC) + OFFSET
    last = max(int(r["atMs"]) for r in rows)
    sq = [r for r in rows if r["phase"] == "sq30"]
    first = min(int(r["atMs"]) for r in sq)
    by_min = {}
    for r in sq:
        by_min.setdefault((int(r["atMs"]) - first) // 60_000, []).append(int(r["micros"]))
    for m in sorted(by_min):
        a = end - timedelta(milliseconds=last - first - m * 60_000)
        b = a + timedelta(minutes=1)
        f = [x for x in freq if a <= x[0] < b]
        mhz = sum(x[1] for x in f) / len(f) if f else float("nan")
        mn = min((x[1] for x in f), default=float("nan"))
        ut = sum(x[2] for x in f) / len(f) if f else float("nan")
        xs = by_min[m]
        print(
            f"{run:10} {m:6}  {a:%H:%M:%S}   {pct(xs, 95) / 1000:7.1f}  {pct(xs, 99) / 1000:7.1f}"
            f"  {mhz:7.0f}  {mn:7.0f}  {ut:7.1f}"
        )
