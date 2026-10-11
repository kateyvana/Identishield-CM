"""Faux serveur de l'API mobile d'IdentiShield-CM (M-03) — Python, bibliothèque standard (ADR-033).

LIMITES — À LIRE AVANT TOUT USAGE
- Ce n'est PAS le cœur. C'est un faux serveur de DÉVELOPPEMENT de l'application mobile.
- Il NE VÉRIFIE NI la signature cryptographique NI l'attestation : il ne contrôle que leur FORME
  (base64 non vide, défi et nonce émis par lui, appareil attendu). Une signature « valide » pour
  le mock ne prouve rien.
- Ses règles de décision (ALLOW / STEP_UP / FREEZE / REJECTED) sont des règles de MOCK, pas celles
  du PDP : STEP_UP si montant >= CAP_PROB.dailyXaf ou nouveau bénéficiaire ; FREEZE vers le numéro
  de test des fixtures ; REJECTED si signature absente ou nonce inconnu / déjà utilisé.
- État en mémoire, volatile, rechargé depuis les fixtures à chaque démarrage. Données fictives.
- HTTPS OBLIGATOIRE, sans repli en clair (ADR-038) : certificat émis par une CA de DÉVELOPPEMENT
  distincte (gen-dev-tls.sh), jamais la CA du bac à sable. Écoute sur 127.0.0.1 par défaut.
- Les réponses ne sont pas uniformes EN TEMPS (ES-22 exige contenu, code ET temps) ; seuls le
  contenu et le code le sont.

Les paramètres nommés viennent de config/ruleset.yaml (jamais en littéral) ; les requêtes sont
validées par contracts/mobile-api.yaml lui-même ; l'horloge est injectée.
"""

import argparse
import base64
import hashlib
import hmac
import json
import re
import secrets
import ssl
import threading
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from isc_mobile_mock import miniyaml, schema

