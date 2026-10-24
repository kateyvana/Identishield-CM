"""Test de contrat contre le VRAI cœur (carte B3a, #46) : chaque réponse des routes /mobile/v1 du
premier enrôlement est validée contre contracts/mobile-api.yaml par LE validateur du faux serveur
(isc_mobile_mock.schema) — un seul validateur, jamais deux.

Lancé DANS la VM, dans un conteneur attaché au réseau fermé isc-net (tools/verify_mobile_contract.sh),
contre la pile démarrée avec la surcouche infra/compose.b3a.yml (profil demo, comptes de test).
Bibliothèque standard seule. Aucun secret affiché : ni PIN, ni OTP, ni défi, ni code.

Étape 3 de l'ordre de la propriétaire (2026-10-23) : cas de REFUS et succès de start/otp, sans
téléphone. Les succès de complete et confirm exigent une vraie attestation matérielle (étape 4).
Carte B4 (#47) : défi de session d'un appareil inconnu (leurre de même forme) et refus de /session ;
le succès de /session exige une clé ACTIVE née dans une puce (même étape 4).
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


def check(name, method, template, path, body, status, reason=None, next_step=None, required=()):
    """Appelle le vrai cœur, valide la réponse contre le contrat PUIS son contenu attendu (étape
    suivante, champs requis) ; UN seul verdict par cas. Renvoie le corps, ou None si échec."""
    got, data, headers = call(method, path, body)
    data = data or {}
    try:
        sch = schema.response_schema(CONTRACT, method, template, got)
        schema.validate(CONTRACT, sch, data)
        if got != status:
            raise AssertionError(f"code {got}, attendu {status}")
        if reason is not None and data.get("reason") != reason:
            raise AssertionError(f"raison {data.get('reason')}, attendue {reason}")
        if next_step is not None and data.get("next") != next_step:
            raise AssertionError(f"etape {data.get('next')}, attendue {next_step}")
        missing = [f for f in required if f not in data]
        if missing:
            raise AssertionError(f"champs attendus absents : {missing}")
        if got == 503 and not headers.get("Retry-After"):
            raise AssertionError("503 sans Retry-After")
        print(f"  OK     {name} : {got}{' ' + (reason or next_step or '')}")
        return data
    except (schema.ContractViolation, AssertionError, KeyError, TypeError) as e:
        failures.append(name)
        print(f"  ECHEC  {name} : {e}")
        return None


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
        "compte neuf -> OTP",
        "POST",
        start,
        start,
        {"msisdn": fresh["msisdn"], "pin": pin},
        200,
        next_step="OTP",
        required=("enrolmentId",),
    )
    if started is None:
        return  # sans enrôlement ouvert, la suite n'a pas de sens
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
    check(
        "OTP lu sur l'IMSI courant -> BIND",
        "POST",
        "/enrolment/otp",
        "/enrolment/otp",
        {"enrolmentId": eid, "otp": otp},
        200,
        next_step="BIND",
        required=("attestationChallenge",),
    )

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

    # Carte B4 : défi et ouverture de session. Sans téléphone, aucune clé ACTIVE : le défi d'un
    # appareil inconnu est un leurre de même forme, et toute ouverture est refusée uniformément.
    print(">>> /session/challenge")
    challenge_t = "/session/challenge"
    device = str(uuid.UUID(int=0x6F0A2C1E))
    check(
        "identifiant hors forme",
        "GET",
        challenge_t,
        challenge_t + "?deviceId=pas-un-uuid",
        None,
        400,
        "REQUEST_INVALID",
    )
    issued = check(
        "appareil inconnu -> defi de meme forme",
        "GET",
        challenge_t,
        challenge_t + "?deviceId=" + device,
        None,
        200,
        required=("challenge",),
    )
    challenge = (issued or {}).get("challenge") or "0" * 32

    print(">>> /session")
    session = "/session"
    signed = {
        "msisdn": fresh["msisdn"],
        "pin": pin,
        "deviceId": device,
        "challenge": challenge,
        "signature": junk,
    }
    check(
        "champ manquant",
        "POST",
        session,
        session,
        {"msisdn": fresh["msisdn"]},
        400,
        "REQUEST_INVALID",
    )
    check(
        "PIN faux",
        "POST",
        session,
        session,
        {**signed, "pin": wrong_pin},
        401,
        "CREDENTIALS_INVALID",
    )
    check(
        "PIN juste, aucune cle ACTIVE -> refus uniforme",
        "POST",
        session,
        session,
        signed,
        401,
        "CREDENTIALS_INVALID",
    )
    daily_without_session(junk)


def daily_without_session(junk):
    """Carte B6 : les routes du quotidien derrière le jeton porteur. Sans jeton, chacune repond
    401 CREDENTIALS_INVALID AVANT tout controle de forme (ES-22) : une annulation mal formee
    sans jeton recoit le meme 401, pas un 400."""
    print("Routes du quotidien sans session (B6)")
    nonce = "9f86d081884c7d659a2feaa0c55ad015"
    transfer = "11111111-2222-4333-8444-555555555555"
    for name, method, template, path, body in [
        ("nonce sans jeton", "POST", "/nonce", "/nonce", None),
        ("historique sans jeton", "GET", "/transactions", "/transactions", None),
        (
            "titulaire sans jeton",
            "GET",
            "/beneficiaries/{msisdn}/holder",
            "/beneficiaries/677001101/holder",
            None,
        ),
        (
            "annulation sans jeton",
            "POST",
            "/transfers/{transferId}/cancel",
            f"/transfers/{transfer}/cancel",
            {"nonce": nonce, "signature": junk},
        ),
        (
            "annulation mal formee sans jeton -> 401, pas 400",
            "POST",
            "/transfers/{transferId}/cancel",
            "/transfers/pas-un-uuid/cancel",
            {"nonce": "court"},
        ),
        ("alertes sans jeton", "GET", "/alerts", "/alerts", None),
    ]:
        check(name, method, template, path, body, 401, "CREDENTIALS_INVALID")


if __name__ == "__main__":
    main()
    if failures:
        print(f"verify_mobile_contract : ROUGE ({len(failures)} echec(s))")
        sys.exit(1)
    print("verify_mobile_contract : VERT")
