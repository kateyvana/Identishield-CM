"""Test de contrat contre le VRAI cœur (carte B3a, #46) : chaque réponse des routes /mobile/v1 du
premier enrôlement est validée contre contracts/mobile-api.yaml par LE validateur du faux serveur
(isc_mobile_mock.schema) — un seul validateur, jamais deux.

Lancé DANS la VM, dans un conteneur attaché au réseau fermé isc-net (tools/verify_mobile_contract.sh),
contre la pile démarrée avec la surcouche infra/compose.b3a.yml (profil demo, comptes de test).
Bibliothèque standard seule. Aucun secret affiché : ni PIN, ni OTP, ni défi, ni code.

Étape 3 de l'ordre de la propriétaire (2026-10-23) : cas de REFUS et succès de start/otp, sans
téléphone. Les succès de complete et confirm exigent une vraie attestation matérielle (étape 4).
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from isc_mobile_mock import miniyaml, schema

CORE = "http://core:8080/mobile/v1"
SIM = "http://sim:8080"
CONTRACT = miniyaml.load(Path("/contracts/mobile-api.yaml"))
FIXTURES = json.loads(Path("/fixtures/fixtures.json").read_text(encoding="utf-8"))

failures = []


def call(method, path, body=None, base=CORE):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read() or b"{}"), r.headers
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else None), e.headers


def check(name, method, template, path, body, status, reason=None):
    """Appelle le vrai cœur et valide la réponse contre le contrat ; renvoie le corps."""
    got, data, headers = call(method, path, body)
    try:
        sch = schema.response_schema(CONTRACT, method, template, got)
        schema.validate(CONTRACT, sch, data)
        if got != status:
            raise AssertionError(f"code {got}, attendu {status}")
        if reason is not None and (data or {}).get("reason") != reason:
            raise AssertionError(f"raison {(data or {}).get('reason')}, attendue {reason}")
        if got == 503 and not headers.get("Retry-After"):
            raise AssertionError("503 sans Retry-After")
        print(f"  OK     {name} : {got}{' ' + reason if reason else ''}")
    except (schema.ContractViolation, AssertionError, KeyError, TypeError) as e:
        failures.append(name)
        print(f"  ECHEC  {name} : {e}")
    return data or {}


def main():
    accounts = FIXTURES["accounts"]
    # Compte remis à zéro par tools/verify_mobile_contract.sh avant chaque passage (rejouable).
    msisdn = os.environ["CONTRACT_MSISDN"]
    fresh = next(
        (
            a
            for a in accounts
            if a["msisdn"] == msisdn and a.get("pin") and not a.get("boundDeviceId")
        ),
        None,
    )
    if fresh is None:
        failures.append("compte de test")
        print(f"  ECHEC  compte de test {msisdn} : absent des fixtures, sans PIN, ou deja lie")
        return
    known = {a["msisdn"] for a in accounts} | {FIXTURES["freezeBeneficiaryMsisdn"]}
    unknown = next(f"67700{n:04d}" for n in range(9990, 9999) if f"67700{n:04d}" not in known)
    pin = fresh["pin"]
    wrong_pin = "0" * len(pin) if pin != "0" * len(pin) else "1" * len(pin)
    imsi = "624010" + fresh["msisdn"]
    start = "/enrolment/start"

    print(">>> /enrolment/start")
    check(
        "compte inconnu",
        "POST",
        start,
        start,
        {"msisdn": unknown, "pin": pin},
        401,
        "CREDENTIALS_INVALID",
    )
    check(
        "PIN faux",
        "POST",
        start,
        start,
        {"msisdn": fresh["msisdn"], "pin": wrong_pin},
        401,
        "CREDENTIALS_INVALID",
    )
    check(
        "champ inconnu",
        "POST",
        start,
        start,
        {"msisdn": fresh["msisdn"], "pin": pin, "extra": 1},
        400,
        "REQUEST_INVALID",
    )
    started = check(
        "compte neuf -> OTP", "POST", start, start, {"msisdn": fresh["msisdn"], "pin": pin}, 200
    )
    if started.get("next") != "OTP" or "enrolmentId" not in started:
        failures.append("compte neuf -> OTP (contenu)")
        print("  ECHEC  compte neuf -> OTP : next ou enrolmentId absent")
        return
    eid = started["enrolmentId"]

    print(">>> /enrolment/otp")
    _, inbox, _ = call("GET", f"/inbox?imsi={imsi}", base=SIM)
    otp = (inbox or {}).get("code", "")
    wrong_otp = "".join("1" if c != "1" else "2" for c in otp) or "000000"
    check(
        "OTP faux",
        "POST",
        "/enrolment/otp",
        "/enrolment/otp",
        {"enrolmentId": eid, "otp": wrong_otp},
        401,
        "OTP_INVALID",
    )
    bound = check(
        "OTP lu sur l'IMSI courant -> BIND",
        "POST",
        "/enrolment/otp",
        "/enrolment/otp",
        {"enrolmentId": eid, "otp": otp},
        200,
    )
    if bound.get("next") != "BIND":
        failures.append("OTP -> BIND (contenu)")
        print("  ECHEC  OTP -> BIND : next absent")

    print(">>> /enrolment/complete")
    junk = base64.b64encode(b"pas un certificat").decode()
    check(
        "chaine d'attestation invalide",
        "POST",
        "/enrolment/complete",
        "/enrolment/complete",
        {
            "enrolmentId": eid,
            "publicKey": junk,
            "attestationChain": [junk],
            "challengeSignature": junk,
        },
        422,
        "ATTESTATION_REJECTED",
    )
    check(
        "enrolement inconnu",
        "POST",
        "/enrolment/complete",
        "/enrolment/complete",
        {
            "enrolmentId": str(uuid.UUID(int=1)),
            "publicKey": junk,
            "attestationChain": [junk],
            "challengeSignature": junk,
        },
        400,
        "REQUEST_INVALID",
    )

    print(">>> /recovery-code/confirm")
    confirm = "/recovery-code/confirm"
    check(
        "enrolement a la mauvaise etape",
        "POST",
        confirm,
        confirm,
        {"enrolmentId": eid, "recoveryCode": "ABCDEF"},
        400,
        "REQUEST_INVALID",
    )
    check(
        "enrolement inconnu",
        "POST",
        confirm,
        confirm,
        {"enrolmentId": str(uuid.UUID(int=2)), "recoveryCode": "ABCDEF"},
        400,
        "REQUEST_INVALID",
    )


if __name__ == "__main__":
    main()
    if failures:
        print(f"verify_mobile_contract : ROUGE ({len(failures)} echec(s))")
        sys.exit(1)
    print("verify_mobile_contract : VERT")
