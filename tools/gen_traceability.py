"""Génère docs/traceability.csv à partir des marqueurs de traçabilité du code.

Convention (CLAUDE.md §5) :
  - `@implements <ID>` dans le code de production → le composant qui réalise
    l'exigence.
  - `@covers <ID>` dans un test → le test qui vérifie l'exigence / le cas d'abus.

Source machine des identifiants valides : `docs/requirements-index.csv` (index
versionné des exigences du SRS, v1.4 ; le .docx reste la référence humaine, mais
ses identifiants sont éclatés dans les cellules et ne s'extraient pas de façon
fiable — d'où l'index). Les cas d'abus AC-01..16 (ISC-TM v1.3 ; AC-14..16 depuis
la v1.2 ; la fenêtre d'amorçage étend AC-13, pas d'AC-17) sont valides d'office.

PORTE : la génération échoue (sortie non nulle) si un marqueur référence
  - une exigence inexistante dans l'index, ou
  - une exigence RETIRÉE (ex. ES-01, ES-16).

Les lignes attestées manuellement (aujourd'hui : les cinq spikes) vivent dans
docs/traceability-seed.csv et sont fusionnées telles quelles ; ce ne sont pas
des marqueurs de code, elles ne sont donc pas soumises à la porte.

Sur le dépôt actuel (squelette sans marqueurs), la sortie est quasi vide, hormis
les lignes de spikes — c'est attendu et correct.
"""

import csv
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "docs" / "requirements-index.csv"
SEED = ROOT / "docs" / "traceability-seed.csv"
OUT = ROOT / "docs" / "traceability.csv"
COLUMNS = ["origine", "exigence", "composant", "test", "verdict", "commit"]

IMPLEMENTS_RE = re.compile(r"@implements\s+([A-Za-z]{1,4}-[0-9]{1,3}[a-z]?)")
COVERS_RE = re.compile(r"@covers\s+([A-Za-z]{1,4}-[0-9]{1,3}[a-z]?)")
CODE_EXT = {".java", ".kt", ".kts", ".ts", ".tsx", ".py"}


def load_index():
    """→ (active: set, retired: set). Source : docs/requirements-index.csv."""
    active, retired = set(), set()
    if INDEX.exists():
        with INDEX.open(encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                idv = (row.get("id") or "").strip()
                if not idv:
                    continue
                if (row.get("statut") or "").strip().upper().startswith("RETIR"):
                    retired.add(idv)
                else:
                    active.add(idv)
    active |= {f"AC-{n:02d}" for n in range(1, 17)}  # cas d'abus (ISC-TM v1.3)
    return active, retired


def git_files():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    return out.stdout.splitlines()


def head_commit():
    out = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return out.stdout.strip() if out.returncode == 0 else ""


def scan():
    """→ (impls: id->set(composants), covers: id->set(tests), erreurs: list)."""
    active, retired = load_index()
    impls, covers, errors = {}, {}, []

    def check(idv, marker, f):
        if idv in retired:
            errors.append(f"{f}: {marker} {idv} — exigence RETIRÉE, marqueur interdit")
        elif idv not in active:
            errors.append(f"{f}: {marker} {idv} — exigence inexistante dans l'index")

    for f in git_files():
        p = ROOT / f
        if p.suffix.lower() not in CODE_EXT or f.startswith("spikes/"):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for idv in IMPLEMENTS_RE.findall(text):
            check(idv, "@implements", f)
            impls.setdefault(idv, set()).add(f)
        for idv in COVERS_RE.findall(text):
            check(idv, "@covers", f)
            covers.setdefault(idv, set()).add(f)
    return impls, covers, errors


def seed_rows():
    if not SEED.exists():
        return []
    with SEED.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def main():
    impls, covers, errors = scan()
    if errors:
        print("porte traceability : ROUGE")
        for e in errors:
            print("  " + e)
        return 1

    commit = head_commit()
    seed = seed_rows()
    rows = list(seed)
    for idv in sorted(set(impls) | set(covers)):
        rows.append(
            {
                "origine": "code",
                "exigence": idv,
                "composant": ";".join(sorted(impls.get(idv, []))),
                "test": ";".join(sorted(covers.get(idv, []))),
                "verdict": "",  # rempli par la CI quand les tests s'exécutent
                "commit": commit,
            }
        )
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in COLUMNS})
    print(
        f"traceability : {len(rows)} ligne(s) écrite(s) dans {OUT.relative_to(ROOT)} "
        f"(dont {len(seed)} attestée(s) manuellement, {len(rows) - len(seed)} depuis marqueurs)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
