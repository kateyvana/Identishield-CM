"""Croise le bout-en-bout minute par minute avec la fréquence effective et l'utilisation du poste.
Instant absolu (poste, UTC) d'une requête = fin du fichier (mtime VM + décalage) - (max dueAtMs - dueAtMs)."""

import csv
import math
import sys
from datetime import UTC, datetime, timedelta


def _read(path, encoding="utf-8"):
    with open(path, encoding=encoding) as fh:
        return list(csv.DictReader(fh))


res, freq_csv = sys.argv[1], sys.argv[2]
OFFSET = timedelta(seconds=28.26)  # VM en retard sur le poste, mesuré à 17:51 (28,2 s à 17:39)
ends = {"1": "2026-10-23 17:40:55.574", "2": "2026-10-23 17:50:58.467"}


def pct(xs, p):
    xs = sorted(xs)
    return xs[max(0, math.ceil(p / 100 * len(xs)) - 1)]


freq = []
for r in _read(freq_csv, "ascii"):
    t = datetime.strptime(r["utc"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
    freq.append((t, float(r["effectif_mhz"]), float(r["utilisation_pct"])))

print("tour minute heure(poste)  p95_ms  p99_ms   max_ms  MHz_moy MHz_min util_moy util_max")
for rnd, e in ends.items():
    rows = _read(f"{res}/e2e-8929a3f-{rnd}.csv")
    end = datetime.strptime(e, "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=UTC) + OFFSET
    last = max(int(r["dueAtMs"]) for r in rows)
    first = min(int(r["dueAtMs"]) for r in rows)
    by = {}
    for r in rows:
        by.setdefault((int(r["dueAtMs"]) - first) // 60_000, []).append(int(r["fromDueMicros"]))
    for m in sorted(by):
        a = end - timedelta(milliseconds=last - first - m * 60_000)
        f = [x for x in freq if a <= x[0] < a + timedelta(minutes=1)]
        xs = by[m]
        mhz = [x[1] for x in f] or [float("nan")]
        ut = [x[2] for x in f] or [float("nan")]
        print(
            f"{rnd:4} {m:6}  {a:%H:%M:%S}   {pct(xs, 95) / 1000:6.1f}  {pct(xs, 99) / 1000:6.1f}"
            f"  {max(xs) / 1000:7.1f}  {sum(mhz) / len(mhz):7.0f} {min(mhz):7.0f}"
            f"  {sum(ut) / len(ut):7.1f} {max(ut):7.1f}"
        )
