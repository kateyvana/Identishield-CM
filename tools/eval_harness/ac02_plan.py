"""T3-04b — Plan d'échantillonnage figé pour l'évaluation d'AC-02.

Ce module NE lance AUCUNE attaque. Il produit UNIQUEMENT une liste déterministe
de cas à évaluer plus tard (data/eval/ac02_plan.csv). C'est un fichier de
données, pas un outil d'exécution.

Chaque cas est une victime tirée parmi les personas « ordinaire » du jeu de dev
(vue détecteur), à un instant tiré, en excluant les usagers déjà porteurs d'un
changement de SIM légitime dans une fenêtre proche (sinon on mélange le signal
« fraude » et le signal « remplacement légitime »).

L'archétype (vérité-terrain) vit dans le SIDECAR, qui se joint à la vue détecteur
par `id` (le sidecar réel n'a pas de colonne `user`). Il n'est lu que pour choisir
les victimes ; il ne ressort jamais dans le plan (aucune colonne d'archétype).

Le suréchantillonnage est ASSUMÉ et DÉCLARÉ : N cas ne représentent pas la
fréquence réelle de la fraude ; ils servent à obtenir une puissance statistique
(règle de trois) sur la mesure du Mois 5.

Reproductibilité : tout est dérivé de PLAN_SEED. Deux exécutions produisent un
fichier identique à l'octet.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import random
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
from pathlib import Path

# --- constantes de l'évaluation (paramètres nommés, pas de valeur en dur ailleurs)
PLAN_SEED = 20260927  # graine maîtresse du plan
N_CASES = 300  # nombre de cas (règle de trois : 0/300 -> <1 % à 95 %)
EXCLUSION_WINDOW_HOURS = 72  # fenêtre autour d'un événement SIM légitime à éviter
ORDINARY = "ordinaire"  # archétype ciblé (usager ordinaire = victime plausible)

# Identifiants attaquant, FICTIFS, hors de la plage des usagers (plage 677 00x xxx).
# Le bénéficiaire attaquant est un compte fictif du même opérateur simulé.
ATTACKER_BENEFICIARY = "677009000"  # dans la plage fictive, réservé à l'attaquant
# IMSI attaquant : vrai IMSI 624-01 sur 15 chiffres (62401 + MSIN 10 chiffres).
# Bloc MSIN 9999999NNN, distinct des usagers (0000000…) et des remplacements
# légitimes (1…). Le compteur NNN (3 chiffres) suppose N_CASES < 1000.
ATTACKER_IMSI_PREFIX = "624019999999"  # 12 chiffres ; + compteur 3 chiffres = 15


@dataclass(frozen=True)
class Case:
    run_id: str
    victim_msisdn: str
    victim_imsi: str
    instant: str  # ISO-8601 UTC : l'instant simulé du rejeu
    beneficiary_msisdn: str
    attacker_imsi: str
    balance_xaf: int  # solde fictif à placer sur la victime avant le rejeu


def _read_detector_view(path: Path) -> dict[str, dict]:
    """Renvoie, par usager, son current_imsi le plus récent et ses montants
    observés (pour placer un solde plausible). Lecture seule, aucune écriture."""
    users: dict[str, dict] = {}
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            u = row["user"]
            d = users.setdefault(u, {"imsi": row["current_imsi"], "amounts": []})
            d["imsi"] = row["current_imsi"]  # la dernière valeur lue fait foi
            try:
                d["amounts"].append(int(row["amount"]))
            except (KeyError, ValueError):
                pass
    return users


def _read_sim_events(path: Path) -> dict[str, list[datetime]]:
    """Instants des changements d'IMSI légitimes, par MSISDN (vue HLR)."""
    events: dict[str, list[datetime]] = {}
    if not path.exists():
        return events
    with path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            ts = datetime.fromisoformat(row["timestamp"])
            events.setdefault(row["msisdn"], []).append(ts)
    return events


def _archetype_by_user(detector: Path, sidecar: Path) -> dict[str, str]:
    """user -> archétype, par jointure `id` : le sidecar réel n'a pas de colonne
    `user` (il se joint à la vue détecteur par `id`, comme voulu en M4). On lit
    d'abord id -> user dans la vue, puis id -> archétype dans le sidecar.
    La vérité-terrain ne sert qu'à choisir des victimes ; elle ne sort pas d'ici."""
    id_to_user: dict[str, str] = {}
    with detector.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if row.get("id") and row.get("user"):
                id_to_user[row["id"]] = row["user"]
    m: dict[str, str] = {}
    with sidecar.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            # sidecar réel : jointure par id ; ancien format toléré : colonne user.
            u = row.get("user") or id_to_user.get(row.get("id", ""))
            a = row.get("archetype", "")
            if u and a:
                m[u] = a
    return m