PREFIX = "/mobile/v1"
# Alphabet de la clé de récupération : sans I, L, O, 0, 1 (confusion à la recopie sur papier).
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
_DURATION = re.compile(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?")


def duration(value: str) -> timedelta:
    """Durée ISO-8601 du jeu de règles (PT30S, PT10M, PT24H...)."""
    m = _DURATION.fullmatch(value)
    if not m or not any(m.groups()):
        raise ValueError(f"durée ISO-8601 non prise en charge : {value!r}")
    h, mi, s = (int(g) if g else 0 for g in m.groups())
    return timedelta(hours=h, minutes=mi, seconds=s)


class Params:
    """Paramètres nommés lus dans config/ruleset.yaml (source unique, ES-15)."""

    def __init__(self, ruleset_path: Path):
        p = miniyaml.load(ruleset_path)["ruleset"]["parameters"]
        self.reenrol_attempts = int(p["REENROL_ATTEMPTS"])
        self.lock_base = duration(p["LOCK_BASE"])
        self.lock_max = duration(p["LOCK_MAX"])
        self.alert_reenrol_count = int(p["ALERT_REENROL"]["count"])
        self.alert_reenrol_window = duration(p["ALERT_REENROL"]["window"])
        self.cap_prob_daily = int(p["CAP_PROB"]["dailyXaf"])
        self.otp_length = int(p["OTP"]["length"])
        self.otp_ttl = duration(p["OTP"]["ttl"])
        self.otp_attempts = int(p["OTP_ATTEMPTS"])
        self.code_length = int(p["CODE_LENGTH"])
        self.enrol_ttl = duration(p["ENROL_TTL"])
        self.session_ttl = duration(p["APP_SESSION_TTL"])
        self.defer = duration(p["DEFER"])
        self.reenrol_freeze = duration(p["REENROL_FREEZE"])


def _iso(t: datetime) -> str:
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _hash(salt: bytes, code: str) -> bytes:
    return hashlib.sha256(salt + code.encode("utf-8")).digest()


def _normalise_code(code: str) -> str:
    return re.sub(r"[\s-]", "", code).upper()


class ApiError(Exception):
    def __init__(self, status: int, reason: str, retry_after: int | None = None):
        super().__init__(reason)
        self.status, self.reason, self.retry_after = status, reason, retry_after


def _err(status, reason, retry_after=None):
    raise ApiError(status, reason, retry_after)


CRED = (401, "CREDENTIALS_INVALID")  # réponse UNIQUE : compte inexistant, PIN faux, jeton (ES-22)


class MockState:
    """État volatile du faux serveur. `clock` est injectée : aucune lecture directe de l'heure."""

    def __init__(self, fixtures: dict, params: Params, clock, demo: bool = False):
        self.p, self.clock, self.demo = params, clock, demo
        self.demo_otp = fixtures["demoOtp"]
        if len(self.demo_otp) != params.otp_length:
            raise ValueError("demoOtp des fixtures : longueur différente de OTP.length")
        self.freeze_msisdn = fixtures["freezeBeneficiaryMsisdn"]
        self.accounts = {}
        for a in fixtures["accounts"]:
            acc = {
                "accountId": a["accountId"],
                "msisdn": a["msisdn"],
                "holderName": a["holderName"],
                "pin": a.get("pin"),
                "balanceXaf": a["balanceXaf"],
                "device": a.get("boundDeviceId"),
                "revoked": set(),
                "known": set(a.get("knownBeneficiaries", [])),
                "state": "TRUSTED",
                "reenrolFrozenUntil": None,
                "recovery": None,
                "failures": [],
                "alerted": False,
            }
            if a.get("recoveryCode"):
                if len(a["recoveryCode"]) != params.code_length:
                    raise ValueError(
                        f"clé de récupération de test ({a['msisdn']}) : longueur != CODE_LENGTH"
                    )
                acc["recovery"] = self._protect(a["recoveryCode"])
            self.accounts[a["msisdn"]] = acc
        self.by_id = {a["accountId"]: a for a in self.accounts.values()}
        self.enrolments, self.sessions, self.challenges, self.nonces = {}, {}, {}, {}
        self.origins, self.transactions, self.alerts = {}, [], []
        self._seq = 0

    # ---- utilitaires ------------------------------------------------------------------------
    def _id(self, prefix):
        self._seq += 1
        return f"{prefix}-{self._seq:06d}"

    def _protect(self, code):
        salt = secrets.token_bytes(16)
        return {"salt": salt, "hash": _hash(salt, _normalise_code(code))}

    def _code_matches(self, acc, code):
        rec = acc["recovery"]
        return rec is not None and hmac.compare_digest(
            rec["hash"], _hash(rec["salt"], _normalise_code(code))
        )

    def _check_pin(self, msisdn, pin):
        acc = self.accounts.get(msisdn)
        if acc is None or acc["pin"] is None or not hmac.compare_digest(acc["pin"], pin):
            _err(*CRED)
        return acc

    def _session(self, token):
        now = self.clock()
        s = self.sessions.get(token or "")
        if s is None or s["expires"] <= now:
            _err(*CRED)
        acc = self.by_id[s["accountId"]]
        if acc["device"] != s["deviceId"]:
            _err(*CRED)  # appareil révoqué ou délié : le jeton ne vaut plus rien
        self._settle(acc)
        return acc, s

    def _new_session(self, acc, device_id):
        token = secrets.token_urlsafe(24)
        self.sessions[token] = {
            "accountId": acc["accountId"],
            "deviceId": device_id,
            "expires": self.clock() + self.p.session_ttl,
        }
        return {"sessionToken": token, "expiresInSeconds": int(self.p.session_ttl.total_seconds())}

    def _consume_nonce(self, token, nonce):
        """Nonce émis pour CETTE session et jamais utilisé ; sinon None."""
        entry = self.nonces.get(nonce)
        if entry is None or entry["token"] != token or entry["used"]:
            return False
        entry["used"] = True
        return True

    def _signed(self, token, body):
        acc, s = self._session(token)
        if not body.get("signature") or not self._consume_nonce(token, body["nonce"]):
            _err(403, "DECISION_REJECTED")
        return acc, s

    def _alert(self, acc, kind, summary, actions):
        alert = {
            "alertId": self._id("al"),
            "accountId": acc["accountId"],
            "type": kind,
            "at": _iso(self.clock()),
            "summary": summary,
            "actions": actions,
            "status": "OPEN" if actions else "INFO",
        }
        self.alerts.append(alert)
        return alert

    def raise_sim_change(self, msisdn):
        """Événement HLR simulé (flux F) : tests et démonstration, hors contrat HTTP."""
        acc = self.accounts[msisdn]
        return self._alert(
            acc,
            "SIM_CHANGED",
            f"La carte SIM de votre numéro a été changée à {self.clock():%H h %M}. C'est vous ?",
            ["ACK", "NOT_ME"],
        )

    def _enrolment(self, enrolment_id, stage):
        e = self.enrolments.get(enrolment_id)
        if e is None:
            _err(400, "REQUEST_INVALID")
        if e["expires"] <= self.clock():
            _err(410, "ENROLMENT_EXPIRED")
        if e["stage"] != stage:
            _err(400, "REQUEST_INVALID")
        return e

    def _issue_code(self, acc, kind, device_id):
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(self.p.code_length))
        acc["recovery"] = self._protect(code)  # seul le haché salé est conservé, dès l'émission
        eid = secrets.token_hex(8)
        self.enrolments[eid] = {
            "accountId": acc["accountId"],
            "kind": kind,
            "stage": "CONFIRM",
            "expires": self.clock() + self.p.enrol_ttl,
            "deviceId": device_id,
        }
        return {"enrolmentId": eid, "recoveryCode": code}

    def _settle(self, acc):
        """Exécute les opérations différées arrivées à échéance (DEFER)."""
        now = self.clock()
        for t in self.transactions:
            if (
                t["from"] == acc["accountId"]
                and t["status"] == "DEFERRED"
                and t["executeAt"] <= now
            ):
                self._execute(t)

    def _execute(self, t):
        payer, payee = self.by_id[t["from"]], self.by_id[t["to"]]
        if payer["balanceXaf"] < t["amountXaf"]:
            t["status"] = "CANCELLED"
            return
        payer["balanceXaf"] -= t["amountXaf"]
        payee["balanceXaf"] += t["amountXaf"]
        payer["known"].add(payee["msisdn"])
        t["status"] = "EXECUTED"

    # ---- routes -----------------------------------------------------------------------------
    def enrolment_start(self, body, token, origin):
        acc = self._check_pin(body["msisdn"], body["pin"])
        if acc["device"] is not None:
            ch = self.challenges.get(body.get("challenge", ""))
            if (
                body.get("signature")
                and body.get("deviceId") == acc["device"]
                and ch is not None
                and ch["deviceId"] == acc["device"]
                and not ch["used"]
            ):
                ch["used"] = True
                return 200, {"next": "SESSION", **self._new_session(acc, acc["device"])}
            frozen = acc["reenrolFrozenUntil"]
            if frozen is not None and frozen > self.clock():
                _err(403, "ACCOUNT_FROZEN")
            kind, stage = "REENROL", "RECOVERY"
        else:
            kind, stage = "FIRST", "OTP"
        eid = secrets.token_hex(8)
        self.enrolments[eid] = {
            "accountId": acc["accountId"],
            "kind": kind,
            "stage": stage,
            "expires": self.clock() + self.p.enrol_ttl,
            "otpExpires": self.clock() + self.p.otp_ttl,
            "otpFailures": 0,
        }
        out = {"next": "OTP" if kind == "FIRST" else "RECOVERY", "enrolmentId": eid}
        if kind == "FIRST" and self.demo:
            out["demoOtp"] = self.demo_otp
        return 200, out

    def _bind(self, e):
        e["stage"] = "BIND"
        e["attestationChallenge"] = base64.b64encode(secrets.token_bytes(16)).decode()
        return 200, {"next": "BIND", "attestationChallenge": e["attestationChallenge"]}

    def enrolment_otp(self, body, token, origin):
        e = self._enrolment(body["enrolmentId"], "OTP")
        if e["otpExpires"] <= self.clock() or e["otpFailures"] >= self.p.otp_attempts:
            _err(410, "OTP_EXPIRED")
        if not hmac.compare_digest(body["otp"], self.demo_otp):
            e["otpFailures"] += 1
            _err(401, "OTP_INVALID")
        return self._bind(e)

    def enrolment_recover(self, body, token, origin):
        e = self._enrolment(body["enrolmentId"], "RECOVERY")
        acc = self.by_id[e["accountId"]]
        frozen = acc["reenrolFrozenUntil"]
        if frozen is not None and frozen > self.clock():
            _err(403, "ACCOUNT_FROZEN")
        now = self.clock()
        o = self.origins.setdefault(origin, {"failures": 0, "lockedUntil": None})
        if o["lockedUntil"] is not None and o["lockedUntil"] > now:
            wait = int((o["lockedUntil"] - now).total_seconds() + 0.999)
            _err(429, "THROTTLED", max(wait, 1))
        if self._code_matches(acc, body["recoveryCode"]):
            o["failures"], o["lockedUntil"] = 0, None
            return self._bind(e)
        # échec : délai croissant sur l'ORIGINE, jamais sur le compte (ES-21)
        o["failures"] += 1
        if o["failures"] >= self.p.reenrol_attempts:
            extra = o["failures"] - self.p.reenrol_attempts
            delay = min(self.p.lock_base * (2**extra), self.p.lock_max)
            o["lockedUntil"] = now + delay
        # comptage par compte pour l'alerte au téléphone lié, sans restriction du compte (ES-24)
        acc["failures"] = [t for t in acc["failures"] if t > now - self.p.alert_reenrol_window]
        acc["failures"].append(now)
        if len(acc["failures"]) >= self.p.alert_reenrol_count and not acc["alerted"]:
            acc["alerted"] = True
            self._alert(
                acc,
                "REENROL_ATTEMPT",
                "Quelqu'un tente de lier un nouveau téléphone à votre compte.",
                ["ACK", "NOT_ME"],
            )
        _err(401, "RECOVERY_INVALID")

    def enrolment_complete(self, body, token, origin):
        e = self._enrolment(body["enrolmentId"], "BIND")
        for cert in body["attestationChain"]:
            raw = cert.rstrip("=")
            if base64.b64decode(raw + "=" * (-len(raw) % 4))[:8] == b"SOFTWARE":  # règle de MOCK
                _err(422, "DEVICE_UNSUPPORTED")
        acc = self.by_id[e["accountId"]]
        old = acc["device"]
        if old is not None:
            acc["revoked"].add(old)
        device_id = "-".join(secrets.token_hex(n) for n in (4, 2, 2, 2, 6))  # forme UUID, aléatoire
        acc["device"] = device_id
        acc["alerted"], acc["failures"] = False, []
        del self.enrolments[body["enrolmentId"]]
        if old is not None:  # ancien appareil informé, sans action (EF-47, ADR-010)
            self._alert(acc, "DEVICE_REBOUND", "Un nouveau téléphone a été lié à votre compte.", [])
        issued = self._issue_code(acc, e["kind"], device_id)
        return 200, {**issued, "deviceId": device_id}

    def recovery_confirm(self, body, token, origin):
        # B1a (2026-10-10) : le code COMPLET est retapé une fois et vérifié contre le haché salé.
        e = self._enrolment(body["enrolmentId"], "CONFIRM")
        acc = self.by_id[e["accountId"]]
        if not self._code_matches(acc, body["recoveryCode"]):
            _err(401, "RECOVERY_INVALID")
        del self.enrolments[body["enrolmentId"]]
        return 200, self._new_session(acc, e["deviceId"])

    def session_challenge(self, query, token, origin):
        device_id = query.get("deviceId", [""])[0]
        if not re.fullmatch(r"[0-9a-f-]{36}", device_id):
            _err(400, "REQUEST_INVALID")
        challenge = secrets.token_urlsafe(24)
        self.challenges[challenge] = {"deviceId": device_id, "used": False}
        return 200, {"challenge": challenge}

    def session_open(self, body, token, origin):
        acc = self._check_pin(body["msisdn"], body["pin"])
        if body["deviceId"] in acc["revoked"]:
            _err(403, "KEY_REVOKED")
        ch = self.challenges.get(body["challenge"])
        if (
            body["deviceId"] != acc["device"]
            or ch is None
            or ch["used"]
            or (ch["deviceId"] != body["deviceId"])
        ):
            _err(*CRED)
        ch["used"] = True
        return 200, self._new_session(acc, acc["device"])

    def nonce(self, body, token, origin):
        self._session(token)
        value = secrets.token_hex(16)
        self.nonces[value] = {"token": token, "used": False}
        return 200, {"nonce": value}

    def account(self, query, token, origin):
        acc, _ = self._session(token)
        deferred = [
            {
                "transferId": t["transferId"],
                "amountXaf": t["amountXaf"],
                "beneficiaryMsisdn": self.by_id[t["to"]]["msisdn"],
                "executeAt": _iso(t["executeAt"]),
            }
            for t in self.transactions
            if t["from"] == acc["accountId"] and t["status"] == "DEFERRED"
        ]
        return 200, {
            "accountId": acc["accountId"],
            "msisdn": acc["msisdn"],
            "holderName": acc["holderName"],
            "balanceXaf": acc["balanceXaf"],
            "state": acc["state"],
            "deferredOperations": deferred,
        }

    def transactions_list(self, query, token, origin):
        acc, _ = self._session(token)
        items = []
        for t in reversed(self.transactions):
            if acc["accountId"] not in (t["from"], t["to"]):
                continue
            outgoing = t["from"] == acc["accountId"]
            if not outgoing and t["status"] != "EXECUTED":
                continue
            other = self.by_id[t["to"] if outgoing else t["from"]]
            items.append(
                {
                    "transferId": t["transferId"],
                    "direction": "OUT" if outgoing else "IN",
                    "amountXaf": t["amountXaf"],
                    "counterpartyMsisdn": other["msisdn"],
                    "counterpartyName": other["holderName"],
                    "status": t["status"],
                    "at": _iso(t["at"]),
                }
            )
        return 200, {"items": items}

    def holder(self, msisdn, token):
        acc, _ = self._session(token)
        target = self.accounts.get(msisdn)
        if target is None:
            _err(404, "REQUEST_INVALID")
        return 200, {
            "msisdn": msisdn,
            "beneficiaryId": target["accountId"],
            "holderName": target["holderName"],
            "newBeneficiary": msisdn not in acc["known"],
        }

    def transfer(self, body, token, origin):
        acc, _ = self._session(token)
        if acc["state"] == "FROZEN":
            _err(403, "ACCOUNT_FROZEN")
        if acc["pin"] is None or not hmac.compare_digest(acc["pin"], body["pin"]):
            _err(*CRED)
        payee = self.by_id.get(body["beneficiaryId"])
        if payee is None:
            _err(404, "REQUEST_INVALID")
        amount = body["amountXaf"]
        if acc["balanceXaf"] < amount:
            _err(409, "INSUFFICIENT_FUNDS")  # le cœur refuse sans appeler le PDP
        now = self.clock()
        t = {
            "transferId": self._id("tr"),
            "from": acc["accountId"],
            "to": payee["accountId"],
            "amountXaf": amount,
            "at": now,
        }
        self.transactions.append(t)
        # règles de MOCK, pas celles du PDP
        if not body.get("signature") or not self._consume_nonce(token, body["nonce"]):
            t["status"] = "REJECTED"
            decision, reason = "REJECTED", "DECISION_REJECTED"
        elif payee["msisdn"] == self.freeze_msisdn:
            t["status"] = "FROZEN"
            decision, reason = "FREEZE", "DECISION_FROZEN"
            self._alert(
                acc, "TRANSFER_FROZEN", "Une opération a été bloquée pour votre sécurité.", []
            )
        elif amount >= self.p.cap_prob_daily or payee["msisdn"] not in acc["known"]:
            t["status"], t["executeAt"] = "DEFERRED", now + self.p.defer
            decision, reason = "STEP_UP", "DECISION_DEFERRED"
            self._alert(
                acc,
                "TRANSFER_DEFERRED",
                f"Votre envoi de {amount} FCFA sera exécuté plus tard.",
                [],
            )
        else:
            self._execute(t)
            decision, reason = "ALLOW", None
        out = {
            "transferId": t["transferId"],
            "decision": decision,
            "status": t["status"],
            "balanceXaf": acc["balanceXaf"],
        }
        if reason:
            out["reason"] = reason
        if decision == "STEP_UP":
            out["executeAt"] = _iso(t["executeAt"])
        return 200, out

    def transfer_cancel(self, transfer_id, body, token):
        acc, _ = self._signed(token, body)
        t = next((x for x in self.transactions if x["transferId"] == transfer_id), None)
        if t is None or t["from"] != acc["accountId"] or t["status"] != "DEFERRED":
            _err(404, "REQUEST_INVALID")
        t["status"] = "CANCELLED"
        return 200, {"transferId": transfer_id, "status": "CANCELLED"}

    def alerts_list(self, query, token, origin):
        acc, _ = self._session(token)
        items = [
            {k: a[k] for k in ("alertId", "type", "at", "summary", "actions", "status")}
            for a in reversed(self.alerts)
            if a["accountId"] == acc["accountId"]
        ]
        return 200, {"items": items}

    def alert_answer(self, alert_id, body, token, not_me):
        acc, _ = self._signed(token, body)
        a = next((x for x in self.alerts if x["alertId"] == alert_id), None)
        if a is None or a["accountId"] != acc["accountId"] or not a["actions"]:
            _err(404, "REQUEST_INVALID")
        if not_me:  # ES-27 : compte FROZEN et ré-enrôlements gelés pendant REENROL_FREEZE
            acc["state"] = "FROZEN"
            acc["reenrolFrozenUntil"] = self.clock() + self.p.reenrol_freeze
            a["status"] = "NOT_ME"
        else:
            a["status"] = "ACKNOWLEDGED"
        a["actions"] = []
        return 200, {"alertId": alert_id, "status": a["status"], "accountState": acc["state"]}

    def recovery_rotate(self, body, token, origin):
        acc, s = self._signed(token, body)
        issued = self._issue_code(acc, "ROTATE", s["deviceId"])
        return 200, issued

    def device_unbind(self, body, token, origin):
        acc, _ = self._signed(token, body)
        acc["revoked"].add(acc["device"])
        acc["device"], acc["recovery"] = None, None
        return 200, {"status": "UNBOUND"}


