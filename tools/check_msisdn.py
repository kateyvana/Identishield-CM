"""Porte fictitious-msisdn (CT-02, ED-09, ADR-035) : identifiants de l'operateur simule.

Deux regles, un seul operateur simule (MTN Cameroun, ADR-035) :
  1. aucun MSISDN hors 677000000-677009999 (prefixe MTN 67x) ;
  2. tout IMSI camerounais (624 + 12 chiffres) est MTN : 624-01.
Avant ADR-035 la plage etait 699000000-699009999, un prefixe ORANGE (69x) associe
a un IMSI MTN : incoherence. Elle n'est toleree que dans spikes/, preuves figees
et datees, jamais importees par la production (CLAUDE.md §4) ; partout ailleurs
un 699xxxxxx fait passer la porte au ROUGE.

Fail-closed (TACHE-01 correction 4) : scanne TOUS les fichiers suivis par git,
quelle que soit leur extension, sauf les binaires (liste noire d'extensions +
detection d'octet nul). Exclusions : docs/ (les specifications citent la regle
et la plage) et ce script lui-meme.
"""

import re
import subprocess
import sys
from pathlib import Path

# Frontieres alphanumeriques strictes : une sous-chaine de hash hexadecimal
# (ex. "e693786595c" dans un uv.lock) n'est pas un MSISDN.
MSISDN = re.compile(r"(?<![0-9A-Za-z])(6\d{8})(?![0-9A-Za-z])")
LOW, HIGH = 677000000, 677009999
LEGACY_LOW, LEGACY_HIGH = 699000000, 699009999  # avant ADR-035 ; toleree dans spikes/ seulement
LEGACY_PREFIX = "spikes/"
# IMSI = MCC 624 (Cameroun) + MNC 2 chiffres + MSIN 10 chiffres ; operateur simule = MNC 01.
IMSI = re.compile(r"(?<![0-9A-Za-z])(624\d{12})(?![0-9A-Za-z])")
IMSI_OPERATOR = "62401"
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
EXCLUDED_PREFIX = "docs/"
EXCLUDED_SELF = "tools/check_msisdn.py"


def tracked_text_files():
    files = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, check=True
    ).stdout.splitlines()
    for f in files:
        if f.startswith(EXCLUDED_PREFIX) or f == EXCLUDED_SELF:
            continue
        p = Path(f)
        if p.suffix.lower() in BINARY_EXT or not p.is_file():
            continue
        try:
            head = p.read_bytes()[:8192]
        except OSError:
            continue
        if b"\x00" in head:
            continue  # binaire non liste : ignore comme binaire, pas comme texte
        yield f, p


def main() -> int:
    errors = []
    for f, p in tracked_text_files():
        text = p.read_text(encoding="utf-8", errors="ignore")
        for m in MSISDN.finditer(text):
            n = int(m.group(1))
            legacy = f.startswith(LEGACY_PREFIX) and LEGACY_LOW <= n <= LEGACY_HIGH
            if not (LOW <= n <= HIGH) and not legacy:
                line = text.count("\n", 0, m.start()) + 1
                errors.append(f"{f}:{line}: MSISDN hors plage fictive : {m.group(1)}")
        for m in IMSI.finditer(text):
            if not m.group(1).startswith(IMSI_OPERATOR):
                line = text.count("\n", 0, m.start()) + 1
                errors.append(f"{f}:{line}: IMSI hors operateur simule (624-01) : {m.group(1)}")
    if errors:
        print("porte fictitious-msisdn : ROUGE")
        for e in errors:
            print("  " + e)
        return 1
    print("porte fictitious-msisdn : VERT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
