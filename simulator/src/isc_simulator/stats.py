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


def compute(sample: Path, manifest: Path, etalon: Path, ruleset: Path) -> dict:
    with open(sample, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    facts = json.loads(manifest.read_text(encoding="utf-8"))
    ref = json.loads(etalon.read_text(encoding="utf-8"))
    night_hours, vel_count, vel_window = ruleset_windows(ruleset)

    digest = hashlib.sha256(sample.read_bytes()).hexdigest()
    n = len(rows)

    # a. Amount — agents only (calibrated) versus the real amounts > 0.
    ref_amt = ref["a_montant"]["montants_strictement_positifs"]
    agents = sorted(float(r["amount"]) for r in rows if r["archetype"] == "agent")
    agent_q = {}
    for k, q in QUANTILES:
        synth, real = percentile(agents, q), ref_amt["quantiles"][k]
        err = 100 * (synth - real) / real
        agent_q[k] = {
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
        c = sum(1 for a in agents if lo <= a < hi)
        synth = c / len(agents) if agents else 0.0
        real = ref_bins[label] / ref_pos
        l1 += abs(synth - real)
        hist.append({"tranche": label, "part_synth": r2(100 * synth), "part_reel": r2(100 * real)})
    population = sorted(float(r["amount"]) for r in rows)
    montant = {
        "agents": {
            "n": len(agents),
            "quantiles": agent_q,
            "histogramme": hist,
            "distance_l1_histogramme": round(l1, 4),
            "cible_l1": 0.10,
        },
        "population_informatif": {
            "n": n,
            "quantiles": {k: r2(percentile(population, q)) for k, q in QUANTILES},
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

    # f. Velocity — reported, NOT forced to the real value (PERSONAS §5.4).
    times: dict[str, list[int]] = {}
    agent_times: dict[str, list[int]] = {}
    for r in rows:
        t = epoch(r["timestamp"])
        times.setdefault(r["user"], []).append(t)
        if r["archetype"] == "agent":
            agent_times.setdefault(r["user"], []).append(t)
    pop_flag, pop_n = velocity_share(times, vel_count, vel_window)
    agt_flag, agt_n = velocity_share(agent_times, vel_count, vel_window)
    velocite = {
        "regle": f">{vel_count} transactions en {vel_window // 60} min (glissant)",
        "population_pct": r2(100 * pop_flag / pop_n) if pop_n else 0.0,
        "agents_pct": r2(100 * agt_flag / agt_n) if agt_n else 0.0,
        "bande_agents_cible_pct": [25, 55],
        "reel_pct": ref["c_par_usager"]["velocite"]["part"],
    }

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
    }


def yes(flag: bool) -> str:
    return "oui" if flag else "**non**"


def render_md(s: dict) -> str:
    e, m, h = s["echantillon"], s["a_montant"], s["b_heure"]
    so, nb, sd = s["c_sorties"], s["c_nouveau_beneficiaire"], s["d_soldes"]
    c, v = s["e_controles"], s["f_velocite"]
    agents = m["agents"]
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
        "## a. Montant — agents seuls vs réel (montants > 0)",
        "",
        "| Quantile | Synthétique | Réel | Écart | Tolérance | Dans la tolérance |",
        "|---|--:|--:|--:|--:|---|",
    ]
    for k, q in agents["quantiles"].items():
        out.append(
            f"| {k} | {q['synthetique']} | {q['reel']} | {q['ecart_relatif_pct']:+} % "
            f"| ±{q['tolerance_pct']} % | {yes(q['dans_tolerance'])} |"
        )
    out += [
        "",
        (
            f"Agents : n = {agents['n']}. Distance L1 d'histogramme = "
            f"**{agents['distance_l1_histogramme']}** (cible < {agents['cible_l1']})."
        ),
        "",
        "| Tranche | Synthétique % | Réel % |",
        "|---|--:|--:|",
    ]
    out += [
        f"| {b['tranche']} | {b['part_synth']} | {b['part_reel']} |" for b in agents["histogramme"]
    ]
    pq = m["population_informatif"]["quantiles"]
    out += [
        "",
        "Population entière (informatif, non forcé) : "
        + ", ".join(f"{k} = {x}" for k, x in pq.items())
        + ".",
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
    band = v["bande_agents_cible_pct"]
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
        "## f. Vélocité — rapportée, NON alignée sur le réel (PERSONAS §5.4)",
        "",
        f"- Règle : {v['regle']} (lue dans VEL).",
        (
            f"- Population : **{v['population_pct']} %** des usagers ; "
            f"agents seuls : **{v['agents_pct']} %** "
            f"(bande cible {band[0]}–{band[1]} %, réel {v['reel_pct']} %)."
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
