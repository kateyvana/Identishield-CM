"""T3-04b — Consignateur de résultats d'évaluation AC-02.

Ce module N'EXÉCUTE PAS l'attaque lui-même. Il ORCHESTRE le scénario de test
AC-02 DÉJÀ PRÉSENT dans le dépôt (l'outil `isc_attacker ac02`, fusionné et vert
depuis le Mois 2), une fois par cas du plan figé, puis CONSIGNE le résultat de
ses oracles. Une seule définition d'AC-02 existe dans le projet : celle du dépôt.

Séparation stricte (comme en M4) :
  - vue d'évaluation  data/eval/ac02_runs.csv         : faits mesurés (oracles) ;
  - sidecar           data/eval/ac02_runs.labels.csv  : vérité-terrain (FRAUD).
La vérité-terrain ne rentre jamais dans une vue destinée au détecteur.

Rôle du propriétaire : c'est LUI qui décide de lancer ce consignateur, et la pile
isolée (isc-net, données fictives) est sous son contrôle. Le module bascule le
mode « couche éteinte » via l'API console existante avant, et restaure « protégé »
après, exactement comme tools/verify_attacker.sh.

INTÉGRATION VÉRIFIÉE le 2026-09-27 contre le dépôt réel (attacker/src/isc_attacker/
cli.py et tools/verify_attacker.sh) :
  - CLI : `python -m isc_attacker ac02` a SIX options obligatoires — --victim,
    --victim-imsi, --attacker-imsi, --beneficiary, --agent-id, --motif ;
  - oracles : lignes ORACLE_* de cli.py:223-231 ; ORACLE_OUTPUT_BENEFICIARY
    n'est qu'un écho de l'entrée, donc « sortie aboutie » se mesure sur
    ORACLE_OUTPUTS >= 1 et ORACLE_OUTPUT_STATE == EXECUTED ;
  - solde/HLR : core.account(msisdn, balance_xaf, created_at) et
    core.hlr_subscriber(msisdn, current_imsi, updated_at), via
    `docker exec isc-postgres psql -U isc_admin -d identishield -tAc` ;
  - mode : POST CONSOLE/api/mode, corps {"layerless": true|false}.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import subprocess
import time
from pathlib import Path

CONSOLE = os.environ.get("CONSOLE_URL", "http://localhost:3000")
NETWORK = os.environ.get("ISC_NET", "isc-net")
# Racine du dépôt (montée dans l'image python, cf. verify_attacker.sh).
REPO_ROOT = os.environ.get("ISC_REPO_ROOT", os.getcwd())

# Paramètres attaquant du scénario AC-02, alignés sur tools/verify_attacker.sh
# (valeurs éprouvées). Ce ne sont pas des données de victime : ils décrivent le
# mode opératoire fixe de l'attaquant, identique pour les N cas du plan.
ATTACKER_AGENT_ID = os.environ.get("ISC_ATTACK_AGENT_ID", "agent-kyc-07")
ATTACK_MOTIF = os.environ.get("ISC_ATTACK_MOTIF", "remplacement SIM")

# Lignes ORACLE_* de l'outil existant (cli.py:223-231) lues pour statuer.
ORACLE_KEYS = [
    "ORACLE_BALANCE_FINAL",  # solde final de la victime (0 si l'attaque aboutit)
    "ORACLE_OUTPUTS",  # nombre de sorties exécutées
    "ORACLE_OUTPUT_STATE",  # état de la sortie (EXECUTED si aboutie)
    "ORACLE_OUTPUT_BENEFICIARY",  # bénéficiaire (écho de l'entrée, non probant seul)
    "ORACLE_OTP_TARGET_IMSI",  # IMSI cible de l'OTP consommé
    "ORACLE_OLD_DEVICE_NOTIFICATIONS",  # notifications à l'ancien appareil (0 = furtif)
]


def _run(cmd: list[str], timeout: int = 120) -> str:
    return subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout, check=False
    ).stdout


def set_mode(layerless: bool) -> None:
    """Bascule le mode de la pile via l'API console (comme verify_attacker.sh).

    Corps JSON {"layerless": true|false}. On attend ensuite que GET /api/mode
    réponde avec la clé "layerless" présente : c'est la porte de disponibilité
    console du script de référence (ligne 31), pas une confirmation de valeur.
    """
    body = '{"layerless": true}' if layerless else '{"layerless": false}'
    _run(
        [
            "curl",
            "-sS",
            "-X",
            "POST",
            f"{CONSOLE}/api/mode",
            "-H",
            "Content-Type: application/json",
            "-d",
            body,
        ]
    )
    for _ in range(15):
        out = _run(["curl", "-sS", f"{CONSOLE}/api/mode"])
        if '"layerless"' in out:
            return
        time.sleep(2)


def seed_victim(victim: str, victim_imsi: str, beneficiary: str, balance: int) -> None:
    """Prépare un cas dans la pile isolée, à l'identique de tools/verify_attacker.sh :
    purge l'état antérieur de la victime, place un solde fictif sur son compte, crée
    le compte bénéficiaire à 0, fixe l'IMSI courant de la victime dans le HLR.

    Schéma réel : core.account(msisdn, balance_xaf, created_at) et
    core.hlr_subscriber(msisdn, current_imsi, updated_at). Toutes les valeurs sont
    fictives (plage 677, IMSI 624-01) et proviennent du plan figé ; la pile est
    jetable. Interpolation f-string alignée sur le script de référence — si l'aide
    adm() offre un jour une requête paramétrée, la préférer.
    """
    sql = (
        f"DELETE FROM core.transaction WHERE account_id IN "
        f"(SELECT id FROM core.account WHERE msisdn IN ('{victim}','{beneficiary}')); "
        f"DELETE FROM core.notification WHERE msisdn='{victim}'; "
        f"DELETE FROM core.otp WHERE msisdn='{victim}'; "
        f"DELETE FROM core.hlr_reassignment WHERE msisdn='{victim}'; "
        f"INSERT INTO core.account(msisdn, balance_xaf, created_at) "
        f"VALUES ('{victim}', {balance}, now()) "
        f"ON CONFLICT (msisdn) DO UPDATE SET balance_xaf={balance}; "
        f"INSERT INTO core.account(msisdn, balance_xaf, created_at) "
        f"VALUES ('{beneficiary}', 0, now()) ON CONFLICT (msisdn) DO NOTHING; "
        f"INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) "
        f"VALUES ('{victim}', '{victim_imsi}', now()) "
        f"ON CONFLICT (msisdn) DO UPDATE SET "
        f"current_imsi='{victim_imsi}', updated_at=now();"
    )
    _run(
        [
            "docker",
            "exec",
            "isc-postgres",
            "psql",
            "-U",
            "isc_admin",
            "-d",
            "identishield",
            "-tAc",
            sql,
        ]
    )


def run_scenario(c: dict) -> str:
    """Lance le scénario AC-02 EXISTANT sur un cas (ne réécrit pas l'attaque).

    Invocation alignée sur verify_attacker.sh : image python:3.12-alpine, source
    de l'attaquant montée en lecture seule, PYTHONPATH=/src, six options CLI.
    """
    cmd = [
        "docker",
        "run",
        "--rm",
        "--network",
        NETWORK,
        "-e",
        "CORE_URL=http://core:8080",
        "-e",
        "SIM_URL=http://sim:8080",
        "-e",
        "PYTHONPATH=/src",
        "-v",
        f"{REPO_ROOT}/attacker/src:/src:ro",
        "python:3.12-alpine",
        "python",
        "-m",
        "isc_attacker",
        "ac02",
        "--victim",
        c["victim_msisdn"],
        "--victim-imsi",
        c["victim_imsi"],
        "--attacker-imsi",
        c["attacker_imsi"],
        "--beneficiary",
        c["beneficiary_msisdn"],
        "--agent-id",
        ATTACKER_AGENT_ID,
        "--motif",
        ATTACK_MOTIF,
    ]
    return _run(cmd)


def parse_oracles(output: str) -> dict[str, str]:
    vals: dict[str, str] = {}
    for line in output.splitlines():
        if "=" in line:
            k, _, v = line.partition("=")
            k = k.strip()
            if k in ORACLE_KEYS:
                vals[k] = v.strip()
    return vals


def oracle_verdict(c: dict, vals: dict[str, str]) -> dict[str, bool]:
    """Les 4 vérifications d'AC-02 (ISC-TM §8), lues sur les lignes ORACLE_*.

    `out_to_attacker` exige une sortie EXÉCUTÉE vers le bénéficiaire attaquant :
    ORACLE_OUTPUT_BENEFICIARY échoit l'entrée, donc le signal probant est
    ORACLE_OUTPUTS >= 1 et ORACLE_OUTPUT_STATE == EXECUTED (cf. la vérif SQL de
    verify_attacker.sh : count(*) … state='EXECUTED').
    """
    try:
        outputs = int(vals.get("ORACLE_OUTPUTS", "0"))
    except ValueError:
        outputs = 0
    return {
        "balance_emptied": vals.get("ORACLE_BALANCE_FINAL") == "0",
        "out_to_attacker": (
            outputs >= 1
            and vals.get("ORACLE_OUTPUT_STATE") == "EXECUTED"
            and vals.get("ORACLE_OUTPUT_BENEFICIARY") == c["beneficiary_msisdn"]
        ),
        "otp_to_attacker_imsi": vals.get("ORACLE_OTP_TARGET_IMSI")
        == c["attacker_imsi"],
        "no_old_device_notif": vals.get("ORACLE_OLD_DEVICE_NOTIFICATIONS") == "0",
    }


def consign(
    plan_csv: Path, out_view: Path, out_labels: Path, dry_run: bool = False
) -> tuple[str, str, int, int]:
    """Parcourt le plan, orchestre le scénario existant, écrit les deux vues.

    dry_run=True : n'appelle NI la pile NI Docker (pour les tests hors sandbox).
    """
    with plan_csv.open(encoding="utf-8", newline="") as f:
        cases = list(csv.DictReader(f))

    view_fields = [
        "run_id",
        "victim_msisdn",
        "instant",
        "balance_emptied",
        "out_to_attacker",
        "otp_to_attacker_imsi",
        "no_old_device_notif",
        "all_oracles_ok",
        "duration_s",
    ]
    label_fields = ["run_id", "label", "source"]
    view_rows, label_rows = [], []
    ok = fail = 0

    if not dry_run:
        set_mode(layerless=True)
    try:
        for c in cases:
            t0 = time.time()
            if dry_run:
                verdict = {
                    k: True
                    for k in (
                        "balance_emptied",
                        "out_to_attacker",
                        "otp_to_attacker_imsi",
                        "no_old_device_notif",
                    )
                }
                dur = 0.0
            else:
                seed_victim(
                    c["victim_msisdn"],
                    c["victim_imsi"],
                    c["beneficiary_msisdn"],
                    int(c["balance_xaf"]),
                )
                out = run_scenario(c)
                verdict = oracle_verdict(c, parse_oracles(out))
                dur = round(time.time() - t0, 3)
            all_ok = all(verdict.values())
            ok += all_ok
            fail += not all_ok
            view_rows.append(
                {
                    "run_id": c["run_id"],
                    "victim_msisdn": c["victim_msisdn"],
                    "instant": c["instant"],
                    **{k: str(v).lower() for k, v in verdict.items()},
                    "all_oracles_ok": str(all_ok).lower(),
                    "duration_s": dur,
                }
            )
            label_rows.append(
                {"run_id": c["run_id"], "label": "FRAUD", "source": "harness"}
            )
    finally:
        if not dry_run:
            set_mode(layerless=False)

    hv = _write(out_view, view_fields, view_rows)
    hl = _write(out_labels, label_fields, label_rows)
    return hv, hl, ok, fail


def _write(path: Path, fields: list[str], rows: list[dict]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [",".join(fields)]
    for r in rows:
        lines.append(",".join(str(r[k]) for k in fields))
    text = "\n".join(lines) + "\n"
    path.write_text(text, encoding="utf-8", newline="")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    p = argparse.ArgumentParser(
        description="Consigne le résultat du scénario AC-02 existant, cas par cas."
    )
    p.add_argument("--plan", required=True, type=Path)
    p.add_argument("--out-view", required=True, type=Path)
    p.add_argument("--out-labels", required=True, type=Path)
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="ne touche NI la pile NI Docker (tests hors sandbox)",
    )
    a = p.parse_args()
    hv, hl, ok, fail = consign(a.plan, a.out_view, a.out_labels, a.dry_run)
    print(f"vue    {a.out_view} sha256 {hv}")
    print(f"labels {a.out_labels} sha256 {hl}")
    print(f"réussis={ok} échoués={fail}")


if __name__ == "__main__":
    main()
