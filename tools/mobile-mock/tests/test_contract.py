"""Tests de contrat : les 18 routes répondent selon contracts/mobile-api.yaml.

Le client (conftest) valide chaque réponse contre le contrat. Ce module vérifie en plus que le
parcours couvre EXACTEMENT les opérations du contrat et que les requêtes hors contrat sont refusées.
(contracts/evaluate.yaml inchangé : vérifié par `git diff` dans le rapport de M-03.)
"""

from conftest import (
    BOUND_MSISDN,
    CONTRACT,
    FIX,
    NEW_MSISDN,
    PIN,
    RECOVERY,
    SIG,
    b64,
)

from isc_mobile_mock import miniyaml

V1 = "/mobile/v1"


def _contract_operations():
    c = miniyaml.load(CONTRACT)
    return {(m.upper(), p) for p, item in c["paths"].items() for m in item}


def test_contract_declares_exactly_18_routes_and_closed_reason_list():
    assert len(_contract_operations()) == 18
    c = miniyaml.load(CONTRACT)
    reasons = c["components"]["schemas"]["ReasonCode"]["enum"]
    assert "CREDENTIALS_INVALID" in reasons and "ACCOUNT_NOT_FOUND" not in reasons
    assert len(reasons) == len(set(reasons)) == 15  # 14 de SPEC §12.4 + REQUEST_INVALID (M-03)
    assert "accountId|type|amountXaf|beneficiaryId|nonce" in c["info"]["description"]


def test_every_route_answers_according_to_the_contract(mock):
    """Un parcours qui touche les 18 routes ; chaque réponse est validée par le client."""
    # A : numéro neuf -> OTP -> liaison -> clé -> confirmation
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": NEW_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    mock.call("POST", f"{V1}/enrolment/otp", {"enrolmentId": eid, "otp": FIX["demoOtp"]})
    issued = mock.call(
        "POST",
        f"{V1}/enrolment/complete",
        {
            "enrolmentId": eid,
            "publicKey": b64("cle"),
            "attestationChain": [b64("cert")],
            "challengeSignature": SIG,
        },
    ).body
    confirm = {"enrolmentId": issued["enrolmentId"], "recoveryCode": issued["recoveryCode"]}
    token = mock.call("POST", f"{V1}/recovery-code/confirm", confirm).body["sessionToken"]
    # lectures, bénéficiaire, envoi différé puis annulé
    mock.call("GET", f"{V1}/account", token=token)
    mock.call("GET", f"{V1}/transactions", token=token)
    holder = mock.call("GET", f"{V1}/beneficiaries/{BOUND_MSISDN}/holder", token=token).body
    mock.app.state.accounts[NEW_MSISDN]["balanceXaf"] = 1000
    sent = mock.call(
        "POST",
        f"{V1}/transfers",
        {
            "type": "P2P",
            "amountXaf": 100,
            "beneficiaryId": holder["beneficiaryId"],
            "nonce": mock.nonce(token),
            "signature": SIG,
            "pin": PIN,
        },
        token=token,
    ).body
    mock.call(
        "POST", f"{V1}/transfers/{sent['transferId']}/cancel", mock.signed(token), token=token
    )
    # alertes
    alert = mock.app.state.raise_sim_change(NEW_MSISDN)
    mock.call("GET", f"{V1}/alerts", token=token)
    mock.call("POST", f"{V1}/alerts/{alert['alertId']}/ack", mock.signed(token), token=token)
    other = mock.app.state.raise_sim_change(NEW_MSISDN)
    mock.call("POST", f"{V1}/alerts/{other['alertId']}/not-me", mock.signed(token), token=token)
    # rotation de la clé de récupération, session quotidienne, déliaison
    rotated = mock.call("POST", f"{V1}/recovery-code/rotate", mock.signed(token), token=token).body
    assert rotated["recoveryCode"] != issued["recoveryCode"]
    token = mock.session(msisdn=NEW_MSISDN, device=issued["deviceId"])
    mock.call("DELETE", f"{V1}/device", mock.signed(token), token=token)
    # D : ré-enrôlement du compte déjà lié
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    mock.call("POST", f"{V1}/enrolment/recover", {"enrolmentId": eid, "recoveryCode": RECOVERY})
    assert mock.covered == _contract_operations()


def test_unbind_makes_next_enrolment_a_first_enrolment(mock):
    token = mock.session()
    assert mock.call("DELETE", f"{V1}/device", mock.signed(token), token=token).status == 200
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN})
    assert r.body["next"] == "OTP"  # plus de clé : premier enrôlement, donc OTP (SPEC §10)


def test_software_attestation_is_refused(mock):
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": NEW_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    mock.call("POST", f"{V1}/enrolment/otp", {"enrolmentId": eid, "otp": FIX["demoOtp"]})
    r = mock.call(
        "POST",
        f"{V1}/enrolment/complete",
        {
            "enrolmentId": eid,
            "publicKey": b64("cle"),
            "attestationChain": [b64("SOFTWARE-emulateur")],
            "challengeSignature": SIG,
        },
    )
    assert (r.status, r.body["reason"]) == (422, "DEVICE_UNSUPPORTED")


def test_out_of_contract_requests_are_refused(mock):
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN})  # PIN absent
    assert (r.status, r.body["reason"]) == (400, "REQUEST_INVALID")
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": "6770001", "pin": PIN})  # trop court
    assert (r.status, r.body["reason"]) == (400, "REQUEST_INVALID")
    r = mock.call("POST", f"{V1}/enrolment/start", raw=b"{pas du json")
    assert (r.status, r.body["reason"]) == (400, "REQUEST_INVALID")
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN, "x": 1})
    assert (r.status, r.body["reason"]) == (400, "REQUEST_INVALID")


def test_unknown_beneficiary_is_not_found(mock):
    token = mock.session()
    r = mock.call("GET", f"{V1}/beneficiaries/677000999/holder", token=token)
    assert (r.status, r.body["reason"]) == (404, "REQUEST_INVALID")