# (méthode, motif, gabarit du contrat, gestionnaire)
ROUTES = [
    ("POST", r"/enrolment/start", "/enrolment/start", "enrolment_start"),
    ("POST", r"/enrolment/otp", "/enrolment/otp", "enrolment_otp"),
    ("POST", r"/enrolment/recover", "/enrolment/recover", "enrolment_recover"),
    ("POST", r"/enrolment/complete", "/enrolment/complete", "enrolment_complete"),
    ("POST", r"/recovery-code/confirm", "/recovery-code/confirm", "recovery_confirm"),
    ("GET", r"/session/challenge", "/session/challenge", "session_challenge"),
    ("POST", r"/session", "/session", "session_open"),
    ("POST", r"/nonce", "/nonce", "nonce"),
    ("GET", r"/account", "/account", "account"),
    ("GET", r"/transactions", "/transactions", "transactions_list"),
    ("GET", r"/beneficiaries/([^/]+)/holder", "/beneficiaries/{msisdn}/holder", "holder"),
    ("POST", r"/transfers", "/transfers", "transfer"),
    ("POST", r"/transfers/([^/]+)/cancel", "/transfers/{transferId}/cancel", "transfer_cancel"),
    ("GET", r"/alerts", "/alerts", "alerts_list"),
    ("POST", r"/alerts/([^/]+)/ack", "/alerts/{alertId}/ack", "alert_ack"),
    ("POST", r"/alerts/([^/]+)/not-me", "/alerts/{alertId}/not-me", "alert_not_me"),
    ("POST", r"/recovery-code/rotate", "/recovery-code/rotate", "recovery_rotate"),
    ("DELETE", r"/device", "/device", "device_unbind"),
]


