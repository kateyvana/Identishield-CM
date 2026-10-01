"""Control statistics of a generated sample versus the T3-01 reference profile (T3-03).

Early form of the plausibility report (EAR-06): it measures the sample with the SAME definitions
as tools/profile_real.py (quantile interpolation, amount bins, sliding velocity window) and reads
NIGHT and VEL from config/ruleset.yaml, never from literals. The PERSONAS §5 tolerances are shown
for information only: passing them is the job of T3-06, not of this report.

Deterministic: no run timestamp, sorted JSON keys, fixed line endings.
"""

# @implements EAR-06

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from datetime import datetime
from itertools import pairwise
from pathlib import Path

from isc_simulator.personas import MSISDN_HIGH, MSISDN_LOW

QUANTILES = (
    ("p10", 10),
    ("p25", 25),
    ("p50", 50),
    ("p75", 75),
    ("p90", 90),
    ("p95", 95),
    ("p99", 99),
)
# PERSONAS §5.1 relative tolerances: 15 % up to p90, 25 % for the noisier tail.
TOLERANCE = {"p10": 15, "p25": 15, "p50": 15, "p75": 15, "p90": 15, "p95": 25, "p99": 25}
# Same bins as tools/profile_real.py, minus the zero bin (synthetic amounts are always > 0).
AMOUNT_BINS = (
    ("1–500", 1, 500),
    ("500–1k", 500, 1000),
    ("1k–5k", 1000, 5000),
    ("5k–10k", 5000, 10000),
    ("10k–25k", 10000, 25000),
    ("25k–50k", 25000, 50000),
    ("50k–100k", 50000, 100000),
    ("100k–500k", 100000, 500000),
    ("500k+", 500000, float("inf")),
)


def percentile(sorted_vals: list[float], q: float) -> float:
    """Same interpolation as tools/profile_real.py (linear, numpy style); q in [0, 100]."""
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


def ruleset_windows(path: Path) -> tuple[set[int], int, int]:
    """(NIGHT hours, VEL.count, VEL.window seconds) read from the versioned rule set."""
    text = path.read_text(encoding="utf-8")
    night = re.search(r'^\s*NIGHT:\s*"(\d{2}):\d{2}-(\d{2}):\d{2}"', text, re.MULTILINE)
    vel = re.search(
        r"^\s*VEL:[^\n]*\n\s*count:\s*(\d+)[^\n]*\n\s*window:\s*PT(\d+)M", text, re.MULTILINE
    )
    if night is None or vel is None:
        raise ValueError(f"NIGHT or VEL not found in {path}")
    start, end = int(night.group(1)), int(night.group(2))
    hours = {h for h in range(24) if h >= start or h < end}
    return hours, int(vel.group(1)), int(vel.group(2)) * 60


def epoch(ts: str) -> int:
    """UTC epoch seconds of an ISO-8601 'Z' timestamp (timezone-independent)."""
    return int(datetime.fromisoformat(ts).timestamp())


def velocity_share(times_by_user: dict[str, list[int]], count: int, window: int) -> tuple[int, int]:
    """Users with more than `count` transactions inside a sliding `window` (profile_real rule)."""
    flagged = 0
    for ts in times_by_user.values():
        ts = sorted(ts)
        j = 0
        for i in range(len(ts)):
            while ts[i] - ts[j] > window:
                j += 1
            if i - j + 1 > count:
                flagged += 1
                break
    return flagged, len(times_by_user)


