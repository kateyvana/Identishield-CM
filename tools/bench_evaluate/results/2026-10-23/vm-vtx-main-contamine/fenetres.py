"""Heures (poste, UTC) de chaque phase MESURÉE du banc moteur sur main, et recouvrement avec les
make check du couloir B. t(phase) = fin du fichier (mtime VM + décalage) - (max atMs - atMs)."""

import csv
import sys
from datetime import UTC, datetime, timedelta


def _read(path, encoding="utf-8"):
    with open(path, encoding=encoding) as fh:
        return list(csv.DictReader(fh))


res = sys.argv[1]
OFFSET = timedelta(seconds=28.2)  # VM en retard sur le poste (mesuré à 17:39 : 28,2 s)
ends = {"1": "2026-10-23 16:58:31.487", "2": "2026-10-23 17:08:41.599"}
checks = [("16:47:26", "16:49:29"), ("17:16:38", "17:18:41")]


def h(s):
    return datetime.strptime("2026-10-23 " + s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=UTC)


for rnd, end_vm in ends.items():
    rows = _read(f"{res}/result-8929a3f-{rnd}.csv")
    end = datetime.strptime(end_vm, "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=UTC) + OFFSET
    last = max(int(r["atMs"]) for r in rows)
    for phase in ("seq", "sq30", "sat16"):
        ats = [int(r["atMs"]) for r in rows if r["phase"] == phase]
        a = end - timedelta(milliseconds=last - min(ats))
        b = end - timedelta(milliseconds=last - max(ats))
        hit = [f"{x}-{y}" for x, y in checks if not (h(y) < a or h(x) > b)]
        print(
            f"tour {rnd} {phase:5} {a:%H:%M:%S} -> {b:%H:%M:%S}  recouvrement make check B : {hit or 'aucun'}"
        )
