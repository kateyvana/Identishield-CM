"""Scénarios des flux A, B, C, D et F d'ISC-MOB-SPEC v1.2 contre le faux serveur.

Toute réponse est validée contre le contrat par le client (conftest). Les attendus viennent des
fixtures et de config/ruleset.yaml, jamais de littéraux recopiés.
"""

from conftest import (
    AMINA,
    BOUND_DEVICE,
    BOUND_MSISDN,
    FIX,
    NEW_MSISDN,
    PARAMS,
    PIN,
    RECOVERY,
    SIG,
    b64,
)

V1 = "/mobile/v1"


def _complete(mock, enrolment_id):
    r = mock.call(
        "POST",
        f"{V1}/enrolment/complete",
        {
            "enrolmentId": enrolment_id,
            "publicKey": b64("cle-publique"),
            "attestationChain": [b64("certificat-feuille"), b64("certificat-racine")],
            "challengeSignature": SIG,
        },
    )
    assert r.status == 200, r.body
    return r.body


def _confirm(mock, issued, code=None):
    """Décision du propriétaire du 2026-10-10 (B1a) : le code COMPLET est retapé une fois."""
    return mock.call(
        "POST",
        f"{V1}/recovery-code/confirm",
        {"enrolmentId": issued["enrolmentId"], "recoveryCode": code or issued["recoveryCode"]},
    )


# ---- flux A : premier enrôlement ----------------------------------------------------------------
def test_flow_a_first_enrolment(mock):
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": NEW_MSISDN, "pin": PIN})
    assert r.status == 200 and r.body["next"] == "OTP"
    assert "demoOtp" not in r.body  # l'OTP n'est lisible qu'en mode --demo
    eid = r.body["enrolmentId"]
    r = mock.call("POST", f"{V1}/enrolment/otp", {"enrolmentId": eid, "otp": FIX["demoOtp"]})
    assert r.status == 200 and r.body["next"] == "BIND"  # liaison APRÈS validation (ES-25)
    issued = _complete(mock, eid)
    assert len(issued["recoveryCode"]) == PARAMS.code_length
    assert "confirmPositions" not in issued  # B1a : le code complet est retapé, pas des positions
    r = _confirm(mock, issued)
    assert r.status == 200
    acc = mock.call("GET", f"{V1}/account", token=r.body["sessionToken"]).body
    assert acc["msisdn"] == NEW_MSISDN and acc["state"] == "TRUSTED"
    # la clé de récupération n'est conservée que hachée et salée
    stored = mock.app.state.accounts[NEW_MSISDN]["recovery"]
    assert issued["recoveryCode"].encode() not in stored["hash"] + stored["salt"]


def test_flow_a_binding_is_active_only_after_the_code_is_confirmed(mock):
    """D4 : la confirmation du code (E-09) ne peut jamais être sautée : avant elle, le nouvel
    appareil n'est pas lié (pas de session), l'ancien reste lié, le nouveau code n'est pas actif."""
    old_token = mock.session()
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    assert _recover(mock, eid, RECOVERY).status == 200
    issued = _complete(mock, eid)
    device = issued["deviceId"]
    ch = mock.call("GET", f"{V1}/session/challenge?deviceId={device}").body["challenge"]
    body = {
        "msisdn": BOUND_MSISDN,
        "pin": PIN,
        "deviceId": device,
        "challenge": ch,
        "signature": SIG,
    }
    r = mock.call("POST", f"{V1}/session", body)
    assert (r.status, r.body["reason"]) == (401, "CREDENTIALS_INVALID")  # pas lié avant E-09
    assert mock.call("GET", f"{V1}/account", token=old_token).status == 200  # l'ancien reste lié
    assert _confirm(mock, issued, code="A" * PARAMS.code_length).status == 401
    assert _confirm(mock, issued).status == 200
    assert mock.call("GET", f"{V1}/account", token=old_token).status == 401  # révoqué à E-09
    assert mock.session(device=device)