def out_sessions(out_times: dict[str, list[int]], ref_s: dict) -> dict:
    """PO-7 : distribution des écarts entre sorties consécutives ; bande ±25 % (relatif) < 10 min."""
    gaps = []
    for ts in out_times.values():
        gaps += [b - a for a, b in pairwise(sorted(ts))]
    lt600 = 100 * sum(1 for g in gaps if g < 600) / len(gaps) if gaps else 0.0
    lt60 = 100 * sum(1 for g in gaps if g < 60) / len(gaps) if gaps else 0.0
    err = (
        100 * (lt600 - ref_s["sorties_part_ecarts_lt_600s"]) / ref_s["sorties_part_ecarts_lt_600s"]
    )
    return {
        "sorties_lt_600s_pct": r2(lt600),
        "sorties_reel_lt_600s_pct": ref_s["sorties_part_ecarts_lt_600s"],
        "sorties_ecart_relatif_pct": r2(err),
        "sorties_dans_tolerance": abs(err) <= 25,
        "sorties_lt_60s_pct": r2(lt60),
        "sorties_reel_lt_60s_pct": ref_s["sorties_part_ecarts_lt_60s"],
    }


def compute(sample: Path, manifest: Path, etalon: Path, ruleset: Path) -> dict:
    with open(sample, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    # v1.4: annotations (archetype, is_new_beneficiary, label) live in the sidecar, joined on id.
    with open(sample.with_name(sample.stem + ".labels.csv"), encoding="utf-8", newline="") as f:
        labels = {r["id"]: r for r in csv.DictReader(f)}
    if set(labels) != {r["id"] for r in rows}:
        raise ValueError("detector view and label sidecar do not join one-to-one on id")
    for r in rows:
        r.update({k: v for k, v in labels[r["id"]].items() if k != "id"})
    facts = json.loads(manifest.read_text(encoding="utf-8"))
    ref = json.loads(etalon.read_text(encoding="utf-8"))
    night_hours, vel_count, vel_window = ruleset_windows(ruleset)

    digest = hashlib.sha256(sample.read_bytes()).hexdigest()
    n = len(rows)

    # a. Amount — WHOLE synthetic population (salary included) versus the real amounts > 0:
    #    the real data set is the population, not a sub-population (PERSONAS v2.0 §5.1).
    ref_amt = ref["a_montant"]["montants_strictement_positifs"]
    amounts = sorted(float(r["amount"]) for r in rows)
    amount_q = {}
    for k, q in QUANTILES:
        synth, real = percentile(amounts, q), ref_amt["quantiles"][k]
        err = 100 * (synth - real) / real
        amount_q[k] = {
            "synthetique": r2(synth),
            "reel": real,
            "ecart_relatif_pct": r2(err),
            "tolerance_pct": TOLERANCE[k],
            "dans_tolerance": abs(err) <= TOLERANCE[k],
        }
    ref_bins = {b["tranche"]: b["compte"] for b in ref["a_montant"]["histogramme"]}
    ref_pos = ref_amt["n"]
    hist, l1 = [], 0.0
    for label, lo, hi in AMOUNT_BINS:
        c = sum(1 for a in amounts if lo <= a < hi)
        synth = c / n if n else 0.0
        real = ref_bins[label] / ref_pos
        l1 += abs(synth - real)
        hist.append({"tranche": label, "part_synth": r2(100 * synth), "part_reel": r2(100 * real)})
    # Round amounts: the synthetic population must not be rounder than the real one.
    ref_mult = ref["f_v1_1"]["montants_masses_ponctuelles"]["parts_multiples"]
    multiples = {}
    for g, real in ref_mult.items():
        share = sum(1 for a in amounts if a % int(g) == 0) / n if n else 0.0
        multiples[g] = {
            "synthetique_pct": r2(100 * share),
            "reel_pct": r2(100 * real),
            "pas_plus_rond_que_le_reel": share <= real,
        }
    montant = {
        "population": {
            "n": n,
            "quantiles": amount_q,
            "histogramme": hist,
            "distance_l1_histogramme": round(l1, 4),
            "cible_l1": 0.10,
            "parts_multiples": multiples,
        },
    }

    # b. Hour of day — whole population versus the real diurnal profile.
    ref_hours = {h["heure"]: h["compte"] for h in ref["b_heure_du_jour"]["par_heure"]}
    ref_total = sum(ref_hours.values())
    counts = [0] * 24
    for r in rows:
        counts[int(r["timestamp"][11:13])] += 1
    l1h = sum(abs(counts[h] / n - ref_hours[h] / ref_total) for h in range(24)) if n else 0.0
    night = sum(counts[h] for h in night_hours)
    heure = {
        "distance_l1": round(l1h, 4),
        "cible_l1": 0.08,
        "part_nuit_pct": r2(100 * night / n) if n else 0.0,
        "part_nuit_reel_pct": ref["b_heure_du_jour"]["fenetre_nuit"]["part"],
        "heures_nuit": sorted(night_hours),
    }

    # c. Outputs and new beneficiaries, per archetype and overall.
    per_arch: dict[str, dict] = {}
    for r in rows:
        a = per_arch.setdefault(r["archetype"], {"tx": 0, "out": 0, "new": 0})
        a["tx"] += 1
        if r["direction"] == "OUT":
            a["out"] += 1
            a["new"] += r["is_new_beneficiary"] == "true"
    outs = sum(a["out"] for a in per_arch.values())
    news = sum(a["new"] for a in per_arch.values())
    ref_dir = ref["d_sorties"]["distribution_direction"]
    sorties = {
        "part_sorties_pct": r2(100 * outs / n) if n else 0.0,
        "part_sorties_reel_in_out_pct": r2(100 * ref_dir["OUT"] / (ref_dir["OUT"] + ref_dir["IN"])),
        "sorties_sautees_solde_insuffisant": facts["skipped_out"],
        "taux_saut_pct": r2(100 * facts["skipped_out"] / (outs + facts["skipped_out"]))
        if outs + facts["skipped_out"]
        else 0.0,
    }
    nouveaux = {
        "part_nouveau_parmi_sorties_pct": r2(100 * news / outs) if outs else 0.0,
        "part_nouveau_parmi_toutes_pct": r2(100 * news / n) if n else 0.0,
    }
    archetypes = {
        name: {
            "usagers": facts["archetype_users"][name],
            "transactions": a["tx"],
            "part_volume_pct": r2(100 * a["tx"] / n),
            "part_sorties_pct": r2(100 * a["out"] / a["tx"]),
            "part_nouveau_parmi_sorties_pct": r2(100 * a["new"] / a["out"]) if a["out"] else 0.0,
        }
        for name, a in sorted(per_arch.items())
    }

    # d. Balances: never negative, and each row's arithmetic and each user's chain are consistent.
    negative = sum(1 for r in rows if int(r["balance_before"]) < 0 or int(r["balance_after"]) < 0)
    arithmetic = 0
    chain = 0
    last: dict[str, int] = {}
    for r in rows:
        before, after, amount = int(r["balance_before"]), int(r["balance_after"]), int(r["amount"])
        if after != (before - amount if r["direction"] == "OUT" else before + amount):
            arithmetic += 1
        if r["user"] in last and last[r["user"]] != before:
            chain += 1
        last[r["user"]] = after
    soldes = {
        "soldes_negatifs": negative,
        "solde_min": min((int(r["balance_after"]) for r in rows), default=0),
        "incoherences_arithmetiques": arithmetic,
        "ruptures_de_chaine": chain,
    }

    # e. MSISDN range, self-transfers, labels.
    def outside(v: str) -> bool:
        return not MSISDN_LOW <= int(v) <= MSISDN_HIGH

    controles = {
        "msisdn_hors_plage": sum(outside(r["user"]) + outside(r["beneficiary"]) for r in rows),
        "auto_transferts": sum(1 for r in rows if r["user"] == r["beneficiary"]),
        "labels": sorted({r["label"] for r in rows}),
    }

    # Comparable population (PERSONAS v2.0 §5): every user but the declared agent overlay.
    times: dict[str, list[int]] = {}
    out_times: dict[str, list[int]] = {}
    for r in rows:
        if r["archetype"] == "agent":
            continue
        t = epoch(r["timestamp"])
        times.setdefault(r["user"], []).append(t)
        if r["direction"] == "OUT":
            out_times.setdefault(r["user"], []).append(t)
    for ts in times.values():
        ts.sort()
    f1 = ref["f_v1_1"]

    # f. Velocity — DIAGNOSTIC only, never a target (PERSONAS v2.0 §5.4): population without
    #    agents, monetary transactions and outputs, over the sample window (stated).
    mon_flag, mon_n = velocity_share(times, vel_count, vel_window)
    out_flag, out_n = velocity_share(out_times, vel_count, vel_window)
    vel_ref = f1["velocite_par_perimetre"]
    velocite = {
        "regle": f">{vel_count} transactions en {vel_window // 60} min (glissant)",
        "perimetre": "population hors agents",
        "fenetre_jours": facts["days"],
        "activite_mediane_reel_jours": f1["fenetre_observation"]["activite_par_usager_jours"][
            "median"
        ],
        "monetaire_pct": r2(100 * mon_flag / mon_n) if mon_n else 0.0,
        "monetaire_reel_pct": vel_ref["montants_positifs"]["part"],
        "sorties_pct": r2(100 * out_flag / out_n) if out_n else 0.0,
        "sorties_reel_pct": vel_ref["sorties_positives"]["part"],
        "monetaire_usagers": [mon_flag, mon_n],
        "monetaire_usagers_reel": [
            vel_ref["montants_positifs"]["usagers_depassant"],
            vel_ref["montants_positifs"]["nb_usagers"],
        ],
        "sorties_usagers": [out_flag, out_n],
        "sorties_usagers_reel": [
            vel_ref["sorties_positives"]["usagers_depassant"],
            vel_ref["sorties_positives"]["nb_usagers"],
        ],
    }

    # h. Per-user monetary rate — population without agents (PERSONAS v2.0 §5.2): transactions /
    #    the user's activity span in days (>= 1), the definition of the reference profile.
    rates = sorted(len(ts) / max(1.0, (ts[-1] - ts[0]) / 86400) for ts in times.values())
    ref_rate = {
        round(k["p"] * 100): k["tx_par_jour"] for k in f1["rythme_monetaire_par_usager"]["noeuds"]
    }
    rythme = {"n_usagers": len(rates), "quantiles": {}}
    for p, tol in ((10, 25), (25, 15), (50, 15), (75, 15), (90, 15), (95, 25)):
        synth, real = percentile(rates, p), ref_rate[p]
        err = 100 * (synth - real) / real
        rythme["quantiles"][f"p{p}"] = {
            "synthetique": round(synth, 3),
            "reel": real,
            "ecart_relatif_pct": r2(err),
            "tolerance_pct": tol,
            "dans_tolerance": abs(err) <= tol,
        }
    rythme["max_synthetique"] = round(rates[-1], 3) if rates else 0.0
    rythme["max_reel"] = ref_rate[100]

    # i. Sessions — share of consecutive monetary transactions of a user less than 10 min / 1 min
    #    apart (PERSONAS v2.0 §5.5); target: ±25 % (relative) of the real share under 10 min.
    gaps = [b - a for ts in times.values() for a, b in pairwise(ts)]
    ref_s = f1["sessions"]
    lt600 = 100 * sum(1 for g in gaps if g < 600) / len(gaps) if gaps else 0.0
    lt60 = 100 * sum(1 for g in gaps if g < 60) / len(gaps) if gaps else 0.0
    sess_err = 100 * (lt600 - ref_s["part_ecarts_lt_600s"]) / ref_s["part_ecarts_lt_600s"]
    sessions = {
        "part_ecarts_lt_600s_pct": r2(lt600),
        "reel_lt_600s_pct": ref_s["part_ecarts_lt_600s"],
        "ecart_relatif_pct": r2(sess_err),
        "tolerance_pct": 25,
        "dans_tolerance": abs(sess_err) <= 25,
        "part_ecarts_lt_60s_pct": r2(lt60),
        "reel_lt_60s_pct": ref_s["part_ecarts_lt_60s"],
        "n_ecarts": len(gaps),
        # PO-7 : écarts entre SORTIES consécutives d'un même usager (hors agents).
        **out_sessions(out_times, ref_s),
    }

    # g. Channel and device binding per archetype (PERSONAS v1.2, declared assumptions).
    per_user_bound: dict[str, tuple[str, bool]] = {}
    chan: dict[str, dict[str, int]] = {}
    for r in rows:
        per_user_bound[r["user"]] = (r["archetype"], r["device_bound"] == "true")
        c = chan.setdefault(r["archetype"], {"tx": 0, "app": 0})
        c["tx"] += 1
        c["app"] += r["channel"] == "APP"
    users_by_arch: dict[str, list[bool]] = {}
    for arch_name, is_bound in per_user_bound.values():
        users_by_arch.setdefault(arch_name, []).append(is_bound)
    canal = {
        name: {
            "part_app_pct": r2(100 * c["app"] / c["tx"]),
            "part_ussd_pct": r2(100 * (c["tx"] - c["app"]) / c["tx"]),
            "usagers_actifs": len(users_by_arch[name]),
            "part_appareil_lie_pct": r2(100 * sum(users_by_arch[name]) / len(users_by_arch[name])),
        }
        for name, c in sorted(chan.items())
    }
    app_without_device = sum(
        1 for r in rows if r["channel"] == "APP" and r["device_bound"] != "true"
    )

    return {
        "echantillon": {
            "fichier": sample.name,
            "sha256": digest,
            "sha256_manifeste_identique": digest == facts["sha256"],
            "transactions": n,
            "usagers": facts["users"],
            "jours": facts["days"],
            "debut": facts["start"],
            "isc_seed": facts["isc_seed"],
            "personas_version": facts["personas_version"],
        },
        "etalon": {"fichier": etalon.name, "source_sha256": ref["source_sha256"]},
        "a_montant": montant,
        "b_heure": heure,
        "c_sorties": sorties,
        "c_nouveau_beneficiaire": nouveaux,
        "c_archetypes": archetypes,
        "d_soldes": soldes,
        "e_controles": controles,
        "f_velocite": velocite,
        "h_rythme": rythme,
        "i_sessions": sessions,
        "g_canal_appareil": {"par_archetype": canal, "app_sans_appareil_lie": app_without_device},
    }


def yes(flag: bool) -> str:
    return "oui" if flag else "**non**"


def render_md(s: dict) -> str:
    e, m, h = s["echantillon"], s["a_montant"], s["b_heure"]
    so, nb, sd = s["c_sorties"], s["c_nouveau_beneficiaire"], s["d_soldes"]
    c, v = s["e_controles"], s["f_velocite"]
    pop = m["population"]
    out = [
        "# Échantillon de contrôle — trafic légitime v1",
        "",
        f"- **Fichier** : `data/synth/{e['fichier']}` (SHA-256 `{e['sha256']}`)",
        f"- **Graine** : ISC_SEED = {e['isc_seed']} ; personas {e['personas_version']}",
        (
            f"- **Population** : {e['usagers']} usagers, {e['jours']} jours à partir du "
            f"{e['debut']} → **{e['transactions']} transactions**, toutes étiquetées `LEGIT`"
        ),
        (
            f"- **Étalon** : `{s['etalon']['fichier']}` "
            f"(source `{s['etalon']['source_sha256'][:8]}…`)"
        ),
        "",
        (
            "> Rapport généré par `python -m isc_simulator.stats` (déterministe). Les tolérances "
            "de PERSONAS §5 sont indiquées à titre d'information : les tenir est l'objet de T3-06."
        ),
        "",
        "## a. Montant — population entière vs réel (montants > 0, salaire compris)",
        "",
        "| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |",
        "|---|--:|--:|--:|--:|---|",
    ]
    for k, q in pop["quantiles"].items():
        out.append(
            f"| {k} | {q['synthetique']} | {q['reel']} | {q['ecart_relatif_pct']:+} % "
            f"| ±{q['tolerance_pct']} % | {yes(q['dans_tolerance'])} |"
        )
    out += [
        "",
        (
            f"n = {pop['n']}. Distance L1 d'histogramme = "
            f"**{pop['distance_l1_histogramme']}** (cible < {pop['cible_l1']})."
        ),
        "",
        "| Tranche | Synthétique % | Réel % |",
        "|---|--:|--:|",
    ]
    out += [
        f"| {b['tranche']} | {b['part_synth']} | {b['part_reel']} |" for b in pop["histogramme"]
    ]
    out += [
        "",
        "Montants ronds (le synthétique ne doit pas être plus rond que le réel) :",
        "",
        "| Multiple de | Synthétique % | Réel % | Pas plus rond que le réel |",
        "|--:|--:|--:|---|",
    ]
    out += [
        f"| {g} | {x['synthetique_pct']} | {x['reel_pct']} | {yes(x['pas_plus_rond_que_le_reel'])} |"
        for g, x in pop["parts_multiples"].items()
    ]
    out += [
        "",
        "## b. Heure du jour — population",
        "",
        f"- Distance L1 sur 24 h : **{h['distance_l1']}** (cible < {h['cible_l1']}).",
        (
            f"- Part NUIT (heures {h['heures_nuit']}, lue dans NIGHT) : **{h['part_nuit_pct']} %** "
            f"(réel {h['part_nuit_reel_pct']} %, cible ±1,5 pt)."
        ),
        "",
        "## c. Sorties et nouveaux bénéficiaires",
        "",
        (
            f"- Part de sorties : **{so['part_sorties_pct']} %** "
            f"(réel {so['part_sorties_reel_in_out_pct']} % parmi IN+OUT ; non forcé)."
        ),
        (
            f"- Sorties sautées pour solde insuffisant : {so['sorties_sautees_solde_insuffisant']} "
            f"({so['taux_saut_pct']} % des sorties tentées)."
        ),
        (
            f"- `is_new_beneficiary` : **{nb['part_nouveau_parmi_sorties_pct']} % des sorties** "
            f"({nb['part_nouveau_parmi_toutes_pct']} % de toutes les transactions)."
        ),
        "",
        (
            "| Archétype | Usagers | Transactions | Part du volume % | Part de sorties % "
            "| Nouveau / sorties % |"
        ),
        "|---|--:|--:|--:|--:|--:|",
    ]
    for name, a in s["c_archetypes"].items():
        out.append(
            f"| {name} | {a['usagers']} | {a['transactions']} | {a['part_volume_pct']} "
            f"| {a['part_sorties_pct']} | {a['part_nouveau_parmi_sorties_pct']} |"
        )
    out += [
        "",
        "## d. Soldes",
        "",
        (
            f"- Soldes négatifs : **{sd['soldes_negatifs']}** "
            f"(solde minimal observé : {sd['solde_min']})."
        ),
        (
            "- Incohérences arithmétiques (après ≠ avant ± montant) : "
            f"{sd['incoherences_arithmetiques']}."
        ),
        (
            "- Ruptures de chaîne (solde d'avant ≠ solde d'après précédent du même usager) : "
            f"{sd['ruptures_de_chaine']}."
        ),
        "",
        "## e. Contrôles",
        "",
        (
            "- MSISDN hors de 677000000–677009999 (usagers et bénéficiaires) : "
            f"**{c['msisdn_hors_plage']}**."
        ),
        (
            f"- Auto-transferts : {c['auto_transferts']}. "
            f"Étiquettes présentes : {', '.join(c['labels'])}."
        ),
        "",
        "## f. Vélocité — DIAGNOSTIC, jamais une cible (PERSONAS v2.0 §5.4)",
        "",
        (
            f"- Règle : {v['regle']} (lue dans VEL). Périmètre : {v['perimetre']}, "
            f"sur {v['fenetre_jours']} jours (activité médiane du réel : "
            f"{v['activite_mediane_reel_jours']} jours ; fenêtres non identiques)."
        ),
        (
            f"- Transactions monétaires : **{v['monetaire_pct']} %** des usagers "
            f"({v['monetaire_usagers'][0]}/{v['monetaire_usagers'][1]} ; "
            f"réel {v['monetaire_reel_pct']} %, "
            f"{v['monetaire_usagers_reel'][0]}/{v['monetaire_usagers_reel'][1]})."
        ),
        (
            f"- Sorties seules : **{v['sorties_pct']} %** "
            f"({v['sorties_usagers'][0]}/{v['sorties_usagers'][1]} ; "
            f"réel {v['sorties_reel_pct']} %, "
            f"{v['sorties_usagers_reel'][0]}/{v['sorties_usagers_reel'][1]})."
        ),
        "",
        "## g. Canal et liaison d'appareil (PERSONAS v1.2, hypothèses déclarées)",
        "",
        "| Archétype | Usagers actifs | Téléphone lié % | APP % des tx | USSD % des tx |",
        "|---|--:|--:|--:|--:|",
    ]
    g = s["g_canal_appareil"]
    for name, a in g["par_archetype"].items():
        out.append(
            f"| {name} | {a['usagers_actifs']} | {a['part_appareil_lie_pct']} "
            f"| {a['part_app_pct']} | {a['part_ussd_pct']} |"
        )
    out += [
        "",
        f"- Transactions APP sans téléphone lié : **{g['app_sans_appareil_lie']}** (attendu : 0).",
        "",
    ]
    rt, se = s["h_rythme"], s["i_sessions"]
    out += [
        "## h. Rythme monétaire par usager — population hors agents (PERSONAS v2.0 §5.2)",
        "",
        f"{rt['n_usagers']} usagers ; tx / durée d'activité de l'usager (jours, ≥ 1).",
        "",
        "| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |",
        "|---|--:|--:|--:|--:|---|",
    ]
    for k, q in rt["quantiles"].items():
        out.append(
            f"| {k} | {q['synthetique']} | {q['reel']} | {q['ecart_relatif_pct']:+} % "
            f"| ±{q['tolerance_pct']} % | {yes(q['dans_tolerance'])} |"
        )
    out += [
        "",
        f"Maximum (rapporté seulement) : {rt['max_synthetique']} (réel {rt['max_reel']}).",
        "",
        "## i. Sessions — population hors agents (PERSONAS v2.0 §5.5)",
        "",
        (
            f"- Écarts consécutifs < 10 min : **{se['part_ecarts_lt_600s_pct']} %** "
            f"(réel {se['reel_lt_600s_pct']} % ; écart {se['ecart_relatif_pct']:+} %, "
            f"tolérance ±{se['tolerance_pct']} % : {yes(se['dans_tolerance'])})."
        ),
        (
            f"- Écarts consécutifs < 1 min : {se['part_ecarts_lt_60s_pct']} % "
            f"(réel {se['reel_lt_60s_pct']} %, rapporté)."
        ),
        (
            f"- Écarts entre SORTIES consécutives < 10 min : **{se['sorties_lt_600s_pct']} %** "
            f"(réel {se['sorties_reel_lt_600s_pct']} % ; écart {se['sorties_ecart_relatif_pct']:+} %, "
            f"tolérance ±25 % : {yes(se['sorties_dans_tolerance'])}) ; < 1 min "
            f"{se['sorties_lt_60s_pct']} % (réel {se['sorties_reel_lt_60s_pct']} %)."
        ),
        "",
    ]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Control statistics of a generated sample.")
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--etalon", type=Path, required=True)
    parser.add_argument("--ruleset", type=Path, required=True)
    parser.add_argument("--out-json", type=Path, required=True)
    parser.add_argument("--out-md", type=Path, required=True)
    args = parser.parse_args(argv)

    stats = compute(
        args.sample, args.sample.with_suffix(".manifest.json"), args.etalon, args.ruleset
    )
    args.out_json.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    args.out_md.write_text(render_md(stats), encoding="utf-8", newline="\n")
    print(json.dumps({k: stats[k] for k in ("d_soldes", "e_controles")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
