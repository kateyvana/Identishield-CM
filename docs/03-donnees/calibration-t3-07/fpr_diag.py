"""T3-07 phase 1 — diagnostic FPR sur le jeu légitime gelé. Aucun seuil écrit. Stdlib seule."""

import csv
import hashlib
import json
import statistics
import sys
from bisect import bisect_left, bisect_right
from collections import defaultdict, deque
from datetime import UTC, datetime
from pathlib import Path

H = Path(sys.argv[1])  # <...>/dev_horizon_v1  (sans extension)
PLAN = Path(sys.argv[2])  # ac02_plan.csv
MAN = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))
OUT = Path(sys.argv[4])

# 0. intégrité contre le manifeste
exp = MAN["horizon_hashes_attendus"]
integ = {}
for key, suf in (("vue_detecteur", ".csv"), ("sidecar_labels", ".labels.csv"), ("hlr_events", ".hlr_events.csv")):
    h = hashlib.sha256(Path(str(H) + suf).read_bytes()).hexdigest()
    integ[key] = {"sha256": h, "identique_au_gel": h == exp[key]["sha256"]}
if not all(v["identique_au_gel"] for v in integ.values()):
    sys.exit(f"INTÉGRITÉ KO : {integ}")


def ep(s):
    return int(datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC).timestamp())


