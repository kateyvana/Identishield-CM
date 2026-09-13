"""Porte secrets (ENF-07) : aucun secret dans les fichiers suivis par git.

Fail-closed (TACHE-01 correction 4) : les NOMS de tous les fichiers suivis sont
controles ; le CONTENU de tous les fichiers suivis est scanne quelle que soit
l'extension, sauf binaires (liste noire + octet nul).

Perimetre : l'arbre de travail suivi, pas l'historique complet. Decision du
proprietaire (2026-08-26) : l'exigence d'ISC-DEV §4 (« dans l'historique »)
sera reformulee en v1.1 ; un scan d'historique exigerait un outil hors stack
(gitleaks) et donc un ADR.
"""

import re
import subprocess
import sys
from pathlib import Path

FORBIDDEN_NAMES = re.compile(r"(^|/)\.env$|\.(pem|key|jks|keystore|p12|pfx)$")
CONTENT_PATTERNS = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "bloc de cle privee"),
    (
        # Un secret EN DUR : `password = "..."` avec une valeur litterale. La valeur NE DOIT PAS
        # etre une REFERENCE de variable (`"$VAR"`, `"${VAR}"`, `"%VAR%"`) : une reference n'est
        # jamais un secret en dur (ordre du proprietaire, 2026-08-28 : affiner le detecteur, pas
        # tordre le code). Ex. legitime : `PGPASSWORD="$POSTGRES_PASSWORD"`.
        re.compile(r"(?i)(password|passwd|secret|token)\s*[:=]\s*['\"](?![$%])[^'\"\s]{8,}['\"]"),
        "secret potentiel en dur",
    ),
]
BINARY_EXT = {
    ".jar",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".ico",
    ".pdf",
    ".zip",
    ".docx",
    ".xlsx",
    ".pptx",
    ".woff",
    ".woff2",
    ".ttf",
    ".eot",
    ".class",
    ".exe",
    ".dll",
    ".so",
    ".dylib",
    ".bin",
    ".parquet",
}


def main() -> int:
    files = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, check=True
    ).stdout.splitlines()
    errors = []
    for f in files:
        if FORBIDDEN_NAMES.search(f):
            errors.append(f"fichier au nom sensible suivi par git : {f}")
    for f in files:
        p = Path(f)
        if p.suffix.lower() in BINARY_EXT or not p.is_file():
            continue
        try:
            head = p.read_bytes()[:8192]
        except OSError:
            continue
        if b"\x00" in head:
            continue
        text = p.read_text(encoding="utf-8", errors="ignore")
        for pattern, label in CONTENT_PATTERNS:
            for m in pattern.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                errors.append(f"{f}:{line}: {label}")
    if errors:
        print("porte secrets : ROUGE")
        for e in errors:
            print("  " + e)
        return 1
    print("porte secrets : VERT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
