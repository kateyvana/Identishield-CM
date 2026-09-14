"""Outil agent operateur HLR (EF-42, UC-11) : remplacement de SIM en une commande.

Cet outil simule un agent d'agence. Le systeme ne distingue PAS un agent honnete d'un agent
complice : le meme appel, avec les memes parametres, sert a la reconduction legitime d'une victime
et au vol d'un attaquant (ADR-011). C'est tout l'interet de la demonstration. Il n'existe donc AUCUN
mode « frauduleux », aucun drapeau d'intention.

Le motif declare est journalise par le coeur (HlrService, T2-10) ; il n'est jamais transmis au PDP
(ES-16b). Stdlib uniquement (argparse + urllib) : aucune dependance hors stack.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def build_body(msisdn: str, new_imsi: str, agent_id: str, motif: str) -> dict[str, str]:
    """Charge utile de la reattribution. Exactement quatre champs ; jamais d'indicateur d'intention."""
    return {"msisdn": msisdn, "newImsi": new_imsi, "agentId": agent_id, "reason": motif}


def reassign(
    core_url: str, msisdn: str, new_imsi: str, agent_id: str, motif: str
) -> tuple[int, dict]:
    """POST /hlr/reassign contre le coeur. Renvoie (code HTTP, corps JSON). 0 si coeur injoignable."""
    data = json.dumps(build_body(msisdn, new_imsi, agent_id, motif)).encode("utf-8")
    request = urllib.request.Request(
        core_url.rstrip("/") + "/hlr/reassign",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as exc:
        try:
            payload = json.load(exc)
        except (ValueError, OSError):
            payload = {"error": exc.reason}
        return exc.code, payload
    except urllib.error.URLError as exc:
        return 0, {"error": f"coeur injoignable : {exc.reason}"}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="isc-hlr-agent",
        description="Outil agent operateur : remplacement de SIM avec motif et identifiant d'agent (EF-42).",
    )
    sub = parser.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("reassign", help="reattribuer la SIM d'un numero a un nouvel IMSI")
    r.add_argument("--msisdn", required=True, help="numero vise")
    r.add_argument("--new-imsi", required=True, help="nouvel IMSI")
    r.add_argument("--agent-id", required=True, help="identifiant de l'agent")
    r.add_argument(
        "--motif", required=True, help="motif declare (journalise, jamais transmis au PDP)"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    core_url = os.environ.get("CORE_URL", "http://core:8080")
    if args.cmd == "reassign":
        status, payload = reassign(core_url, args.msisdn, args.new_imsi, args.agent_id, args.motif)
        if status == 200:
            print(
                "REASSIGN_OK"
                f" msisdn={payload['msisdn']}"
                f" old={payload['oldImsi']}"
                f" new={payload['newImsi']}"
                f" at={payload['changedAt']}"
                f" event_emitted={payload['eventEmitted']}"
            )
            print(
                "evenement de changement d'IMSI emis : "
                f"{payload['oldImsi']} -> {payload['newImsi']}"
            )
            return 0
        print(
            f"REASSIGN_REFUSED status={status} : {payload.get('error', 'erreur inconnue')}",
            file=sys.stderr,
        )
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