lab = {}
with open(str(H) + ".labels.csv", newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        lab[r["id"]] = (r["archetype"], r["is_new_beneficiary"] == "true")

tx = []  # (user, t, out, amount, benef, bal_before, channel, new, arch)
arch_of = {}
with open(str(H) + ".csv", newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        a, nb = lab[r["id"]]
        arch_of[r["user"]] = a
        tx.append((r["user"], ep(r["timestamp"]), r["direction"] == "OUT", int(r["amount"]),
                   r["beneficiary"], int(r["balance_before"]), r["channel"], nb, a))
tx.sort(key=lambda x: (x[0], x[1]))
t0 = min(x[1] for x in tx)

ARCHS = ["ordinaire", "salarie", "victime", "marchand", "agent"]
n_tx = defaultdict(int)
n_out = defaultdict(int)
users = defaultdict(set)
for u, t, o, *_r, a in tx:
    n_tx[a] += 1
    users[a].add(u)
    if o:
        n_out[a] += 1
by_user = defaultdict(list)
for x in tx:
    by_user[x[0]].append(x)


def pct(k, n):
    return round(100 * k / n, 3) if n else 0.0


def table(flag_ids_by_arch, flag_users_by_arch):
    """FPR par transaction (dénominateur EVAL : toutes les tx légitimes du persona) + part d'usagers."""
    rows = {}
    for a in ARCHS:
        k = flag_ids_by_arch.get(a, 0)
        rows[a] = {"tx_declenchees": k, "fpr_tx_pct": pct(k, n_tx[a]),
                   "usagers_touches": len(flag_users_by_arch.get(a, ())),
                   "part_usagers_pct": pct(len(flag_users_by_arch.get(a, ())), len(users[a]))}
    k = sum(flag_ids_by_arch.get(a, 0) for a in ARCHS if a != "agent")
    uu = sum(len(flag_users_by_arch.get(a, ())) for a in ARCHS if a != "agent")
    nt = sum(n_tx[a] for a in ARCHS if a != "agent")
    nu = sum(len(users[a]) for a in ARCHS if a != "agent")
    rows["HORS_AGENTS"] = {"tx_declenchees": k, "fpr_tx_pct": pct(k, nt), "usagers_touches": uu,
                           "part_usagers_pct": pct(uu, nu)}
    rows["pire_persona_hors_agents"] = max(
        (rows[a]["fpr_tx_pct"], a) for a in ARCHS if a != "agent")
    return rows


res = {"integrite": integ, "fenetre_jours": 182,
       "denominateurs": {a: {"usagers": len(users[a]), "tx": n_tx[a], "sorties": n_out[a]} for a in ARCHS}}

# A. VEL clause sorties : > c sorties dans (t - w, t]
vel = {}
for w_min in (5, 10, 15, 30):
    for c in (3, 4, 5, 6, 8):
        fl, fu = defaultdict(int), defaultdict(set)
        for u, xs in by_user.items():
            q = deque()
            for x in xs:
                if not x[2]:
                    continue
                q.append(x[1])
                while q[0] <= x[1] - w_min * 60:
                    q.popleft()
                if len(q) > c:
                    fl[x[8]] += 1
                    fu[x[8]].add(u)
        vel[f">{c} sorties / {w_min} min"] = table(fl, fu)
res["A_vel_sorties"] = vel

# B. VEL clause bénéficiaires : > n bénéficiaires NOUVEAUX distincts en 24 h
#    nouveauté (i) sidecar (vérité générateur) ; (ii) observable par le PDP = jamais vu dans le flux
#    de l'usager, avec rodage de 30 j exclu (démarrage à froid)
WARM = 30 * 86400
velb = {}
for mode in ("sidecar", "flux_observe_rodage_30j"):
    for n in (2, 3, 4, 5):
        fl, fu = defaultdict(int), defaultdict(set)
        for u, xs in by_user.items():
            seen = set()
            win = deque()  # (t, benef)
            for x in xs:
                if not x[2]:
                    if mode != "sidecar":
                        seen.add(x[4])
                    continue
                new = x[7] if mode == "sidecar" else (x[4] not in seen)
                seen.add(x[4])
                if mode != "sidecar" and x[1] < t0 + WARM:
                    continue
                if new:
                    win.append((x[1], x[4]))
                while win and win[0][0] <= x[1] - 86400:
                    win.popleft()
                if new and len({b for _, b in win}) > n:
                    fl[x[8]] += 1
                    fu[x[8]].add(u)
        velb[f"{mode} : > {n} nouveaux / 24 h"] = table(fl, fu)
res["B_vel_beneficiaires"] = velb
# part des sorties « nouveau bénéficiaire » selon le flux observé (après rodage), pour comparaison
nb_side = nb_obs = outs_after = 0
for u, xs in by_user.items():
    seen = set()
    for x in xs:
        new_obs = x[4] not in seen
        seen.add(x[4])
        if x[2] and x[1] >= t0 + WARM and x[8] != "agent":
            outs_after += 1
            nb_side += x[7]
            nb_obs += new_obs
res["B_nouveaute_comparee_hors_agents_apres_rodage"] = {
    "sorties": outs_after, "nouveau_sidecar_pct": pct(nb_side, outs_after),
    "nouveau_flux_observe_pct": pct(nb_obs, outs_after)}

# C. NIGHT : exposition des sorties ; deux hypothèses de fuseau pour les heures réelles
#    (H0 : heure naïve du réel = UTC ; H1 : heure naïve = WAT = UTC+1 -> UTC = naïve - 1)
night = {}
for shift_name, shift in (("H0_reel_en_UTC", 0), ("H1_reel_en_WAT", -1)):
    for lo, hi in ((23, 5), (0, 5), (0, 4), (1, 5)):
        hours = {h % 24 for h in range(lo, hi + (24 if hi < lo else 0))}
        fl, fu, fl_nb = defaultdict(int), defaultdict(set), defaultdict(int)
        for x in tx:
            if not x[2]:
                continue
            hr = (datetime.fromtimestamp(x[1], UTC).hour + shift) % 24
            if hr in hours:
                fl[x[8]] += 1
                fu[x[8]].add(x[0])
                if x[7]:
                    fl_nb[x[8]] += 1
        tb = table(fl, fu)
        tb["NIGHT_et_nouveau_beneficiaire_fpr_tx_pct"] = {
            a: pct(fl_nb[a], n_tx[a]) for a in ARCHS}
        night[f"{shift_name} : {lo:02d}h-{hi:02d}h"] = tb
res["C_night_exposition"] = night

# D1. CAP_PROB en PROBATION (après un changement d'IMSI légitime, vue HLR), CAR_IMSI = 72 h
hlr = []
with open(str(H) + ".hlr_events.csv", newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        hlr.append((r["msisdn"], ep(r["timestamp"])))
CAR = 72 * 3600
cap = {}
prob_tx = defaultdict(int)
for daily in (10000, 25000, 50000, 100000):
    for bp in (10, 25, 50, None):
        fl, fu = defaultdict(int), defaultdict(set)
        fl_amt_only = defaultdict(int)
        for msisdn, te in hlr:
            xs = by_user.get(msisdn, [])
            i = bisect_left([x[1] for x in xs], te)
            spent = deque()  # (t, amount) des sorties autorisées sur 24 h glissantes
            for x in xs[i:]:
                if x[1] >= te + CAR:
                    break
                if daily == 10000 and bp == 10:
                    prob_tx[x[8]] += 1
                if not x[2]:
                    continue
                while spent and spent[0][0] <= x[1] - 86400:
                    spent.popleft()
                limit = daily if bp is None else min(daily, x[5] * bp // 100)
                over = sum(a for _, a in spent) + x[3] > limit
                if x[7] or over:
                    fl[x[8]] += 1
                    fu[x[8]].add(msisdn)
                    if over and not x[7]:
                        fl_amt_only[x[8]] += 1
                else:
                    spent.append((x[1], x[3]))
        tb = table(fl, fu)
        tb["dont_plafond_seul_tx"] = dict(fl_amt_only)
        cap[f"min({daily} ; {bp} % du solde)" if bp else f"{daily} seul"] = tb
res["D1_cap_prob_probation"] = {"evenements_hlr": len(hlr), "car_imsi_h": 72,
                                "tx_en_probation_par_persona": dict(prob_tx), "grille": cap}

# D2. fail-closed (H5) : part des tx légitimes qui sont des sorties > seuil (sémantique du cœur)
fc = {}
for thr in (10000, 25000, 50000, 100000):
    fl, fu = defaultdict(int), defaultdict(set)
    for x in tx:
        if x[2] and x[3] > thr:
            fl[x[8]] += 1
            fu[x[8]].add(x[0])
    fc[f"> {thr}"] = table(fl, fu)
res["D2_fail_closed_h5"] = fc

# E. signature AC-02 lue sur le plan gelé (300 cas)
plan = list(csv.DictReader(open(PLAN, newline="", encoding="utf-8")))
e = {"cas": len(plan)}
bal = [int(p["balance_xaf"]) for p in plan]
e["solde_xaf"] = {"min": min(bal), "p50": statistics.median(bal), "max": max(bal),
                  "part_sup_25000_pct": pct(sum(b > 25000 for b in bal), len(bal))}
hrs = [datetime.fromtimestamp(ep(p["instant"]), UTC).hour for p in plan]
e["instant_dans_NIGHT_23_05_utc_pct"] = pct(sum(h in {23, 0, 1, 2, 3, 4} for h in hrs), len(hrs))
thr_hit = 0
for p in plan:
    t = ep(p["instant"])
    amts = [x[3] for x in by_user.get(p["victim_msisdn"], []) if x[2] and t - 30 * 86400 <= x[1] < t]
    thr = min(50000, 3 * statistics.median(amts)) if amts else 50000
    thr_hit += int(p["balance_xaf"]) >= thr
e["montant_ge_THR_HIGH_actuel_pct"] = pct(thr_hit, len(plan))
e["beneficiaire_nouveau_pct"] = 100.0
e["sorties_par_attaque"] = 1
res["E_signature_ac02"] = e

OUT.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
print("OK", OUT)
