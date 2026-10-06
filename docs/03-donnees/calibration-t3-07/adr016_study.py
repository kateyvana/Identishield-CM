"""T3-07 x T3-08 — étude hors ligne ADR-016 + calibration. PROPOSITION : rien n'est écrit dans le dépôt.

Rejoue la fonction de décision proposée sur le jeu légitime gelé (dev horizon v1) et sur le plan
AC-02 gelé. Stdlib seule. Tous les poids/seuils sont des paramètres nommés (dict PARAMS).
"""

import csv
import json
import sys
from bisect import bisect_left, insort
from collections import defaultdict, deque
from datetime import UTC, datetime
from math import sqrt
from pathlib import Path

H, PLAN, OUT = sys.argv[1], sys.argv[2], Path(sys.argv[3])
ARCHS = ["ordinaire", "salarie", "victime", "marchand", "agent"]
PUBLIC = ARCHS[:4]
WARM = 30 * 86400  # rodage : la nouveauté et la médiane 30 j exigent un historique observé
WAT = 3600  # heure locale supposée = UTC+1 (déclaré)


def ep(s):
    return int(datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC).timestamp())


def wilson(k, n, z=1.96):
    if not n:
        return [0.0, 0.0]
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return [round(100 * (c - h), 2), round(100 * (c + h), 2)]


def pct(k, n):
    return round(100 * k / n, 3) if n else 0.0


# --- chargement ---------------------------------------------------------------------------
lab = {}
for r in csv.DictReader(open(H + ".labels.csv", newline="", encoding="utf-8")):
    lab[r["id"]] = (r["archetype"], r["sim_event_case"])
by_user = defaultdict(list)
for r in csv.DictReader(open(H + ".csv", newline="", encoding="utf-8")):
    a, case = lab[r["id"]]
    by_user[r["user"]].append({
        "t": ep(r["timestamp"]), "out": r["direction"] == "OUT", "amt": int(r["amount"]),
        "ben": r["beneficiary"], "bb": int(r["balance_before"]), "ba": int(r["balance_after"]),
        "ch": r["channel"], "arch": a, "case": case})
for xs in by_user.values():
    xs.sort(key=lambda x: x["t"])
t0 = min(x["t"] for xs in by_user.values() for x in xs)
hlr = defaultdict(list)
for r in csv.DictReader(open(H + ".hlr_events.csv", newline="", encoding="utf-8")):
    hlr[r["msisdn"]].append(ep(r["timestamp"]))

# --- pré-calcul des signaux par transaction (indépendants des poids) ----------------------
def local_hour(t):
    return datetime.fromtimestamp(t + WAT, UTC).hour


