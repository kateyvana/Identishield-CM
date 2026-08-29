"""Génère docs/traceability.csv à partir des marqueurs de traçabilité du code.

Convention (CLAUDE.md §5) :
  - `@implements <ID>` dans le code de production → le composant qui réalise
    l'exigence.
  - `@covers <ID>` dans un test → le test qui vérifie l'exigence / le cas d'abus.

Source de vérité des identifiants valides : le SRS (docs/02-specifications/
ISC-SRS-*.docx) pour les exigences, et le modèle de menace (02-modele-de-
menace-v1.1.md) pour les cas d'abus AC-xx. PORTE : un marqueur référençant un
identifiant inexistant fait échouer la génération (sortie non nulle).

Les lignes attestées manuellement (aujourd'hui : les cinq spikes) vivent dans
docs/traceability-seed.csv et sont fusionnées telles quelles ; elles ne sont
PAS des marqueurs de code et ne sont donc pas soumises à la porte.

Sur le dépôt actuel (squelette sans marqueurs), la sortie est quasi vide, hormis
les lignes de spikes — c'est attendu et correct.

Limite connue et signalée : l'extraction des identifiants depuis le .docx du SRS
est imparfaite (les identifiants sont éclatés dans des tableaux). Elle capture
~40-50 des 117 exigences nominales. Tant qu'aucun marqueur n'existe, la porte ne
mord pas ; avant le mois 4, produire une source machine fiable des exigences
(index versionné, ou SRS converti) pour que la porte ne rejette pas à tort une
exigence valide non captée.
"""
import csv
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "docs" / "02-specifications"
SEED = ROOT / "docs" / "traceability-seed.csv"
OUT = ROOT / "docs" / "traceability.csv"
COLUMNS = ["origine", "exigence", "composant", "test", "verdict", "commit"]

REQ_RE = re.compile(r"(?:EF|ES|ENF|EAR|EI|ED)[-‐‑‒–—][0-9]{1,3}[a-z]?")
AC_RE = re.compile(r"AC-[0-9]{2}")
IMPLEMENTS_RE = re.compile(r"@implements\s+([A-Za-z]{1,4}[-‐‑‒–—][0-9]{1,3}[a-z]?)")
COVERS_RE = re.compile(r"@covers\s+([A-Za-z]{1,4}[-‐‑‒–—][0-9]{1,3}[a-z]?)")

CODE_EXT = {".java", ".kt", ".kts", ".ts", ".tsx", ".py"}


def norm(idv):
    """Normalise le tiret en tiret ASCII pour comparaison stable."""
    return re.sub(r"[‐‑‒–—]", "-", idv)


def valid_ids():
    ids = set()
    for docx in SPEC.glob("ISC-SRS-*.docx"):
        try:
            xml = zipfile.ZipFile(docx).read("word/document.xml").decode("utf-8")
        except OSError:
            continue
        txt = re.sub(r"<[^>]+>", "", xml)
        ids |= {norm(m) for m in REQ_RE.findall(txt)}
    tm = SPEC / "02-modele-de-menace-v1.1.md"
    if tm.exists():
        ids |= set(AC_RE.findall(tm.read_text(encoding="utf-8", errors="ignore")))
    ids |= {f"AC-{n:02d}" for n in range(1, 14)}  # AC-01..13 (ISC-TM)
    return ids


def git_files():
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    return out.stdout.splitlines()


def head_commit():
    out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else ""


def scan():
    """→ (impls: id->set(composants), covers: id->set(tests), erreurs: list)."""
    valid = valid_ids()
    impls, covers, errors = {}, {}, []
    for f in git_files():
        p = ROOT / f
        if p.suffix.lower() not in CODE_EXT or "/spikes/" in "/" + f or f.startswith("spikes/"):
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        is_test = "test" in f.lower()
        for idv in (norm(x) for x in IMPLEMENTS_RE.findall(text)):
            if idv not in valid:
                errors.append(f"{f}: @implements {idv} — exigence inexistante dans le SRS")
            impls.setdefault(idv, set()).add(f)
        for idv in (norm(x) for x in COVERS_RE.findall(text)):
            if idv not in valid:
                errors.append(f"{f}: @covers {idv} — exigence/cas d'abus inexistant")
            covers.setdefault(idv, set()).add(f)
            _ = is_test  # (le chemin distingue test/prod ; réservé à un durcissement futur)
    return impls, covers, errors


def seed_rows():
    if not SEED.exists():
        return []
    with SEED.open(encoding="utf-8", newline="") as fh:
        return [row for row in csv.DictReader(fh)]


def main():
    impls, covers, errors = scan()
    if errors:
        print("porte traceability : ROUGE")
        for e in errors:
            print("  " + e)
        return 1

    commit = head_commit()
    rows = list(seed_rows())
    for idv in sorted(set(impls) | set(covers)):
        rows.append({
            "origine": "code",
            "exigence": idv,
            "composant": ";".join(sorted(impls.get(idv, []))),
            "test": ";".join(sorted(covers.get(idv, []))),
            "verdict": "",          # rempli par la CI quand les tests s'exécutent
            "commit": commit,
        })
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in COLUMNS})
    print(f"traceability : {len(rows)} ligne(s) écrite(s) dans {OUT.relative_to(ROOT)} "
          f"(dont {len(seed_rows())} attestée(s) manuellement, {len(rows)-len(seed_rows())} depuis marqueurs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