def _median(xs: list[int]) -> int:
    if not xs:
        return 50000
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) // 2


def build_plan(
    detector_csv: Path,
    sidecar_csv: Path,
    hlr_csv: Path,
    users_index_csv: Path | None = None,
) -> list[Case]:
    """Construit la liste déterministe des N cas. Aucune exécution."""
    rng = random.Random(PLAN_SEED)
    users = _read_detector_view(detector_csv)
    events = _read_sim_events(hlr_csv)

    # archétype par usager : depuis un index dédié (user,archetype) si fourni,
    # sinon par jointure id depuis le sidecar (vérité-terrain).
    if users_index_csv and users_index_csv.exists():
        arch = {}
        with users_index_csv.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                arch[row["user"]] = row.get("archetype", "")
    else:
        arch = _archetype_by_user(detector_csv, sidecar_csv)

    # usagers éligibles : ordinaires, connus de la vue détecteur.
    eligible = sorted(u for u in users if arch.get(u) == ORDINARY)
    if len(eligible) < N_CASES:
        raise ValueError(
            f"Seulement {len(eligible)} usagers '{ORDINARY}' pour {N_CASES} cas : "
            f"utiliser le jeu horizon (pas l'échantillon de contrôle) et vérifier "
            f"la jointure d'archétype (le sidecar se joint par 'id', sans 'user')."
        )
    chosen = rng.sample(eligible, N_CASES)

    # horizon des instants : borné par les timestamps observés
    all_ts = []
    with detector_csv.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            all_ts.append(row["timestamp"])
    t_min = datetime.fromisoformat(min(all_ts))
    t_max = datetime.fromisoformat(max(all_ts))
    span = int((t_max - t_min).total_seconds())

    cases: list[Case] = []
    for i, u in enumerate(chosen):
        # instant tiré, en évitant la fenêtre d'un événement SIM légitime
        for _ in range(50):
            t = t_min + timedelta(seconds=rng.randint(0, max(1, span)))
            near = any(
                abs((t - e).total_seconds()) < EXCLUSION_WINDOW_HOURS * 3600
                for e in events.get(u, [])
            )
            if not near:
                break
        bal = _median(users[u]["amounts"]) * 10 or 500000  # solde plausible à vider
        cases.append(
            Case(
                run_id=f"AC02-{i + 1:04d}",
                victim_msisdn=u,
                victim_imsi=users[u]["imsi"],
                instant=t.strftime("%Y-%m-%dT%H:%M:%SZ"),
                beneficiary_msisdn=ATTACKER_BENEFICIARY,
                attacker_imsi=f"{ATTACKER_IMSI_PREFIX}{i:03d}",
                balance_xaf=int(bal),
            )
        )
    return cases


def write_plan(cases: list[Case], out: Path) -> str:
    out.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "run_id",
        "victim_msisdn",
        "victim_imsi",
        "instant",
        "beneficiary_msisdn",
        "attacker_imsi",
        "balance_xaf",
    ]
    # écriture déterministe : fin de ligne \n, ordre fixe
    lines = [",".join(fields)]
    for c in cases:
        d = asdict(c)
        lines.append(",".join(str(d[k]) for k in fields))
    text = "\n".join(lines) + "\n"
    out.write_text(text, encoding="utf-8", newline="")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    p = argparse.ArgumentParser(description="Plan figé d'évaluation AC-02 (aucune exécution).")
    p.add_argument("--detector", required=True, type=Path)
    p.add_argument("--sidecar", required=True, type=Path)
    p.add_argument("--hlr", required=True, type=Path)
    p.add_argument(
        "--users-index",
        type=Path,
        default=None,
        help="CSV optionnel user,archetype (sinon jointure id du sidecar)",
    )
    p.add_argument("--out", required=True, type=Path)
    a = p.parse_args()
    cases = build_plan(a.detector, a.sidecar, a.hlr, a.users_index)
    h = write_plan(cases, a.out)
    print(f"{len(cases)} cas -> {a.out} (sha256 {h})")


if __name__ == "__main__":
    main()