def test_flow_a_demo_mode_exposes_otp(demo_mock):
    r = demo_mock.call("POST", f"{V1}/enrolment/start", {"msisdn": NEW_MSISDN, "pin": PIN})
    assert r.body["demoOtp"] == FIX["demoOtp"]


def test_flow_a_wrong_otp_then_new_otp_required(mock):
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": NEW_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    wrong = "".join("2" if c == "1" else "1" for c in FIX["demoOtp"])
    for _ in range(PARAMS.otp_attempts):
        r = mock.call("POST", f"{V1}/enrolment/otp", {"enrolmentId": eid, "otp": wrong})
        assert (r.status, r.body["reason"]) == (401, "OTP_INVALID")
    r = mock.call("POST", f"{V1}/enrolment/otp", {"enrolmentId": eid, "otp": FIX["demoOtp"]})
    assert (r.status, r.body["reason"]) == (410, "OTP_EXPIRED")


# ---- aiguillage (SPEC §4) -----------------------------------------------------------------------
def test_routing_bound_with_signature_gives_session(mock):
    ch = mock.call("GET", f"{V1}/session/challenge?deviceId={BOUND_DEVICE}").body["challenge"]
    r = mock.call(
        "POST",
        f"{V1}/enrolment/start",
        {
            "msisdn": BOUND_MSISDN,
            "pin": PIN,
            "deviceId": BOUND_DEVICE,
            "challenge": ch,
            "signature": SIG,
        },
    )
    assert r.status == 200 and r.body["next"] == "SESSION" and r.body["sessionToken"]


def test_routing_bound_without_signature_gives_recovery(mock):
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN})
    assert r.status == 200 and r.body["next"] == "RECOVERY"  # jamais d'OTP au ré-enrôlement


# ---- ES-22 : réponse uniforme ------------------------------------------------------------------
def test_es22_unknown_account_and_wrong_pin_are_identical(mock):
    unknown = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": "677000999", "pin": PIN})
    wrong = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": "9999"})
    assert unknown.status == wrong.status == 401
    assert unknown.body == wrong.body == {"reason": "CREDENTIALS_INVALID"}


# ---- flux B : quotidien -------------------------------------------------------------------------
def test_flow_b_daily_session(mock):
    token = mock.session()
    acc = mock.call("GET", f"{V1}/account", token=token).body
    assert acc["balanceXaf"] == AMINA["balanceXaf"] and acc["holderName"] == AMINA["holderName"]
    assert mock.call("GET", f"{V1}/transactions", token=token).status == 200
    assert mock.call("GET", f"{V1}/alerts", token=token).status == 200


def test_flow_b_no_token_is_credentials_invalid(mock):
    r = mock.call("GET", f"{V1}/account")
    assert (r.status, r.body["reason"]) == (401, "CREDENTIALS_INVALID")


def test_flow_b_session_expires(mock):
    token = mock.session()
    mock.clock.advance(PARAMS.session_ttl)
    assert mock.call("GET", f"{V1}/account", token=token).status == 401


# ---- flux C : les quatre décisions -------------------------------------------------------------
def _holder(mock, token, msisdn):
    return mock.call("GET", f"{V1}/beneficiaries/{msisdn}/holder", token=token).body


def _send(mock, token, msisdn, amount, signed=True, nonce=None):
    body = {
        "type": "P2P",
        "amountXaf": amount,
        "beneficiaryId": _holder(mock, token, msisdn)["beneficiaryId"],
        "nonce": nonce or mock.nonce(token),
        "pin": PIN,
    }
    if signed:
        body["signature"] = SIG
    return mock.call("POST", f"{V1}/transfers", body, token=token)


def test_flow_c_allow(mock):
    token = mock.session()
    known = AMINA["knownBeneficiaries"][0]
    assert _holder(mock, token, known)["newBeneficiary"] is False
    small = PARAMS.cap_prob_daily // 5
    r = _send(mock, token, known, small)
    assert (r.body["decision"], r.body["status"]) == ("ALLOW", "EXECUTED")
    assert r.body["balanceXaf"] == AMINA["balanceXaf"] - small


