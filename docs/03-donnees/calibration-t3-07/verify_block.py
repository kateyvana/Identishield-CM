"""T3-07 — vérification critique : FPR légitime SOUS LA POLITIQUE USSD = BLOQUER (décision 06/10).

Réutilise À L'IDENTIQUE le chargement, les signaux, le veto ES-04 et decide_score de
adr016_study.py (paramètres BASE de l'étude), et ne change que la RÉSOLUTION du STEP_UP.
"""

import sys
from collections import defaultdict
from pathlib import Path

src = (Path(__file__).parent / "adr016_study.py").read_text(encoding="utf-8")
src = src.split('res = {"fenetre_mesure"')[0]
src = src.replace('"ch": r["channel"],', '"ch": r["channel"], "db": r["device_bound"] == "true",', 1)
assert '"db": r["device_bound"]' in src
exec(compile(src, "adr016_study.py", "exec"))  # noqa: S102 — script local de diagnostic

P = variant(W={"newBeneficiary": 15, "newBeneficiaryHigh": 45, "vel": 70, "night": 25})  # valeurs écrites dans le ruleset
print("PARAMS", {k: P[k] for k in ("AGG", "SCORE", "W", "THR_HIGH", "NIGHT_LOCAL", "CAP_PROB")})
den_all, den_ussd = defaultdict(int), defaultdict(int)
ussd_users = defaultdict(set)
fz, fz_ussd = defaultdict(int), defaultdict(int)
su = {k: defaultdict(int) for k in ("ussd", "app", "ussd_db", "ussd_nodb", "app_nodb")}
cancel_users = {"D1": defaultdict(set), "D2": defaultdict(set)}
fz_users = defaultdict(set)

for u, xs in by_user.items():
    wins = prob_windows.get(u, [])
    state = {}
    for x in xs:
        if x["t"] < t0 + WARM:
            continue
        a = x["arch"]
        den_all[a] += 1
        if x["ch"] == "USSD":
            den_ussd[a] += 1
            ussd_users[a].add(u)
        if not x["out"]:
            continue
        d = None
        for te, case, entry in wins:  # veto ES-04, copie conforme de run()
            if not (te <= x["t"] < te + CAR):
                continue
            st = state.setdefault(te, {"spent": deque(), "lifted": False})
            if st["lifted"]:
                break
            while st["spent"] and st["spent"][0][0] <= x["t"] - 86400:
                st["spent"].popleft()
            limit = min(P["CAP_PROB"]["dailyXaf"], entry * P["CAP_PROB"]["balancePct"] // 100)
            if x["new"] or sum(m for _, m in st["spent"]) + x["amt"] > limit:
                if case == "a" and P["LIFT_A_BY_SIGNATURE"]:
                    st["lifted"] = True
                    d = "LIFTED"
                else:
                    d = "FREEZE"
            else:
                st["spent"].append((x["t"], x["amt"]))
            break
        if d is None:
            d = decide_score(x, P)
        if d == "FREEZE":
            fz[a] += 1
            fz_users[a].add(u)
            if x["ch"] == "USSD":
                fz_ussd[a] += 1
        elif d == "STEP_UP":
            if x["ch"] == "USSD":
                su["ussd"][a] += 1
                su["ussd_db" if x["db"] else "ussd_nodb"][a] += 1
                cancel_users["D1"][a].add(u)
                if not x["db"]:
                    cancel_users["D2"][a].add(u)
            else:
                su["app"][a] += 1
                if not x["db"]:
                    su["app_nodb"][a] += 1

print(f"{'persona':10s} | {'D1 FPR/tout':>11s} {'Wilson':>14s} | {'D1 FPR/USSD':>11s} {'Wilson':>14s} | {'D2 FPR/tout':>11s} | {'D2 FPR/USSD':>11s} | {'tout-annulé':>11s} | STEP_UP USSD lié/non-lié, app non-lié")
rows = {}
for a in ARCHS:
    d1 = fz[a] + su["ussd"][a]
    d2 = fz[a] + su["ussd_nodb"][a] + su["app_nodb"][a]
    d1u = fz_ussd[a] + su["ussd"][a]
    d2u = fz_ussd[a] + su["ussd_nodb"][a]
    worst = fz[a] + su["ussd"][a] + su["app"][a]
    rows[a] = (d1, d2, d1u, d2u, worst)
    print(f"{a:10s} | {pct(d1, den_all[a]):10.3f}% {str(wilson(d1, den_all[a])):>14s} | {pct(d1u, den_ussd[a]):10.3f}% {str(wilson(d1u, den_ussd[a])):>14s} | "
          f"{pct(d2, den_all[a]):10.3f}% | {pct(d2u, den_ussd[a]):10.3f}% | {pct(worst, den_all[a]):10.3f}% | "
          f"{su['ussd_db'][a]}/{su['ussd_nodb'][a]}, {su['app_nodb'][a]}  (tx USSD {den_ussd[a]}, tout {den_all[a]})")
print("usagers USSD touchés par une annulation (D1) :",
      {a: f"{len(cancel_users['D1'][a])}/{len(ussd_users[a])} = {pct(len(cancel_users['D1'][a]), len(ussd_users[a]))}%" for a in ARCHS})
print("usagers touchés par un FREEZE :", {a: f"{len(fz_users[a])}" for a in ARCHS})
SEUIL = 5.0  # ISC-EVAL §5.4, FPR par persona
over = [(a, k) for a in ARCHS if a != "agent" for k, v in zip(("D1/tout", "D2/tout", "D1/USSD", "D2/USSD"), rows[a][:4])
        if pct(v, den_all[a] if "tout" in k else den_ussd[a]) >= SEUIL]
print("DÉPASSEMENTS DU SEUIL EVAL (hors agents) :", over or "aucun")
sys.exit(1 if over else 0)
