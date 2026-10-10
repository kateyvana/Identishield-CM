"""User survey analysis (FR + EN Google Forms exports) — deterministic, standard library only.

Reads the two raw exports (never versioned: data/survey/raw/ is git-ignored), harmonises the
labels through the explicit tables of `labels.py`, and writes AGGREGATES only (counts,
proportions with 95 % Wilson intervals), Markdown tables and SVG figures. No individual answer,
no timestamp is ever written out.

Usage:
    uv run python -m isc_survey.analysis --fr <fr.csv> --en <en.csv> --out-dir <dir>
"""

import argparse
import csv
import json
import random
import statistics
from collections import Counter
from math import sqrt
from pathlib import Path

from isc_survey.labels import LABELS, MULTI, NUMERIC, QUESTIONS, SECOND_HEADER

LANGS = ("fr", "en")
EXPECTED_COLUMNS = 24
ANON_FILE = "reponses_anonymisees.csv"
ANON_SEED = 20261008  # fixed shuffle seed of the de-identified dataset (row order carries nothing)
ANON_COLUMNS = ["lang"] + list(QUESTIONS.values())


class UnknownLabel(ValueError):
    """An answer label absent from the harmonisation table: the script must stop."""


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """95 % Wilson score interval for k successes out of n, as percentages (2 decimals)."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (round(100 * (c - h), 2), round(100 * (c + h), 2))


def proportion(k: int, n: int) -> dict:
    lo, hi = wilson(k, n)
    return {"k": k, "n": n, "pct": round(100 * k / n, 2) if n else 0.0, "wilson95": [lo, hi]}


def _code(question: str, lang: str, raw: str):
    """Harmonised code(s) for one cell; None if unanswered. Unknown label -> UnknownLabel."""
    raw = raw.strip()
    if not raw:
        return None
    if question in NUMERIC:
        if not raw.isdigit() or not 1 <= int(raw) <= 10:
            raise UnknownLabel(f"{question} ({lang}) : valeur hors échelle 1-10 : {raw!r}")
        return int(raw)
    table = LABELS[question][lang]
    parts = [p.strip() for p in raw.split(";")] if question in MULTI else [raw]
    codes = []
    for part in parts:
        if part not in table:
            raise UnknownLabel(f"{question} ({lang}) : libellé non reconnu : {part!r}")
        codes.append(table[part])
    return codes if question in MULTI else codes[0]


def load(path: Path, lang: str) -> tuple[list[dict], int]:
    """Harmonised respondents of one export, and the number of entirely empty submissions."""
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.reader(f))
    header, data = rows[0], rows[1:]
    if len(header) != EXPECTED_COLUMNS or header[1].strip() != SECOND_HEADER[lang]:
        raise ValueError(f"{path} : en-tête inattendu pour la langue {lang!r}")
    respondents, empty = [], 0
    for row in data:
        if len(row) != EXPECTED_COLUMNS:
            raise ValueError(f"{path} : ligne de {len(row)} colonnes (attendu {EXPECTED_COLUMNS})")
        if not any(cell.strip() for cell in row[1:]):
            empty += 1  # entirely empty submission: excluded
            continue
        r = {"lang": lang}
        for col, q in QUESTIONS.items():
            r[q] = _code(q, lang, row[col])
        respondents.append(r)
    return respondents, empty


def _answered(rs: list[dict], q: str) -> list[dict]:
    return [r for r in rs if r[q] is not None]


def distribution(rs: list[dict], q: str) -> dict:
    """Per-code proportions among those who answered q (multi-choice: share of respondents)."""
    ans = _answered(rs, q)
    c: Counter = Counter()
    for r in ans:
        for code in r[q] if q in MULTI else [r[q]]:
            c[code] += 1
    return {"n": len(ans), "codes": {k: proportion(v, len(ans)) for k, v in sorted(c.items())}}


def share(rs: list[dict], q: str, codes: set) -> dict:
    """Proportion of respondents (who answered q) whose answer is in `codes`."""
    ans = _answered(rs, q)
    hit = sum(1 for r in ans if (set(r[q]) & codes if q in MULTI else r[q] in codes))
    return proportion(hit, len(ans))


def by_lang(rs: list[dict], fn) -> dict:
    return {"total": fn(rs), **{lang: fn([r for r in rs if r["lang"] == lang]) for lang in LANGS}}


def analyse(fr: list[dict], en: list[dict]) -> dict:
    rs = fr + en
    victims = [r for r in rs if r["Q11"] in {"self", "close"}]  # Q12 population
    ussd_users = [r for r in rs if r["Q3"] in {"ussd", "both"}]  # Q20 population
    fear = [r["Q14"] for r in rs if r["Q14"] is not None]
    fear_lang = {
        lang: [r["Q14"] for r in rs if r["lang"] == lang and r["Q14"] is not None] for lang in LANGS
    }

    def fear_stats(values: list[int]) -> dict:
        return {
            "n": len(values),
            "median": statistics.median(values),
            "mean": round(statistics.fmean(values), 2),
            "distribution": {str(v): values.count(v) for v in range(1, 11)},
            "share_8_10": proportion(sum(v >= 8 for v in values), len(values)),
            "share_1_3": proportion(sum(v <= 3 for v in values), len(values)),
        }

    out = {
        "n_valid": {"total": len(rs), "fr": len(fr), "en": len(en)},
        "profile": {
            q: by_lang(rs, lambda s, q=q: distribution(s, q))
            for q in ("Q1", "Q2", "Q3", "Q4", "Q5")
        },
        # Q6 is NOT merged: two different questions (FR "ever", EN "per month").
        "Q6_not_merged": {
            "fr_max_balance_ever": distribution(fr, "Q6"),
            "en_max_balance_per_month": distribution(en, "Q6"),
        },
        "awareness": {
            "Q7_distribution": by_lang(rs, lambda s: distribution(s, "Q7")),
            "Q7_never_heard": by_lang(rs, lambda s: share(s, "Q7", {"never"})),
            "Q8_unaware_of_flaw": by_lang(rs, lambda s: share(s, "Q8", {"no"})),
            "Q9_phishing_received": by_lang(rs, lambda s: share(s, "Q9", {"once", "often"})),
            "Q10_relies_on_sms_code": by_lang(rs, lambda s: share(s, "Q10", {"yes"})),
        },
        "severity": {
            "Q11_distribution": by_lang(rs, lambda s: distribution(s, "Q11")),
            # Main indicator: "myself", the only victim option present in BOTH forms.
            "Q11_self_victim": by_lang(rs, lambda s: share(s, "Q11", {"self"})),
            "Q12_population": (
                "victimes déclarées en Q11 : mélange soi-même (FR+EN) et proche (EN seul), "
                "non comparable entre FR et EN ; sert seulement de population à Q12"
            ),
            "Q12_population_n": {
                "total": len(victims),
                **{lang: sum(1 for r in victims if r["lang"] == lang) for lang in LANGS},
            },
            "Q12_distribution_victims": by_lang(victims, lambda s: distribution(s, "Q12")),
            "Q12_not_recovered_victims": by_lang(victims, lambda s: share(s, "Q12", {"no"})),
            "Q13_distribution": by_lang(rs, lambda s: distribution(s, "Q13")),
            "Q13_serious_or_catastrophic": by_lang(
                rs, lambda s: share(s, "Q13", {"serious", "catastrophic"})
            ),
            "Q14_fear": {
                "total": fear_stats(fear),
                **{lang: fear_stats(v) for lang, v in fear_lang.items()},
            },
        },
        "adoption": {
            "Q17_accepts_extra_step": by_lang(
                rs, lambda s: share(s, "Q17", {"yes_always", "yes_if_rare"})
            ),
            "Q17_distribution": by_lang(rs, lambda s: distribution(s, "Q17")),
            "Q18_distribution": by_lang(rs, lambda s: distribution(s, "Q18")),
            "Q20_population": "utilisateurs USSD (Q3 = USSD ou les deux)",
            "Q20_yes_ussd_users": by_lang(ussd_users, lambda s: share(s, "Q20", {"yes"})),
            "Q20_distribution_ussd_users": by_lang(ussd_users, lambda s: distribution(s, "Q20")),
            "Q21_would_install": by_lang(rs, lambda s: share(s, "Q21", {"yes_now", "yes_if_free"})),
            "Q21_distribution": by_lang(rs, lambda s: distribution(s, "Q21")),
            "Q22_note": "question ajoutée le 2026-09-24 en cours de collecte",
            "Q22_would_accept_code": by_lang(
                rs, lambda s: share(s, "Q22", {"yes_keep", "yes_afraid"})
            ),
            "Q22_distribution": by_lang(rs, lambda s: distribution(s, "Q22")),
        },
        "expectations": {
            "Q15_current_measures_sufficient": by_lang(rs, lambda s: share(s, "Q15", {"yes"})),
            "Q16_distribution": by_lang(rs, lambda s: distribution(s, "Q16")),
            "Q19_distribution": by_lang(rs, lambda s: distribution(s, "Q19")),
            "Q23_distribution": by_lang(rs, lambda s: distribution(s, "Q23")),
            "Q23_fear_wrongly_blocked": by_lang(rs, lambda s: share(s, "Q23", {"fear_blocked"})),
        },
    }
    return out


# --- SVG figures (hand-written, deterministic) ----------------------------------------------


def _svg_open(width: int, height: int) -> list[str]:
    """Opening tag and white background shared by every figure."""
    return [
        (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" font-family="sans-serif" font-size="13">'
        ),
        f'<rect width="{width}" height="{height}" fill="#ffffff"/>',
    ]


def bar_chart_svg(title: str, items: list[tuple[str, dict]], width: int = 720) -> str:
    """Horizontal bars (proportion) with 95 % Wilson whiskers; items = (label, proportion())."""
    left, right, row_h, top = 300, 60, 34, 46
    plot_w = width - left - right
    height = top + row_h * len(items) + 30

    def x(pct: float) -> float:
        return left + plot_w * pct / 100

    esc = lambda s: s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    out = [
        *_svg_open(width, height),
        f'<text x="12" y="24" font-size="15" font-weight="bold">{esc(title)}</text>',
    ]
    for t in (0, 25, 50, 75, 100):
        out.append(
            f'<line x1="{x(t):.1f}" y1="{top - 6}" x2="{x(t):.1f}" '
            f'y2="{height - 24}" stroke="#dde3ea"/>'
        )
        out.append(
            f'<text x="{x(t):.1f}" y="{height - 8}" text-anchor="middle" '
            f'fill="#5b6672">{t} %</text>'
        )
    for i, (label, p) in enumerate(items):
        y = top + i * row_h
        lo, hi = p["wilson95"]
        out.append(f'<text x="{left - 10}" y="{y + 17}" text-anchor="end">{esc(label)}</text>')
        out.append(
            f'<rect x="{left}" y="{y + 5}" width="{x(p["pct"]) - left:.1f}" height="18" '
            f'fill="#2f6f9f"/>'
        )
        out.append(
            f'<line x1="{x(lo):.1f}" y1="{y + 14}" x2="{x(hi):.1f}" y2="{y + 14}" '
            f'stroke="#1b1f24" stroke-width="1.5"/>'
        )
        for v in (lo, hi):
            out.append(
                f'<line x1="{x(v):.1f}" y1="{y + 9}" x2="{x(v):.1f}" y2="{y + 19}" '
                f'stroke="#1b1f24" stroke-width="1.5"/>'
            )
        out.append(
            f'<text x="{x(hi) + 6:.1f}" y="{y + 18}" fill="#1b1f24">'
            f"{p['pct']:.1f} % ({p['k']}/{p['n']})</text>"
        )
    out.append("</svg>")
    return "\n".join(out) + "\n"


def histogram_svg(title: str, distribution: dict[str, int], width: int = 720) -> str:
    """Vertical histogram of the 1-10 fear scale (counts)."""
    left, top, bottom, height = 50, 46, 40, 300
    plot_h = height - top - bottom
    bw = (width - left - 20) / 10
    peak = max(distribution.values()) or 1
    out = [
        *_svg_open(width, height),
        f'<text x="12" y="24" font-size="15" font-weight="bold">{title}</text>',
    ]
    for i in range(10):
        n = distribution[str(i + 1)]
        h = plot_h * n / peak
        x0 = left + i * bw
        out.append(
            f'<rect x="{x0 + 4:.1f}" y="{top + plot_h - h:.1f}" width="{bw - 8:.1f}" '
            f'height="{h:.1f}" fill="{"#b5452f" if i >= 7 else "#2f6f9f"}"/>'
        )
        out.append(
            f'<text x="{x0 + bw / 2:.1f}" y="{top + plot_h - h - 6:.1f}" '
            f'text-anchor="middle">{n}</text>'
        )
        out.append(
            f'<text x="{x0 + bw / 2:.1f}" y="{height - 18}" '
            f'text-anchor="middle" fill="#5b6672">{i + 1}</text>'
        )
    out.append("</svg>")
    return "\n".join(out) + "\n"


def figures(a: dict) -> dict[str, str]:
    aw, sv, ad, ex = a["awareness"], a["severity"], a["adoption"], a["expectations"]
    return {
        "fig_axe1_conscience.svg": bar_chart_svg(
            "Axe 1 — Conscience du risque (IC Wilson 95 %)",
            [
                ("Q7 jamais entendu parler du SIM swap", aw["Q7_never_heard"]["total"]),
                ("Q8 ignorait la faille", aw["Q8_unaware_of_flaw"]["total"]),
                ("Q9 a reçu un message d'hameçonnage", aw["Q9_phishing_received"]["total"]),
                ("Q10 compte sur le code SMS", aw["Q10_relies_on_sms_code"]["total"]),
            ],
        ),
        "fig_axe2_gravite.svg": bar_chart_svg(
            "Axe 2 — Gravité perçue (IC Wilson 95 %)",
            [
                ("Q11 victime : moi-même", sv["Q11_self_victim"]["total"]),
                ("Q12 non récupéré (victimes)", sv["Q12_not_recovered_victims"]["total"]),
                ("Q13 grave ou catastrophique", sv["Q13_serious_or_catastrophic"]["total"]),
                ("Q14 peur 8–10 / 10", sv["Q14_fear"]["total"]["share_8_10"]),
            ],
        ),
        "fig_q14_peur.svg": histogram_svg(
            "Q14 — Peur de l'arnaque (1–10), effectifs ; 8–10 en rouge",
            sv["Q14_fear"]["total"]["distribution"],
        ),
        "fig_axe3_adoption.svg": bar_chart_svg(
            "Axe 3 — Volonté d'adopter (IC Wilson 95 %)",
            [
                ("Q17 accepte une vérification", ad["Q17_accepts_extra_step"]["total"]),
                ("Q20 oui (utilisateurs USSD)", ad["Q20_yes_ussd_users"]["total"]),
                ("Q21 installerait l'application", ad["Q21_would_install"]["total"]),
                ("Q22 accepterait le code (n réduit)", ad["Q22_would_accept_code"]["total"]),
            ],
        ),
        "fig_axe4_attentes.svg": bar_chart_svg(
            "Axe 4 — Attentes (IC Wilson 95 %)",
            [
                (
                    "Q15 mesures actuelles suffisantes",
                    ex["Q15_current_measures_sufficient"]["total"],
                ),
                ("Q16 système très utile", ex["Q16_distribution"]["total"]["codes"]["very"]),
                ("Q19 préfère l'agence", ex["Q19_distribution"]["total"]["codes"]["branch"]),
                ("Q23 crainte d'être bloqué à tort", ex["Q23_fear_wrongly_blocked"]["total"]),
            ],
        ),
    }


# --- De-identified dataset -----------------------------------------------------------------


def _known_codes(question: str) -> set[str]:
    return {code for table in LABELS[question].values() for code in table.values()}


def write_anonymised(rs: list[dict], path: Path) -> None:
    """Harmonised codes + language only: no timestamp, rows shuffled with a fixed seed."""
    rows = list(rs)
    random.Random(ANON_SEED).shuffle(rows)
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(ANON_COLUMNS)
        for r in rows:
            cells = [r["lang"]]
            for q in QUESTIONS.values():
                v = r[q]
                cells.append("" if v is None else ";".join(v) if q in MULTI else str(v))
            w.writerow(cells)


def load_anonymised(path: Path) -> list[dict]:
    """Read the de-identified dataset back; any unknown column or code is an error."""
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        if next(reader) != ANON_COLUMNS:
            raise ValueError(f"{path} : colonnes inattendues")
        rs = []
        for row in reader:
            if row[0] not in LANGS:
                raise UnknownLabel(f"langue inconnue : {row[0]!r}")
            r = {"lang": row[0]}
            for q, cell in zip(QUESTIONS.values(), row[1:], strict=True):
                if not cell:
                    r[q] = None
                elif q in NUMERIC:
                    r[q] = _code(q, r["lang"], cell)
                else:
                    codes = cell.split(";") if q in MULTI else [cell]
                    unknown = set(codes) - _known_codes(q)
                    if unknown:
                        raise UnknownLabel(f"{q} : code inconnu {sorted(unknown)}")
                    r[q] = codes if q in MULTI else codes[0]
            rs.append(r)
    return rs


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--fr", type=Path, required=True)
    ap.add_argument("--en", type=Path, required=True)
    ap.add_argument("--out-dir", type=Path, required=True)
    args = ap.parse_args(argv)
    fr, empty_fr = load(args.fr, "fr")
    en, empty_en = load(args.en, "en")
    result = analyse(fr, en)
    result["excluded_empty_submissions"] = {"fr": empty_fr, "en": empty_en}
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "agregats_sondage_v1.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    for name, svg in figures(result).items():
        (args.out_dir / name).write_text(svg, encoding="utf-8", newline="\n")
    write_anonymised(fr + en, args.out_dir / ANON_FILE)
    print(f"OK : {result['n_valid']['total']} répondants valides -> {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
