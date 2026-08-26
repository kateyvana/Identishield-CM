"""Porte check-docs (TACHE-00 tache 7 ; ISC-DEV §4, SRS §16).

Trois verifications sur docs/ :
1. tout identifiant ADR-xxx cite possede un fichier docs/01-decisions/ADR-xxx.md ;
2. aucun identifiant d'exigence RETIREE (liste : ES-01, ES-16 et leurs formes
   anglaises SR-01, SR-16) n'est cite ailleurs que sur sa ligne de retrait ;
3. les ensembles d'identifiants d'exigences des SRS FR et EN sont identiques
   sous le mapping EF->FR, ES->SR, ENF->NFR, EAR->RAR, ED->DR, EI->IR.

Les .docx sont convertis en texte via zipfile (bibliotheque standard seule).
"""

import re
import sys
import zipfile
from pathlib import Path

DOCS = Path("docs")
ADR_DIR = DOCS / "01-decisions"
SRS_FR = DOCS / "02-specifications" / "ISC-SRS-FR_Cahier_des_charges_v1.2.docx"
SRS_EN = (
    DOCS / "02-specifications" / "ISC-SRS-EN_Software_Requirements_Specification_v1.2.docx"
)

# Identifiants retires (SRS §16). ES-01b etc. restent valides : la frontiere
# interdit un caractere alphanumerique apres le numero.
RETIRED = ["ES-01", "ES-16", "SR-01", "SR-16"]
RETIREMENT_MARKERS = ("RETIRÉE", "RETIREE", "RETIRED", "WITHDRAWN")

# Fichiers historiques anterieurs au retrait, qui le documentent ou le testent.
# Toute NOUVELLE citation d'un identifiant retire ailleurs reste une erreur.
RETIRED_ALLOWLIST = {
    Path("docs/03-revues/03-journal-defauts-SRS-v1.1.md"),
    Path("docs/03-revues/TACHE-00_Mise_en_place_depot.md"),
}

PREFIX_MAP = {"EF": "FR", "ES": "SR", "ENF": "NFR", "EAR": "RAR", "ED": "DR", "EI": "IR"}


def docx_to_text(path: Path) -> str:
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf-8")
    xml = re.sub(r"</w:p>", "\n", xml)
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    xml = re.sub(r"<w:br[^>]*/>", "\n", xml)
    text = re.sub(r"<[^>]+>", "", xml)
    return (
        text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
    )


def iter_doc_texts():
    """Yield (path, text) for every analysable document under docs/."""
    for p in sorted(DOCS.rglob("*")):
        if not p.is_file():
            continue
        if p.suffix == ".md" or p.suffix == ".csv":
            yield p, p.read_text(encoding="utf-8", errors="ignore")
        elif p.suffix == ".docx":
            try:
                yield p, docx_to_text(p)
            except (OSError, KeyError, zipfile.BadZipFile) as exc:
                yield p, f"__UNREADABLE__ {exc}"


def check_adr_references(texts) -> list[str]:
    errors = []
    pattern = re.compile(r"\bADR-(\d{3})\b")
    for path, text in texts:
        for num in sorted(set(pattern.findall(text))):
            target = ADR_DIR / f"ADR-{num}.md"
            if not target.exists():
                errors.append(f"{path}: cite ADR-{num} mais {target} n'existe pas")
    return errors


def check_retired_ids(texts) -> list[str]:
    errors = []
    patterns = [(rid, re.compile(re.escape(rid) + r"(?![0-9A-Za-z])")) for rid in RETIRED]
    for path, text in texts:
        if path in RETIRED_ALLOWLIST:
            continue
        lines = text.splitlines()
        for lineno, line in enumerate(lines, start=1):
            # Ligne de retrait : marqueur sur la ligne elle-meme, ou sur la
            # suivante (dans un tableau .docx converti, la cellule ID et la
            # cellule "RETIREE ..." deviennent deux lignes adjacentes).
            window = line.upper()
            if lineno < len(lines):
                window += " " + lines[lineno].upper()
            if any(marker in window for marker in RETIREMENT_MARKERS):
                continue
            for rid, pat in patterns:
                if pat.search(line):
                    errors.append(
                        f"{path}:{lineno}: cite l'identifiant retiré {rid} "
                        f"hors de sa ligne de retrait"
                    )
    return errors


def extract_ids(text: str, prefixes) -> set[str]:
    """IDs d'exigences DEFINIS : l'identifiant seul sur sa ligne (cellule de
    tableau du SRS converti). Les simples mentions dans le corps du texte ou
    l'historique des revisions ne definissent pas une exigence."""
    ids = set()
    pat = re.compile(r"^(" + "|".join(prefixes) + r")-(\d+[a-z]?)$")
    for line in text.splitlines():
        m = pat.match(line.strip())
        if m:
            ids.add(f"{m.group(1)}-{m.group(2)}")
    return ids


def check_fr_en_parity() -> list[str]:
    errors = []
    for p in (SRS_FR, SRS_EN):
        if not p.exists():
            return [f"document SRS introuvable : {p}"]
    fr_text = docx_to_text(SRS_FR)
    en_text = docx_to_text(SRS_EN)
    fr_ids = extract_ids(fr_text, PREFIX_MAP.keys())
    en_ids_raw = extract_ids(en_text, PREFIX_MAP.values())
    reverse = {v: k for k, v in PREFIX_MAP.items()}
    en_ids = {f"{reverse[i.split('-')[0]]}-{i.split('-')[1]}" for i in en_ids_raw}
    for missing in sorted(fr_ids - en_ids):
        errors.append(f"SRS: {missing} present dans la version FR, absent de la version EN")
    for missing in sorted(en_ids - fr_ids):
        errors.append(f"SRS: {missing} present dans la version EN, absent de la version FR")
    return errors


def main() -> int:
    texts = list(iter_doc_texts())
    unreadable = [str(p) for p, t in texts if t.startswith("__UNREADABLE__")]
    errors = []
    if unreadable:
        errors.extend(f"document illisible : {p}" for p in unreadable)
    texts = [(p, t) for p, t in texts if not t.startswith("__UNREADABLE__")]
    errors.extend(check_adr_references(texts))
    errors.extend(check_retired_ids(texts))
    errors.extend(check_fr_en_parity())
    if errors:
        print("porte check-docs : ROUGE")
        for e in errors:
            print("  " + e)
        return 1
    print("porte check-docs : VERT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
