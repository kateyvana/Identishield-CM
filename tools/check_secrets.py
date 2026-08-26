"""Porte secrets (ENF-07) : aucun secret dans les fichiers suivis par git.

Verifie (1) qu'aucun fichier au nom sensible n'est suivi, (2) qu'aucun bloc de
cle privee ni mot de passe en dur n'apparait dans un fichier texte suivi.

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
    (re.compile(r"(?i)(password|passwd|secret|token)\s*[:=]\s*['\"][^'\"\s]{8,}['\"]"),
     "secret potentiel en dur"),
]
TEXT_EXT = {".java", ".py", ".ts", ".tsx", ".js", ".sh", ".yml", ".yaml", ".sql",
            ".md", ".json", ".properties", ".kts", ".gradle", ".toml", ".cfg", ".csv", ".txt"}

def main() -> int:
    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                           check=True).stdout.splitlines()
    errors = []
    for f in files:
        if FORBIDDEN_NAMES.search(f):
            errors.append(f"fichier au nom sensible suivi par git : {f}")
    for f in files:
        p = Path(f)
        if p.suffix.lower() not in TEXT_EXT or not p.is_file():
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
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