for u, xs in by_user.items():
    seen = set()
    outs10 = deque()
    newben24 = deque()
    win30 = deque()
    sorted30 = []
    for x in xs:
        x["new"] = x["ben"] not in seen
        seen.add(x["ben"])
        if not x["out"]:
            continue
        t = x["t"]
        outs10.append(t)
        while outs10[0] <= t - 600:
            outs10.popleft()
        if x["new"]:
            newben24.append((t, x["ben"]))
        while newben24 and newben24[0][0] <= t - 86400:
            newben24.popleft()
        while win30 and win30[0][0] < t - 30 * 86400:
            old = win30.popleft()
            sorted30.pop(bisect_left(sorted30, old[1]))
        n = len(sorted30)
        x["med30"] = (sorted30[n // 2] if n % 2 else (sorted30[n // 2 - 1] + sorted30[n // 2]) / 2) if n else None
        win30.append((t, x["amt"]))
        insort(sorted30, x["amt"])
        x["vel_out"] = len(outs10) > 3
        x["vel_ben"] = x["new"] and len({b for _, b in newben24}) > 3
        x["hour_local"] = local_hour(t)
        x["hour_written"] = datetime.fromtimestamp(t, UTC).hour

# PROBATION : fenêtres [te, te + CAR_IMSI) ; cas a/b lu sur la 1re tx qui suit l'événement (sidecar)
CAR = 72 * 3600
prob_windows = defaultdict(list)  # user -> [(te, case, entry_balance)]
for u, tes in hlr.items():
    xs = by_user.get(u, [])
    ts = [x["t"] for x in xs]
    for te in tes:
        i = bisect_left(ts, te)
        case = xs[i]["case"] if i < len(xs) else "?"
        entry = xs[i - 1]["ba"] if i > 0 else (xs[i]["bb"] if i < len(xs) else 0)
        prob_windows[u].append((te, case or "?", entry))

# dénominateurs ISC-EVAL : toutes les tx légitimes du persona, fenêtre de mesure = après rodage
den = defaultdict(int)
den_users = defaultdict(set)
for u, xs in by_user.items():
    for x in xs:
        if x["t"] >= t0 + WARM:
            den[x["arch"]] += 1
            den_users[x["arch"]].add(u)


def night_hit(hour, win):
    lo, hi = win
    return hour >= lo or hour < hi if lo > hi else lo <= hour < hi


# --- fonction de décision proposée --------------------------------------------------------
def decide_score(x, P, hour_key="hour_local", night=True, high_override=None):
    thr = P["THR_HIGH"]["absoluteXaf"]
    if x.get("med30") is not None:
        thr = min(thr, P["THR_HIGH"]["medianFactor"] * x["med30"])
    if high_override is not None:
        thr = high_override
    W = P["W"]
    c = []
    if x["new"]:
        c.append(W["newBeneficiaryHigh"] if x["amt"] >= thr else W["newBeneficiary"])
    if x["vel_out"] or x["vel_ben"]:
        c.append(W["vel"])
    if night and night_hit(x[hour_key], P["NIGHT_LOCAL"]):
        c.append(W["night"])
    s = (min(P["SCORE"]["max"], sum(c)) if P["AGG"] == "bounded_sum" else max(c, default=0))
    if s >= P["SCORE"]["freeze"]:
        return "FREEZE"
    if s >= P["SCORE"]["stepUp"]:
        return "STEP_UP"
    return "ALLOW"


def run(P, night=True, hour_key="hour_local"):
    fz, su, lift, suu = defaultdict(int), defaultdict(int), defaultdict(int), defaultdict(int)
    fz_u, su_u = defaultdict(set), defaultdict(set)
    veto = {"a": [0, 0, set(), set()], "b": [0, 0, set(), set()], "?": [0, 0, set(), set()]}
    cap = P["CAP_PROB"]
    for u, xs in by_user.items():
        wins = prob_windows.get(u, [])
        state = {}  # te -> {"spent": deque, "lifted": bool}
        for x in xs:
            if not x["out"] or x["t"] < t0 + WARM:
                continue
            a = x["arch"]
            d = None
            for te, case, entry in wins:
                if not (te <= x["t"] < te + CAR):
                    continue
                st = state.setdefault(te, {"spent": deque(), "lifted": False})
                if st["lifted"]:
                    break
                v = veto[case]
                v[0] += 1
                v[2].add(u)
                while st["spent"] and st["spent"][0][0] <= x["t"] - 86400:
                    st["spent"].popleft()
                limit = min(cap["dailyXaf"], entry * cap["balancePct"] // 100)
                if x["new"] or sum(m for _, m in st["spent"]) + x["amt"] > limit:
                    v[1] += 1
                    v[3].add(u)
                    if case == "a" and P["LIFT_A_BY_SIGNATURE"]:
                        st["lifted"] = True  # ADR-012 : levée anticipée par signature du dispositif lié
                        lift[a] += 1
                        d = "LIFTED"
                    else:
                        d = "FREEZE"
                else:
                    st["spent"].append((x["t"], x["amt"]))
                break
            if d is None:
                d = decide_score(x, P, hour_key, night)
            if d == "FREEZE":
                fz[a] += 1
                fz_u[a].add(u)
            elif d == "STEP_UP":
                su[a] += 1
                su_u[a].add(u)
                if x["ch"] == "USSD":
                    suu[a] += 1  # SAD §7.2/7.4 : non résolu avant expiration DEFER -> CANCELLED
    rows = {}
    for a in ARCHS:
        rows[a] = {"freeze": fz[a], "fpr_freeze_pct": pct(fz[a], den[a]), "wilson95": wilson(fz[a], den[a]),
                   "step_up": su[a], "step_up_pct": pct(su[a], den[a]),
                   "step_up_ussd": suu[a],
                   "fpr_sad_pct (FREEZE + STEP_UP USSD expiré)": pct(fz[a] + suu[a], den[a]),
                   "wilson95_sad": wilson(fz[a] + suu[a], den[a]),
                   "friction_app_pct (STEP_UP signé)": pct(su[a] - suu[a], den[a]),
                   "usagers_freeze_pct": pct(len(fz_u[a]), len(den_users[a])),
                   "usagers_step_up_pct": pct(len(su_u[a]), len(den_users[a])),
                   "levees_signature_cas_a": lift[a]}
    rows["_veto_probation"] = {k: {"sorties_en_probation": v[0], "veto": v[1], "usagers": len(v[2]),
                                   "usagers_veto": len(v[3]), "part_sorties_pct": pct(v[1], v[0]),
                                   "part_usagers_pct": pct(len(v[3]), len(v[2])),
                                   "wilson_usagers": wilson(len(v[3]), len(v[2]))} for k, v in veto.items() if v[0]}
    return rows


BASE = {
    "AGG": "bounded_sum",
    "SCORE": {"stepUp": 40, "freeze": 70, "max": 100},
    "W": {"newBeneficiary": 20, "newBeneficiaryHigh": 45, "vel": 70, "night": 25},
    "THR_HIGH": {"absoluteXaf": 50000, "medianFactor": 3},
    "NIGHT_LOCAL": (23, 5),
    "CAP_PROB": {"dailyXaf": 25000, "balancePct": 10},
    "LIFT_A_BY_SIGNATURE": True,
}


def variant(**kw):
    P = json.loads(json.dumps(BASE))
    P["NIGHT_LOCAL"] = BASE["NIGHT_LOCAL"]
    for k, v in kw.items():
        if isinstance(v, dict):
            P[k].update(v)
        else:
            P[k] = v
    return P


res = {"fenetre_mesure": "jours 31 à 182 (rodage de 30 j exclu)",
       "denominateurs": {a: {"tx": den[a], "usagers": len(den_users[a])} for a in ARCHS},
       "base": {k: (list(v) if isinstance(v, tuple) else v) for k, v in BASE.items()}}

res["1_base"] = run(BASE)
res["1_base_sans_night"] = run(BASE, night=False)
res["1_base_max"] = run(variant(AGG="max"))
res["1_base_night_heure_ecrite"] = run(BASE, hour_key="hour_written")

grid_w = {}
for wnb in (10, 15, 20, 25, 30):
    for wnbh in (40, 45, 50):
        for agg in ("bounded_sum", "max"):
            r = run(variant(AGG=agg, W={"newBeneficiary": wnb, "newBeneficiaryHigh": wnbh}))
            grid_w[f"{agg} nb={wnb} nbH={wnbh}"] = {a: [r[a]["fpr_freeze_pct"], r[a]["fpr_sad_pct (FREEZE + STEP_UP USSD expiré)"], r[a]["friction_app_pct (STEP_UP signé)"]] for a in ARCHS}
res["2_grille_poids"] = grid_w

grid_thr = {}
for ab in (25000, 50000, 100000):
    for f in (2, 3, 5):
        r = run(variant(THR_HIGH={"absoluteXaf": ab, "medianFactor": f}))
        grid_thr[f"abs={ab} x{f}"] = {a: [r[a]["fpr_freeze_pct"], r[a]["fpr_sad_pct (FREEZE + STEP_UP USSD expiré)"], r[a]["friction_app_pct (STEP_UP signé)"]] for a in ARCHS}
res["3_grille_thr_high"] = grid_thr

grid_night = {}
for w in ((23, 5), (0, 5), (0, 4), (22, 5)):
    for wn in (15, 25):
        r = run(variant(NIGHT_LOCAL=w, W={"night": wn}))
        grid_night[f"{w[0]:02d}h-{w[1]:02d}h w={wn}"] = {a: [r[a]["fpr_freeze_pct"], r[a]["fpr_sad_pct (FREEZE + STEP_UP USSD expiré)"], r[a]["friction_app_pct (STEP_UP signé)"]] for a in ARCHS}
res["4_grille_night"] = grid_night

# --- CAP_PROB ES-04 : courbe perte / friction ------------------------------------------------
plan = list(csv.DictReader(open(PLAN, newline="", encoding="utf-8")))
bal = [int(p["balance_xaf"]) for p in plan]
curve = {}
for daily in (10000, 15000, 25000, 50000):
    for bp in (10, 25, 50, 100):
        r = run(variant(CAP_PROB={"dailyXaf": daily, "balancePct": bp}))
        vp = r["_veto_probation"]
        # perte max AC-07 (bénéficiaire connu, 72 h, plafond 24 h glissantes => jusqu'à 3 plafonds)
        losses = sorted(min(b, 3 * min(daily, b * bp // 100)) for b in bal)
        curve[f"min({daily} ; {bp} %)"] = {
            "cas_b": vp.get("b"), "cas_a": vp.get("a"),
            "perte_max_ac07_mediane_xaf": losses[len(losses) // 2],
            "perte_max_ac07_part_mediane_du_solde_pct": round(100 * sorted(l / b for l, b in zip(
                [min(b, 3 * min(daily, b * bp // 100)) for b in bal], bal))[len(bal) // 2], 1)}
res["5_cap_prob_courbe"] = curve
res["5_cap_prob_sans_levee_a"] = run(variant(LIFT_A_BY_SIGNATURE=False))["_veto_probation"]

# --- ES-11 fail-closed par persona (H5 < 2 %) ----------------------------------------------
fc = {}
for thr in range(20000, 65000, 5000):
    k = defaultdict(int)
    tot = defaultdict(int)
    for xs in by_user.values():
        for x in xs:
            tot[x["arch"]] += 1
            if x["out"] and x["amt"] > thr:
                k[x["arch"]] += 1
    fc[str(thr)] = {a: pct(k[a], tot[a]) for a in ARCHS}
    fc[str(thr)]["ac02_plan_passe_en_degrade_pct"] = pct(sum(b <= thr for b in bal), len(bal))
res["6_fail_closed_es11"] = fc

# --- TPR attendu AC-02 (plan gelé) ---------------------------------------------------------
def tpr(P):
    out = {"avec_imsi_freeze_pct": 100.0, "motif_avec_imsi": "veto ES-04 : nouveau bénéficiaire en PROBATION"}
    dec = defaultdict(int)
    nh = hi = 0
    for p in plan:
        u, t, b = p["victim_msisdn"], ep(p["instant"]), int(p["balance_xaf"])
        xs = [x for x in by_user.get(u, []) if x["t"] < t]
        seen = {x["ben"] for x in xs}
        outs = [x for x in xs if x["out"]]
        win = sorted(x["amt"] for x in outs if x["t"] >= t - 30 * 86400)
        n = len(win)
        med = (win[n // 2] if n % 2 else (win[n // 2 - 1] + win[n // 2]) / 2) if n else None
        recent = sum(1 for x in outs if x["t"] > t - 600)
        x = {"new": p["beneficiary_msisdn"] not in seen, "amt": b, "med30": med,
             "vel_out": recent + 1 > 3, "vel_ben": False, "hour_local": local_hour(t)}
        nh += night_hit(x["hour_local"], P["NIGHT_LOCAL"])
        thr = min(P["THR_HIGH"]["absoluteXaf"], P["THR_HIGH"]["medianFactor"] * med) if med else P["THR_HIGH"]["absoluteXaf"]
        hi += b >= thr
        dec[decide_score(x, P)] += 1
    N = len(plan)
    out.update({"sans_imsi": {k: pct(v, N) for k, v in dec.items()},
                "sans_imsi_borne_basse_tpr_pct (FREEZE seul)": pct(dec["FREEZE"], N),
                "wilson_borne_basse": wilson(dec["FREEZE"], N),
                "sans_imsi_borne_haute_tpr_pct (FREEZE + STEP_UP non résoluble)": pct(dec["FREEZE"] + dec["STEP_UP"], N),
                "wilson_borne_haute": wilson(dec["FREEZE"] + dec["STEP_UP"], N),
                "part_night_locale_pct": pct(nh, N), "part_ge_thr_high_pct": pct(hi, N)})
    return out


res["7_tpr_ac02"] = {
    "base": tpr(BASE),
    "base_max": tpr(variant(AGG="max")),
    "base_sans_night": tpr(variant(W={"night": 0})),
    "nbH=40": tpr(variant(W={"newBeneficiaryHigh": 40})),
    "nbH=50": tpr(variant(W={"newBeneficiaryHigh": 50})),
    "thr x2": tpr(variant(THR_HIGH={"medianFactor": 2})),
    "thr x5": tpr(variant(THR_HIGH={"medianFactor": 5})),
}
OUT.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
print("OK")