class MockApp:
    """Aiguillage HTTP -> état ; les requêtes sont validées par le contrat lui-même."""

    def __init__(self, state: MockState, contract: dict):
        self.state, self.contract = state, contract
        self._lock = threading.Lock()  # le serveur traite les requêtes en parallèle

    def dispatch(self, method, raw_path, headers, raw_body, origin):
        """-> (statut, corps dict, en-têtes supplémentaires, gabarit du contrat ou None)."""
        with self._lock:
            return self._dispatch(method, raw_path, headers, raw_body, origin)

    def _dispatch(self, method, raw_path, headers, raw_body, origin):
        url = urlsplit(raw_path)
        if not url.path.startswith(PREFIX):
            return 404, {"reason": "REQUEST_INVALID"}, {}, None
        path = url.path[len(PREFIX) :]
        for verb, pattern, template, name in ROUTES:
            m = re.fullmatch(pattern, path)
            if m and verb == method:
                break
        else:
            return 404, {"reason": "REQUEST_INVALID"}, {}, None
        auth = headers.get("Authorization", "")
        token = auth[len("Bearer ") :] if auth.startswith("Bearer ") else None
        try:
            body = None
            req_schema = schema.request_schema(self.contract, method, template)
            if req_schema is not None:
                try:
                    body = json.loads(raw_body or b"null")
                    schema.validate(self.contract, req_schema, body)
                except (ValueError, schema.ContractViolation):
                    _err(400, "REQUEST_INVALID")
            args = m.groups()
            if name == "holder":
                if not re.fullmatch(r"67700[0-9]{4}", args[0]):
                    _err(400, "REQUEST_INVALID")
                status, out = self.state.holder(args[0], token)
            elif name == "transfer_cancel":
                status, out = self.state.transfer_cancel(args[0], body, token)
            elif name in ("alert_ack", "alert_not_me"):
                status, out = self.state.alert_answer(args[0], body, token, name == "alert_not_me")
            elif verb == "GET":
                status, out = getattr(self.state, name)(parse_qs(url.query), token, origin)
            else:
                status, out = getattr(self.state, name)(body, token, origin)
            return status, out, {}, template
        except ApiError as e:
            extra = {"Retry-After": str(e.retry_after)} if e.retry_after else {}
            return e.status, {"reason": e.reason}, extra, template


