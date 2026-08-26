"""Porte fictitious-msisdn (CT-02, ED-09) : aucun MSISDN hors 699000000-699009999.

Scanne les fichiers texte suivis par git, hors docs/ (les specifications citent
la regle et la plage) et hors ce script lui-meme.
"""
import re
import subprocess
import sys
from pathlib import Path

MSISDN = re.compile(r"(?<!\d)(6\d{8})(?!\d)")
LOW, HIGH = 699000000, 699009999
TEXT_EXT = {".java", ".py", ".ts", ".tsx", ".js", ".sh", ".yml", ".yaml", ".sql",
            ".json", ".properties", ".kts", ".gradle", ".toml", ".cfg", ".csv", ".md"}
EXCLUDED = ("docs/", "tools/check_msisdn.py")

def main() -> int:
    files = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                           check=True).stdout.splitlines()
    errors = []
    for f in files:
        if f.startswith(EXCLUDED[0]) or f == EXCLUDED[1]:
            continue
        p = Path(f)
        if p.suffix.lower() not in TEXT_EXT or not p.is_file():
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for m in MSISDN.finditer(text):
            n = int(m.group(1))
            if not (LOW <= n <= HIGH):
                line = text.count("\n", 0, m.start()) + 1
                errors.append(f"{f}:{line}: MSISDN hors plage fictive : {m.group(1)}")
    if errors:
        print("porte fictitious-msisdn : ROUGE")
        for e in errors:
            print("  " + e)
        return 1
    print("porte fictitious-msisdn : VERT")
    return 0

if __name__ == "__main__":
    sys.exit(main())
