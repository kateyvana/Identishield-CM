#!/usr/bin/env python3
"""T3-01 / T3-05a — Profil de référence des transactions réelles (ÉTALON du Mois 3), v1.1.

Lit data/real/cleaned_transactions__1_.csv (CONFIDENTIEL, jamais versionné) et produit DEUX
artefacts figés, eux versionnés (aucune ligne brute, aucun montant individuel, aucun identifiant) :
  - docs/03-donnees/profil_reel_v1.1.json  (chiffres lisibles machine, comparaison auto en T3-06)
  - docs/03-donnees/PROFIL_REEL_v1.1.md    (mêmes chiffres en tableaux)

v1.1 (T3-05a) = sections a à e INCHANGÉES par rapport à v1 + section f (agrégats ajoutés) :
masses ponctuelles de montant et nœuds du reste, rythme monétaire par usager, regroupement en
sessions, vélocité par périmètre, fenêtre d'observation. Les fichiers v1 (profil_reel_v1.json,
PROFIL_REEL_v1.md) restent l'étalon figé de T3-01 et ne sont plus réécrits.

DÉTERMINISTE : relancer le script redonne un JSON identique. Aucun horodatage de run n'est écrit ;
la date de l'artefact (DATE) et la version (VERSION) sont figées. Stdlib uniquement (reproductible).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from datetime import UTC, datetime
from itertools import pairwise
from pathlib import Path

# --- Métadonnées FIGÉES de l'artefact v1 (pas de datetime.now() : déterminisme). ------------------
VERSION = "v1.1"
DATE = "2026-09-28"
SOURCE_NAME = "cleaned_transactions__1_.csv"

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "data" / "real" / SOURCE_NAME
OUT_JSON = ROOT / "docs" / "03-donnees" / "profil_reel_v1.1.json"
OUT_MD = ROOT / "docs" / "03-donnees" / "PROFIL_REEL_v1.1.md"

CAP_PROB = 25000  # futur seuil (FCFA) : part des sorties >= CAP_PROB.
NIGHT_HOURS = [23, 0, 1, 2, 3, 4]  # fenêtre NUIT 23h–05h (23:00–04:59).
VELOCITY_N = 3  # « dépasser 3 transactions » => >3, soit >=4 dans la fenêtre.
VELOCITY_WINDOW_S = 600  # 10 minutes.
POINT_MASSES_K = 20  # v1.1 : nombre de masses ponctuelles de montant publiées (agrégats).
SESSION_GAPS_S = (60, 600)  # v1.1 : seuils d'écart entre transactions consécutives d'un usager.

# Tranches de montant lisibles (bornes hautes exclusives ; dernière = +inf).
AMOUNT_BINS = [
    ("0", 0, 0),
    ("1–500", 1, 500),
    ("500–1k", 500, 1000),
    ("1k–5k", 1000, 5000),
    ("5k–10k", 5000, 10000),
    ("10k–25k", 10000, 25000),
    ("25k–50k", 25000, 50000),
    ("50k–100k", 50000, 100000),
    ("100k–500k", 100000, 500000),
    ("500k+", 500000, float("inf")),
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def percentile(sorted_vals: list[float], q: float) -> float:
    """Quantile déterministe (interpolation linéaire, style numpy). q dans [0,100]."""
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return float(sorted_vals[0])
    rank = (q / 100.0) * (len(sorted_vals) - 1)
    lo = int(rank)
    frac = rank - lo
    if lo + 1 >= len(sorted_vals):
        return float(sorted_vals[-1])
    return float(sorted_vals[lo] + (sorted_vals[lo + 1] - sorted_vals[lo]) * frac)


def r2(x: float) -> float:
    return round(x, 2)


def stats_amounts(vals: list[float]) -> dict:
    s = sorted(vals)
    n = len(s)
    if n == 0:
        return {"n": 0}
    quant = {f"p{q}": r2(percentile(s, q)) for q in (10, 25, 50, 75, 90, 95, 99)}
    return {
        "n": n,
        "min": r2(s[0]),
        "max": r2(s[-1]),
        "mean": r2(sum(s) / n),
        "median": r2(percentile(s, 50)),
        "quantiles": quant,
    }


def r4(x: float) -> float:
    return round(x, 4)


def velocity(times_by_user: dict[str, list[float]]) -> dict:
    """Usagers dépassant VELOCITY_N transactions dans une fenêtre glissante de VELOCITY_WINDOW_S."""
    flagged = 0
    for times in times_by_user.values():
        ts = sorted(times)
        j = 0
        for i in range(len(ts)):
            while ts[i] - ts[j] > VELOCITY_WINDOW_S:
                j += 1
            if i - j + 1 > VELOCITY_N:
                flagged += 1
                break
    n = len(times_by_user)
    return {
        "usagers_depassant": flagged,
        "nb_usagers": n,
        "part": r2(100 * flagged / n) if n else 0.0,
    }


def v1_1_aggregates(
    amounts_pos: list[float],
    per_user_times: dict[str, list[float]],
    mon_times: dict[str, list[float]],
    out_times: dict[str, list[float]],
) -> dict:
    """Section f (v1.1, T3-05a) : agrégats seulement, aucune ligne ni identifiant."""
    # Montants : les POINT_MASSES_K valeurs exactes les plus fréquentes (ordre déterministe :
    # fréquence décroissante puis montant), et les nœuds du reste (montants hors de ces valeurs).
    counts = Counter(amounts_pos)
    masses = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:POINT_MASSES_K]
    npos = len(amounts_pos)
    mass_values = {v for v, _ in masses}
    resid = sorted(a for a in amounts_pos if a not in mass_values)
    resid_knots = [
        {"p": p / 100, "montant": r4(percentile(resid, p))}
        for p in (0, 10, 25, 50, 75, 90, 95, 99, 100)
    ]
    # Rythme monétaire par usager (usagers ayant >= 1 transaction monétaire) : transactions
    # monétaires / durée d'activité de l'usager (jours, >= 1), indépendant de la fenêtre globale.
    spans = {u: max(1.0, (max(v) - min(v)) / 86400) for u, v in per_user_times.items() if v}
    rates = sorted(len(v) / spans[u] for u, v in mon_times.items() if v)
    rate_knots = [
        {"p": p / 100, "tx_par_jour": r4(percentile(rates, p))}
        for p in (0, 5, 10, 25, 50, 75, 90, 95, 100)
    ]
    # Regroupement en sessions : écarts entre transactions monétaires consécutives d'un usager.
    gaps = []
    for v in mon_times.values():
        ts = sorted(v)
        gaps += [b - a for a, b in pairwise(ts)]
    sessions = {
        f"part_ecarts_lt_{s}s": r2(100 * sum(1 for g in gaps if g < s) / len(gaps)) if gaps else 0.0
        for s in SESSION_GAPS_S
    }
    sessions["n_ecarts"] = len(gaps)
    all_t = [t for v in per_user_times.values() for t in v]
    span_days = sorted(spans.values())
    return {
        "montants_masses_ponctuelles": {
            "k": POINT_MASSES_K,
            "part_totale": r4(sum(c for _, c in masses) / npos),
            "masses": [{"montant": v, "part": r4(c / npos)} for v, c in masses],
            "noeuds_du_reste": resid_knots,
            "n_reste": len(resid),
            # parts de montants ronds (bande « aucune surcorrection » de T3-06)
            "parts_multiples": {
                str(g): r4(sum(1 for a in amounts_pos if a % g == 0) / npos)
                for g in (100, 500, 1000, 5000)
            },
        },
        "rythme_monetaire_par_usager": {
            "definition": "tx monétaires (montant > 0) / durée d'activité de l'usager (jours, >= 1)",
            "nb_usagers": len(rates),
            "noeuds": rate_knots,
        },
        "sessions": sessions,
        "velocite_par_perimetre": {
            "regle": f">{VELOCITY_N} transactions en {VELOCITY_WINDOW_S // 60} min (glissant)",
            "toutes_lignes": velocity(per_user_times),
            "montants_positifs": velocity({u: v for u, v in mon_times.items() if v}),
            "sorties_positives": velocity({u: v for u, v in out_times.items() if v}),
        },
        "fenetre_observation": {
            "debut": datetime.fromtimestamp(min(all_t), UTC).date().isoformat(),
            "fin": datetime.fromtimestamp(max(all_t), UTC).date().isoformat(),
            "jours": round((max(all_t) - min(all_t)) / 86400),
            "activite_par_usager_jours": {
                "p10": round(percentile(span_days, 10)),
                "median": round(percentile(span_days, 50)),
                "p90": round(percentile(span_days, 90)),
            },
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Étalon des transactions réelles (agrégats).")
    parser.add_argument("--source", type=Path, default=SRC, help="CSV réel (défaut : data/real/)")
    src = parser.parse_args(argv).source
    src_sha = sha256(src)
    amounts_all: list[float] = []
    amounts_pos: list[float] = []
    by_hour = Counter()
    dir_counts = Counter()
    out_amounts: list[float] = []
    per_user_times: dict[str, list[float]] = defaultdict(list)
    mon_times: dict[str, list[float]] = defaultdict(list)  # v1.1 : montants > 0
    out_times: dict[str, list[float]] = defaultdict(list)  # v1.1 : sorties > 0
    n = 0

    with open(src, newline="", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            n += 1
            # MONTANT
            try:
                amt = float(row["Amount"])
            except (TypeError, ValueError):
                amt = 0.0
            amounts_all.append(amt)
            if amt > 0:
                amounts_pos.append(amt)
            # HEURE (dérivée du champ Time « HH:MM:SS », robuste)
            try:
                hour = int(row["Time"].split(":")[0])
            except (TypeError, ValueError, IndexError):
                hour = -1
            if 0 <= hour <= 23:
                by_hour[hour] += 1
            # DIRECTION / SORTIES
            direction = (row.get("Direction") or "").strip().upper()
            dir_counts[direction] += 1
            if direction == "OUT":
                out_amounts.append(amt)
            # PAR USAGER : horodatage epoch (Datetime « YYYY-mm-dd HH:MM:SS »)
            uid = row.get("UserId") or ""
            try:
                # tzinfo=UTC : datetime « aware » -> epoch déterministe (indépendant du fuseau de la
                # machine). Seules les DIFFÉRENCES intra-usager servent (vélocité), inchangées.
                ts = (
                    datetime.strptime(row["Datetime"], "%Y-%m-%d %H:%M:%S")
                    .replace(tzinfo=UTC)
                    .timestamp()
                )
                per_user_times[uid].append(ts)
                if amt > 0:
                    mon_times[uid].append(ts)
                    if direction == "OUT":
                        out_times[uid].append(ts)
            except (TypeError, ValueError):
                pass

    # a. MONTANT
    montant = {
        "tous_les_rangs": stats_amounts(amounts_all),
        "montants_strictement_positifs": stats_amounts(amounts_pos),
        "zero_amount": {
            "n": len(amounts_all) - len(amounts_pos),
            "part": r2(100 * (len(amounts_all) - len(amounts_pos)) / n) if n else 0.0,
        },
        "histogramme": [],
    }
    for label, lo, hi in AMOUNT_BINS:
        if lo == 0 and hi == 0:
            c = sum(1 for a in amounts_all if a == 0)
        else:
            c = sum(1 for a in amounts_all if lo <= a < hi)
        montant["histogramme"].append(
            {"tranche": label, "compte": c, "part": r2(100 * c / n) if n else 0.0}
        )

    # b. HEURE DU JOUR
    heures = []
    night = 0
    for h in range(24):
        c = by_hour.get(h, 0)
        heures.append({"heure": h, "compte": c, "part": r2(100 * c / n) if n else 0.0})
        if h in NIGHT_HOURS:
            night += c
    heure_du_jour = {
        "par_heure": heures,
        "fenetre_nuit": {
            "definition": "23h–05h (heures 23,0,1,2,3,4)",
            "compte": night,
            "part": r2(100 * night / n) if n else 0.0,
        },
    }

    # c. PAR USAGER + VÉLOCITÉ
    counts = sorted(len(v) for v in per_user_times.values())
    nb_users = len(counts)
    flagged = 0
    for times in per_user_times.values():
        ts = sorted(times)
        j = 0
        hit = False
        for i in range(len(ts)):
            while ts[i] - ts[j] > VELOCITY_WINDOW_S:
                j += 1
            if i - j + 1 > VELOCITY_N:  # > 3 transactions dans la fenêtre glissante de 10 min
                hit = True
                break
        if hit:
            flagged += 1
    par_usager = {
        "nb_usagers": nb_users,
        "transactions_par_usager": {
            "min": counts[0] if counts else 0,
            "median": r2(percentile([float(c) for c in counts], 50)),
            "p90": r2(percentile([float(c) for c in counts], 90)),
            "p95": r2(percentile([float(c) for c in counts], 95)),
            "max": counts[-1] if counts else 0,
        },
        "velocite": {
            "regle": ">3 transactions en 10 min (fenêtre glissante)",
            "usagers_depassant": flagged,
            "part": r2(100 * flagged / nb_users) if nb_users else 0.0,
        },
    }

    # d. SORTIES
    n_out = len(out_amounts)
    out_ge_cap = sum(1 for a in out_amounts if a >= CAP_PROB)
    sorties = {
        "champ_sens": "Direction (valeurs : OUT = sortie/débit, IN = entrée, unknown = non renseigné)",
        "distribution_direction": {
            k or "(vide)": v for k, v in sorted(dir_counts.items(), key=lambda kv: -kv[1])
        },
        "taux_de_sorties": r2(100 * n_out / n) if n else 0.0,
        "sorties_n": n_out,
        "cap_prob_fcfa": CAP_PROB,
        "part_sorties_ge_cap_prob": r2(100 * out_ge_cap / n_out) if n_out else 0.0,
        "sorties_ge_cap_prob_n": out_ge_cap,
    }

    # e. CHAMPS ABSENTS pour notre modèle
    champs_absents = {
        "beneficiaire": {
            "present": False,
            "reponse": "NON",
            "detail": "Aucune colonne bénéficiaire/destinataire/contrepartie. Le CSV décrit des "
            "opérations d'un usager (UserId), sans le tiers crédité. Impact modèle : la "
            "règle « nouveau bénéficiaire » (ES-05b) n'est pas calibrable sur ce jeu ; il "
            "faudra la SYNTHÉTISER en T3-02+.",
        },
        "autres_champs_manquants": [
            "IMSI / identité SIM (pas de CAR_IMSI observable ici)",
            "identifiant d'appareil / liaison d'appareil (device binding)",
            "décision / veto / score du PDP (données AVANT toute couche)",
            "canal précis USSD vs application (seul Operator + Txn_type sont donnés)",
            "signature / nonce (anti-rejeu)",
        ],
        "champs_presents_utiles": [
            "Amount (montant)",
            "Time / Datetime (heure, vélocité)",
            "UserId (par usager)",
            "Direction (sorties/entrées)",
            "Operator, Txn_type/Txn_type_std (type d'opération)",
        ],
    }

    profil = {
        "version": VERSION,
        "date": DATE,
        "source_name": SOURCE_NAME,
        "source_sha256": src_sha,
        "effectif_lignes_donnees": n,
        "note_effectif": "26989 lignes de données ; 26990 lignes totales avec l'en-tête "
        "(= le « 26 990 » annoncé).",
        "a_montant": montant,
        "b_heure_du_jour": heure_du_jour,
        "c_par_usager": par_usager,
        "d_sorties": sorties,
        "e_champs_absents": champs_absents,
        "f_v1_1": v1_1_aggregates(amounts_pos, per_user_times, mon_times, out_times),
    }

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8", newline="\n") as f:
        json.dump(profil, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")

    write_md(profil)
    print(f"écrit : {OUT_JSON.relative_to(ROOT)} et {OUT_MD.relative_to(ROOT)}")
    print(f"effectif={n}  sha256={src_sha}")
    return 0


def write_md(p: dict) -> None:
    m = p["a_montant"]
    ma = m["tous_les_rangs"]
    mp = m["montants_strictement_positifs"]
    lines = []
    lines.append(f"# Profil de référence des transactions réelles — {p['version']}")
    lines.append("")
    lines.append(f"- **Date de l'artefact** : {p['date']}")
    lines.append(f"- **Fichier source** : `{p['source_name']}` (confidentiel, NON versionné)")
    lines.append(f"- **Empreinte SHA-256 du source** : `{p['source_sha256']}`")
    lines.append(
        f"- **Effectif** : {p['effectif_lignes_donnees']} lignes de données "
        f"(26 990 lignes totales avec l'en-tête)"
    )
    lines.append(f"- **Version** : {p['version']}")
    lines.append("")
    lines.append(
        "> Étalon du Mois 3 : distributions de référence tirées des transactions réelles, "
        "contre lesquelles le générateur sera jugé (T3-06) et les seuils calibrés (T3-07). "
        "Généré par `tools/profile_real.py` (déterministe)."
    )
    lines.append("")
    lines.append("## a. Montant (FCFA)")
    lines.append("")
    lines.append("| Statistique | Tous les rangs | Montants > 0 |")
    lines.append("|---|--:|--:|")
    for k in ("n", "min", "max", "mean", "median"):
        lines.append(f"| {k} | {ma.get(k)} | {mp.get(k)} |")
    for q in ("p10", "p25", "p50", "p75", "p90", "p95", "p99"):
        lines.append(f"| {q} | {ma['quantiles'][q]} | {mp['quantiles'][q]} |")
    z = m["zero_amount"]
    lines.append("")
    lines.append(
        f"Montants nuls (événements non monétaires : SMS/promo/autre) : "
        f"**{z['n']}** ({z['part']} %)."
    )
    lines.append("")
    lines.append("**Histogramme par tranches**")
    lines.append("")
    lines.append("| Tranche | Compte | % |")
    lines.append("|---|--:|--:|")
    for b in m["histogramme"]:
        lines.append(f"| {b['tranche']} | {b['compte']} | {b['part']} |")
    lines.append("")
    lines.append("## b. Heure du jour")
    lines.append("")
    lines.append("| Heure | Compte | % |")
    lines.append("|--:|--:|--:|")
    for h in p["b_heure_du_jour"]["par_heure"]:
        lines.append(f"| {h['heure']:02d} | {h['compte']} | {h['part']} |")
    fn = p["b_heure_du_jour"]["fenetre_nuit"]
    lines.append("")
    lines.append(
        f"**Fenêtre NUIT** ({fn['definition']}) : **{fn['compte']}** transactions, "
        f"**{fn['part']} %**."
    )
    lines.append("")
    lines.append("## c. Par usager")
    lines.append("")
    u = p["c_par_usager"]
    t = u["transactions_par_usager"]
    lines.append(f"Nombre d'usagers : **{u['nb_usagers']}**.")
    lines.append("")
    lines.append("| Transactions / usager | Valeur |")
    lines.append("|---|--:|")
    for k in ("min", "median", "p90", "p95", "max"):
        lines.append(f"| {k} | {t[k]} |")
    v = u["velocite"]
    lines.append("")
    lines.append(
        f"**Vélocité** ({v['regle']}) : **{v['usagers_depassant']}** usagers, **{v['part']} %**."
    )
    lines.append("")
    lines.append("## d. Sorties (débits)")
    lines.append("")
    d = p["d_sorties"]
    lines.append(f"Champ de sens : {d['champ_sens']}.")
    lines.append("")
    lines.append("| Direction | Compte |")
    lines.append("|---|--:|")
    for k, c in d["distribution_direction"].items():
        lines.append(f"| {k} | {c} |")
    lines.append("")
    lines.append(f"- **Taux de sorties** : {d['taux_de_sorties']} % ({d['sorties_n']} sorties).")
    lines.append(
        f"- **Part des sorties ≥ {d['cap_prob_fcfa']} FCFA (futur CAP_PROB)** : "
        f"{d['part_sorties_ge_cap_prob']} % des sorties ({d['sorties_ge_cap_prob_n']})."
    )
    lines.append("")
    lines.append("## e. Champs absents pour notre modèle")
    lines.append("")
    e = p["e_champs_absents"]
    lines.append(
        f"- **Champ bénéficiaire présent ?** **{e['beneficiaire']['reponse']}**. "
        f"{e['beneficiaire']['detail']}"
    )
    lines.append("- **Autres champs manquants :**")
    for x in e["autres_champs_manquants"]:
        lines.append(f"  - {x}")
    lines.append("- **Champs présents utiles :**")
    for x in e["champs_presents_utiles"]:
        lines.append(f"  - {x}")
    lines.append("")
    f1 = p["f_v1_1"]
    fo = f1["fenetre_observation"]
    lines += [
        "## f. Agrégats ajoutés en v1.1 (T3-05a)",
        "",
        (
            f"**Fenêtre d'observation** : du {fo['debut']} au {fo['fin']} ({fo['jours']} jours). "
            f"Activité par usager : médiane {fo['activite_par_usager_jours']['median']} jours "
            f"(p10 {fo['activite_par_usager_jours']['p10']}, "
            f"p90 {fo['activite_par_usager_jours']['p90']})."
        ),
        "",
    ]
    rt = f1["rythme_monetaire_par_usager"]
    lines += [
        f"**Rythme monétaire par usager** ({rt['definition']}) — {rt['nb_usagers']} usagers :",
        "",
        "| Quantile | tx / jour |",
        "|---|--:|",
    ]
    lines += [f"| p{round(k['p'] * 100)} | {k['tx_par_jour']} |" for k in rt["noeuds"]]
    se = f1["sessions"]
    lines += [
        "",
        (
            f"**Sessions** : écarts entre transactions monétaires consécutives d'un usager < 10 min : "
            f"**{se['part_ecarts_lt_600s']} %** ; < 1 min : {se['part_ecarts_lt_60s']} % "
            f"({se['n_ecarts']} écarts)."
        ),
        "",
        "**Vélocité par périmètre** :",
        "",
        "| Périmètre | Usagers dépassant | Usagers | % |",
        "|---|--:|--:|--:|",
    ]
    for name, v in f1["velocite_par_perimetre"].items():
        if isinstance(v, dict):
            lines.append(f"| {name} | {v['usagers_depassant']} | {v['nb_usagers']} | {v['part']} |")
    mm = f1["montants_masses_ponctuelles"]
    lines += [
        "",
        (
            f"**Montants : {mm['k']} masses ponctuelles** ({round(100 * mm['part_totale'], 2)} % "
            f"des montants > 0) :"
        ),
        "",
        "| Montant (FCFA) | Part |",
        "|--:|--:|",
    ]
    lines += [f"| {m['montant']} | {m['part']} |" for m in mm["masses"]]
    lines += [
        "",
        f"**Nœuds du reste** (montants hors de ces valeurs, n = {mm['n_reste']}) :",
        "",
        "| Quantile | Montant |",
        "|---|--:|",
    ]
    lines += [f"| p{round(k['p'] * 100)} | {k['montant']} |" for k in mm["noeuds_du_reste"]]
    lines.append("")
    f1 = p["f_v1_1"]
    fo = f1["fenetre_observation"]
    lines += [
        "## f. Agrégats ajoutés en v1.1 (T3-05a)",
        "",
        (
            f"**Fenêtre d'observation** : du {fo['debut']} au {fo['fin']} ({fo['jours']} jours). "
            f"Activité par usager : médiane {fo['activite_par_usager_jours']['median']} jours "
            f"(p10 {fo['activite_par_usager_jours']['p10']}, "
            f"p90 {fo['activite_par_usager_jours']['p90']})."
        ),
        "",
    ]
    rt = f1["rythme_monetaire_par_usager"]
    lines += [
        f"**Rythme monétaire par usager** ({rt['definition']}) — {rt['nb_usagers']} usagers :",
        "",
        "| Quantile | tx / jour |",
        "|---|--:|",
    ]
    lines += [f"| p{round(k['p'] * 100)} | {k['tx_par_jour']} |" for k in rt["noeuds"]]
    se = f1["sessions"]
    lines += [
        "",
        (
            f"**Sessions** : écarts entre transactions monétaires consécutives d'un usager < 10 min : "
            f"**{se['part_ecarts_lt_600s']} %** ; < 1 min : {se['part_ecarts_lt_60s']} % "
            f"({se['n_ecarts']} écarts)."
        ),
        "",
        "**Vélocité par périmètre** :",
        "",
        "| Périmètre | Usagers dépassant | Usagers | % |",
        "|---|--:|--:|--:|",
    ]
    for name, v in f1["velocite_par_perimetre"].items():
        if isinstance(v, dict):
            lines.append(f"| {name} | {v['usagers_depassant']} | {v['nb_usagers']} | {v['part']} |")
    mm = f1["montants_masses_ponctuelles"]
    lines += [
        "",
        (
            f"**Montants : {mm['k']} masses ponctuelles** ({round(100 * mm['part_totale'], 2)} % "
            f"des montants > 0) :"
        ),
        "",
        "| Montant (FCFA) | Part |",
        "|--:|--:|",
    ]
    lines += [f"| {m['montant']} | {m['part']} |" for m in mm["masses"]]
    lines += [
        "",
        f"**Nœuds du reste** (montants hors de ces valeurs, n = {mm['n_reste']}) :",
        "",
        "| Quantile | Montant |",
        "|---|--:|",
    ]
    lines += [f"| p{round(k['p'] * 100)} | {k['montant']} |" for k in mm["noeuds_du_reste"]]
    lines.append("")
    with open(OUT_MD, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    raise SystemExit(main())