def test_flow_c_step_up_deferred_then_executed(mock):
    token = mock.session()
    r = _send(mock, token, AMINA["knownBeneficiaries"][0], PARAMS.cap_prob_daily)
    assert (r.body["decision"], r.body["reason"]) == ("STEP_UP", "DECISION_DEFERRED")
    acc = mock.call("GET", f"{V1}/account", token=token).body
    assert acc["balanceXaf"] == AMINA["balanceXaf"] and len(acc["deferredOperations"]) == 1
    mock.clock.advance(PARAMS.defer)
    token = mock.session()  # la session d'avant a expiré
    acc = mock.call("GET", f"{V1}/account", token=token).body
    assert acc["deferredOperations"] == []
    assert acc["balanceXaf"] == AMINA["balanceXaf"] - PARAMS.cap_prob_daily


def test_flow_c_step_up_new_beneficiary_and_cancel(mock):
    token = mock.session()
    assert _holder(mock, token, NEW_MSISDN)["newBeneficiary"] is True
    r = _send(mock, token, NEW_MSISDN, PARAMS.cap_prob_daily // 5)
    assert r.body["decision"] == "STEP_UP"
    c = mock.call(
        "POST", f"{V1}/transfers/{r.body['transferId']}/cancel", mock.signed(token), token=token
    )
    assert c.body["status"] == "CANCELLED"


def test_flow_c_freeze(mock):
    token = mock.session()
    r = _send(mock, token, FIX["freezeBeneficiaryMsisdn"], PARAMS.cap_prob_daily // 5)
    assert (r.body["decision"], r.body["status"]) == ("FREEZE", "FROZEN")


def test_flow_c_rejected_without_signature_and_on_nonce_reuse(mock):
    token = mock.session()
    known = AMINA["knownBeneficiaries"][0]
    assert _send(mock, token, known, 100, signed=False).body["decision"] == "REJECTED"
    nonce = mock.nonce(token)
    assert _send(mock, token, known, 100, nonce=nonce).body["decision"] == "ALLOW"
    again = _send(mock, token, known, 100, nonce=nonce)
    assert (again.body["decision"], again.body["reason"]) == ("REJECTED", "DECISION_REJECTED")


def test_flow_c_insufficient_funds(mock):
    token = mock.session()
    r = _send(mock, token, AMINA["knownBeneficiaries"][0], AMINA["balanceXaf"] + 1)
    assert (r.status, r.body["reason"]) == (409, "INSUFFICIENT_FUNDS")


# ---- flux D : ré-enrôlement ---------------------------------------------------------------------
def _recover(mock, eid, code):
    return mock.call("POST", f"{V1}/enrolment/recover", {"enrolmentId": eid, "recoveryCode": code})


def test_flow_d_two_failures_retry_after_then_success(mock):
    old_token = mock.session()
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    bad = "A" * PARAMS.code_length
    for _ in range(PARAMS.reenrol_attempts):
        r = _recover(mock, eid, bad)
        assert (r.status, r.body["reason"]) == (401, "RECOVERY_INVALID")
    r = _recover(mock, eid, RECOVERY)  # même avec la bonne clé : l'origine attend
    assert (r.status, r.body["reason"]) == (429, "THROTTLED")
    assert int(r.headers["Retry-After"]) == int(PARAMS.lock_base.total_seconds())
    mock.clock.advance(PARAMS.lock_base)
    r = _recover(mock, eid, RECOVERY)
    assert r.status == 200 and r.body["next"] == "BIND"
    issued = _complete(mock, eid)
    assert issued["deviceId"] != BOUND_DEVICE
    assert _confirm(mock, issued).status == 200
    # ancien téléphone : jeton invalide, puis KEY_REVOKED ; notification informative (EF-47)
    assert mock.call("GET", f"{V1}/account", token=old_token).status == 401
    ch = mock.call("GET", f"{V1}/session/challenge?deviceId={BOUND_DEVICE}").body["challenge"]
    body = {
        "msisdn": BOUND_MSISDN,
        "pin": PIN,
        "deviceId": BOUND_DEVICE,
        "challenge": ch,
        "signature": SIG,
    }
    r = mock.call("POST", f"{V1}/session", body)
    assert (r.status, r.body["reason"]) == (403, "KEY_REVOKED")
    new_token = mock.session(device=issued["deviceId"])
    alerts = mock.call("GET", f"{V1}/alerts", token=new_token).body["items"]
    rebound = [a for a in alerts if a["type"] == "DEVICE_REBOUND"]
    assert rebound and rebound[0]["actions"] == [] and rebound[0]["status"] == "INFO"
    # pas de quarantaine après ré-enrôlement (ES-03c)
    assert mock.call("GET", f"{V1}/account", token=new_token).body["state"] == "TRUSTED"


def test_flow_d_retry_after_doubles_and_account_never_blocked(mock):
    eid = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN}).body[
        "enrolmentId"
    ]
    bad = "A" * PARAMS.code_length
    for _ in range(PARAMS.reenrol_attempts):
        _recover(mock, eid, bad)
    mock.clock.advance(PARAMS.lock_base)
    assert _recover(mock, eid, bad).status == 401
    r = _recover(mock, eid, bad)
    assert int(r.headers["Retry-After"]) == int(2 * PARAMS.lock_base.total_seconds())
    # le titulaire, sur son téléphone lié, n'est jamais bloqué (ES-21)
    assert mock.session()


