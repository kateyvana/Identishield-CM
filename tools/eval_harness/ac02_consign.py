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

INTÉGRATION À VÉRIFIER PAR CLAUDE CODE contre le dépôt réel (marquées TODO-INT) :
  - la signature de la CLI `python -m isc_attacker ac02 --victim ... --victim-imsi
    ... --beneficiary ... --attacker-imsi ...` ;
  - les noms exacts des lignes ORACLE_* émises ;
  - la méthode de placement d'un solde fictif sur la victime (SQL admin, comme
    l'aide adm() de verify_attacker.sh) ;
  - l'endpoint de bascule de mode (CONSOLE/api/mode).
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
ATTACKER_IMAGE = os.environ.get("ATTACKER_IMAGE", "isc-attacker:latest")
NETWORK = os.environ.get("ISC_NET", "isc-net")

# Les 4 oracles d'AC-02 (ISC-TM §8). Noms lus dans la sortie de l'outil existant.
ORACLE_KEYS = [
    "ORACLE_BALANCE_FINAL",  # solde de la victime vidé (attendu 0)
    "ORACLE_OUTPUT_BENEFICIARY",  # sortie vers le bénéficiaire attaquant
    "ORACLE_OTP_TARGET_IMSI",  # OTP parti vers l'IMSI attaquant
    "ORACLE_OLD_DEVICE_NOTIFICATIONS",  # 0 notification à l'ancien appareil
]


def _run(cmd: list[str], timeout: int = 120) -> str:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False).stdout


def set_mode(layerless: bool) -> None:
    """Bascule le mode de la pile via l'API console (comme verify_attacker.sh)."""
    body = '{"layerless":true}' if layerless else '{"layerless":false}'
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
    # attendre la prise en compte
    for _ in range(15):
        out = _run(["curl", "-sS", f"{CONSOLE}/api/mode"])
        if ('"layerless"' in out) == layerless:
            return
        time.sleep(2)


def seed_victim(msisdn: str, imsi: str, balance: int) -> None:
    """Place un solde fictif sur un compte victime fictif dans la pile isolée.
    TODO-INT : adapter la requête au schéma réel (cf. adm() de verify_attacker.sh).
    Toutes les valeurs sont fictives (plage 677, IMSI 624-01)."""
    sql = (
        f"INSERT INTO account(msisdn, current_imsi, balance_xaf) "
        f"VALUES ('{msisdn}','{imsi}',{balance}) "
        f"ON CONFLICT (msisdn) DO UPDATE SET current_imsi=EXCLUDED.current_imsi, "
        f"balance_xaf=EXCLUDED.balance_xaf;"
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
    """Lance le scénario AC-02 EXISTANT sur un cas. Ne réécrit pas l'attaque."""
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
        ATTACKER_IMAGE,
        "python",
        "-m",
        "isc_attacker",
        "ac02",
        "--victim",
        c["victim_msisdn"],
        "--victim-imsi",
        c["victim_imsi"],
        "--beneficiary",
        c["beneficiary_msisdn"],
        "--attacker-imsi",
        c["attacker_imsi"],
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
    """Les 4 vérifications d'AC-02, telles que le Mois 2 les définit."""
    return {
        "balance_emptied": vals.get("ORACLE_BALANCE_FINAL") == "0",
        "out_to_attacker": vals.get("ORACLE_OUTPUT_BENEFICIARY") == c["beneficiary_msisdn"],
        "otp_to_attacker_imsi": vals.get("ORACLE_OTP_TARGET_IMSI") == c["attacker_imsi"],
        "no_old_device_notif": vals.get("ORACLE_OLD_DEVICE_NOTIFICATIONS") == "0",
    }


def consign(
    plan_csv: Path, out_view: Path, out_labels: Path, dry_run: bool = False
) -> tuple[str, str, int, int]:
    """Parcourt le plan, orchestre le scénario existant, écrit les deux vues.
    dry_run=True : n'appelle NI la pile NI Docker (pour les tests hors sandbox)."""
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
                seed_victim(c["victim_msisdn"], c["victim_imsi"], int(c["balance_xaf"]))
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
            label_rows.append({"run_id": c["run_id"], "label": "FRAUD", "source": "harness"})
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
        "--dry-run", action="store_true", help="ne touche NI la pile NI Docker (tests hors sandbox)"
    )
    a = p.parse_args()
    hv, hl, ok, fail = consign(a.plan, a.out_view, a.out_labels, a.dry_run)
    print(f"vue    {a.out_view} sha256 {hv}")
    print(f"labels {a.out_labels} sha256 {hl}")
    print(f"réussis={ok} échoués={fail}")


if __name__ == "__main__":
    main()