def make_handler(app: MockApp):
    class Handler(BaseHTTPRequestHandler):
        server_version = "isc-mobile-mock"

        def _serve(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            status, body, extra, template = app.dispatch(
                self.command, self.path, self.headers, raw, self.client_address[0]
            )
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self._template = template
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            for k, v in extra.items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(payload)

        do_GET = do_POST = do_DELETE = _serve

        def log_message(self, fmt, *args):  # jamais de PIN, d'OTP, de clé ni de numéro (MOB-S-11)
            print(f"{self.command} {getattr(self, '_template', None) or '?'} -> {args[1]}")

    return Handler


def build(ruleset: Path, contract: Path, fixtures: Path, clock, demo=False) -> MockApp:
    state = MockState(
        json.loads(Path(fixtures).read_text(encoding="utf-8")), Params(ruleset), clock, demo
    )
    return MockApp(state, miniyaml.load(contract))


# Délai de la poignée de main TLS d'une connexion : un client muet libère son fil sans jamais
# bloquer les autres (transport de l'outil de développement, pas un paramètre du jeu de règles).
HANDSHAKE_TIMEOUT_SECONDS = 5


class TLSThreadingHTTPServer(ThreadingHTTPServer):
    """HTTPS uniquement (ADR-038). La poignée de main TLS se fait dans le fil de CHAQUE connexion,
    jamais dans la boucle d'acceptation : un client qui se connecte sans parler ne bloque que son
    propre fil. Un client en clair échoue à la poignée de main ; il n'existe aucun mode HTTP."""

    daemon_threads = True

    def __init__(self, address, handler, context: ssl.SSLContext):
        self.tls_context = context
        super().__init__(address, handler)

    def finish_request(self, request, client_address):
        request.settimeout(HANDSHAKE_TIMEOUT_SECONDS)
        try:
            tls = self.tls_context.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            return  # poignée de main refusée (clair, CA non reconnue, client muet) ; socket fermée
        tls.settimeout(None)
        self.RequestHandlerClass(tls, client_address, self)


def serve(app: MockApp, host: str, port: int, certfile: Path, keyfile: Path) -> ThreadingHTTPServer:
    """Serveur HTTPS uniquement (ADR-038), TLS 1.2 minimum."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=str(certfile), keyfile=str(keyfile))
    return TLSThreadingHTTPServer((host, port), make_handler(app), context)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="isc-mobile-mock",
        description="Faux serveur de l'API mobile (développement de l'app ; ne vérifie ni "
        "signature ni attestation).",
    )
    parser.add_argument("--port", type=int, required=True, help="port d'écoute (paramètre nommé)")
    parser.add_argument("--host", default="127.0.0.1", help="adresse d'écoute (défaut : locale)")
    parser.add_argument("--ruleset", type=Path, required=True, help="config/ruleset.yaml")
    parser.add_argument("--contract", type=Path, required=True, help="contracts/mobile-api.yaml")
    parser.add_argument("--fixtures", type=Path, required=True, help="fixtures fictives (JSON)")
    parser.add_argument("--demo", action="store_true", help="expose l'OTP de test (demoOtp)")
    parser.add_argument(
        "--cert", type=Path, required=True, help="certificat HTTPS (gen-dev-tls.sh)"
    )
    parser.add_argument("--key", type=Path, required=True, help="clé du certificat HTTPS")
    args = parser.parse_args(argv)
    app = build(args.ruleset, args.contract, args.fixtures, lambda: datetime.now(UTC), args.demo)
    server = serve(app, args.host, args.port, args.cert, args.key)
    print(f"isc-mobile-mock : https://{args.host}:{args.port}{PREFIX} (mode démo : {args.demo})")
    print("RAPPEL : faux serveur ; ni signature ni attestation ne sont vérifiées.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