# ---- flux F : alertes --------------------------------------------------------------------------
def test_flow_f_sim_changed_not_me_freezes(mock):
    token = mock.session()
    mock.app.state.raise_sim_change(BOUND_MSISDN)
    alert = mock.call("GET", f"{V1}/alerts", token=token).body["items"][0]
    assert alert["type"] == "SIM_CHANGED" and alert["actions"] == ["ACK", "NOT_ME"]
    r = mock.call("POST", f"{V1}/alerts/{alert['alertId']}/not-me", mock.signed(token), token=token)
    assert (r.status, r.body["status"], r.body["accountState"]) == (200, "NOT_ME", "FROZEN")
    assert mock.call("GET", f"{V1}/account", token=token).body["state"] == "FROZEN"
    r = _send(mock, token, AMINA["knownBeneficiaries"][0], 100)
    assert (r.status, r.body["reason"]) == (403, "ACCOUNT_FROZEN")
    # ré-enrôlements gelés pendant REENROL_FREEZE
    r = mock.call("POST", f"{V1}/enrolment/start", {"msisdn": BOUND_MSISDN, "pin": PIN})
    assert (r.status, r.body["reason"]) == (403, "ACCOUNT_FROZEN")


def test_flow_f_ack_keeps_account_trusted(mock):
    token = mock.session()
    alert = mock.app.state.raise_sim_change(BOUND_MSISDN)
    r = mock.call("POST", f"{V1}/alerts/{alert['alertId']}/ack", mock.signed(token), token=token)
    assert (r.body["status"], r.body["accountState"]) == ("ACKNOWLEDGED", "TRUSTED")


def test_flow_f_signed_action_with_reused_nonce_is_rejected(mock):
    token = mock.session()
    alert = mock.app.state.raise_sim_change(BOUND_MSISDN)
    signed = mock.signed(token)
    mock.call("POST", f"{V1}/alerts/{alert['alertId']}/ack", signed, token=token)
    other = mock.app.state.raise_sim_change(BOUND_MSISDN)
    r = mock.call("POST", f"{V1}/alerts/{other['alertId']}/ack", signed, token=token)
    assert (r.status, r.body["reason"]) == (403, "DECISION_REJECTED")
