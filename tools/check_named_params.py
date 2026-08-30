"""Porte #11 (ISC-DEV, ES-15/EF-17) : aucun paramètre nommé en littéral dans le code.

Les paramètres nommés (ISC-TM §7) vivent dans UN seul endroit versionné,
`config/ruleset.yaml` ; le code de production les lit, ne les code jamais en dur.
Cette porte lit les valeurs DISTINCTIVES du jeu de règles et échoue si l'une
d'elles réapparaît comme littéral dans le code de production — signe qu'un seuil
a été codé en dur au lieu d'être chargé de la configuration.

« Vraie défense = paramètres à un seul endroit » : la porte est un filet
(liste noire), la garantie est structurelle. On ne compare que les valeurs
distinctives — durées ISO-8601, fenêtre horaire, entiers >= 1000 — pour éviter
les faux positifs sur des petits nombres ubiquitaires (3, 10, 24…).

Périmètre : fichiers de code suivis par git (.java/.kt/.kts/.ts/.tsx), hors
tests, hors `spikes/`, hors `config/`, hors `tools/`. Fail-closed.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULESET = ROOT / "config" / "ruleset.yaml"
CODE_EXT = {".java", ".kt", ".kts", ".ts", ".tsx"}

# Un fichier est un test s'il vit dans un dossier de test ou finit par Test/Spec.
TEST_MARKERS = ("/test/", "/tests/")


def distinctive_values() -> list[str]:
    """Valeurs distinctives du jeu de règles à ne jamais coder en dur."""
    text = RULESET.read_text(encoding="utf-8")
    values: set[str] = set()
    values.update(re.findall(r"PT\d+[HMS](?:\d+[MS])?", text))  # durées ISO-8601
    values.update(re.findall(r"\d{2}:\d{2}-\d{2}:\d{2}", text))  # fenêtre horaire (NIGHT)
    values.update(m for m in re.findall(r"\b\d{4,}\b", text))  # entiers >= 1000
    return sorted(values)


def git_code_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    files = []
    for f in out.stdout.splitlines():
        p = Path(f)
        if p.suffix.lower() not in CODE_EXT:
            continue
        low = "/" + f.lower()
        if any(m in low for m in TEST_MARKERS):
            continue
        if f.startswith(("spikes/", "config/", "tools/")):
            continue
        if p.name.endswith(("Test.java", "Test.kt", "Spec.ts")):
            continue
        files.append(f)
    return files


def main() -> int:
    if not RULESET.exists():
        print("porte parametres-nommes : ROUGE")
        print(f"  {RULESET.relative_to(ROOT)} introuvable")
        return 1

    values = distinctive_values()
    patterns = [(v, re.compile(r"(?<![\w.])" + re.escape(v) + r"(?![\w.])")) for v in values]
    errors = []
    for f in git_code_files():
        try:
            text = (ROOT / f).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for value, pat in patterns:
                if pat.search(line):
                    errors.append(f"{f}:{lineno}: valeur de paramètre nommé en dur « {value} »")

    if errors:
        print("porte parametres-nommes : ROUGE")
        print("  Ces valeurs appartiennent a config/ruleset.yaml, pas au code :")
        for e in errors:
            print("  " + e)
        return 1
    print(f"porte parametres-nommes : VERT ({len(values)} valeurs distinctives surveillees)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
