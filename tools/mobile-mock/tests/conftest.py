"""Faux serveur lancé sur un port éphémère, horloge contrôlée, client qui valide TOUTE réponse
contre contracts/mobile-api.yaml (code HTTP déclaré + corps conforme au schéma)."""

import base64
import json
import threading
import urllib.error
import urllib.request
from datetime import UTC, datetime, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

from isc_mobile_mock import miniyaml, schema, server

ROOT = Path(__file__).resolve().parents[3]
RULESET = ROOT / "config" / "ruleset.yaml"
CONTRACT = ROOT / "contracts" / "mobile-api.yaml"
FIXTURES = ROOT / "tools" / "mobile-mock" / "fixtures.json"
FIX = json.loads(FIXTURES.read_text(encoding="utf-8"))
PARAMS = server.Params(RULESET)

NEW_MSISDN, BOUND_MSISDN = "677000200", "677000123"
AMINA = next(a for a in FIX["accounts"] if a["msisdn"] == BOUND_MSISDN)
PIN = AMINA["pin"]
BOUND_DEVICE = AMINA["boundDeviceId"]
RECOVERY = AMINA["recoveryCode"]
SIG = base64.b64encode(b"signature-de-test").decode()  # forme seulement : le mock ne vérifie rien


def b64(text: str) -> str:
    return base64.b64encode(text.encode()).decode()


class Clock:
    def __init__(self):
        self.now = datetime(2026, 9, 26, 14, 2, tzinfo=UTC)

    def __call__(self):
        return self.now

    def advance(self, delta: timedelta):
        self.now += delta


class Response:
    def __init__(self, status, body, headers):
        self.status, self.body, self.headers = status, body, headers


class Client:
    def __init__(self, base, contract, app, clock):
        self.base, self.contract, self.app, self.clock = base, contract, app, clock
        self.covered = set()

    def call(self, method, path, body=None, token=None, raw=None):
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req) as resp:
                status, payload, headers = resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as e:
            status, payload, headers = e.code, e.read(), dict(e.headers)
        out = json.loads(payload)
        template = self._template(method, path)
        if template is not None:
            # la réponse DOIT être déclarée par le contrat et conforme à son schéma
            sch = schema.response_schema(self.contract, method, template, status)
            schema.validate(self.contract, sch, out)
            self.covered.add((method, template))
        return Response(status, out, headers)

    @staticmethod
    def _template(method, path):
        import re

        path = path.split("?")[0][len(server.PREFIX) :]
        for verb, pattern, template, _ in server.ROUTES:
            if verb == method and re.fullmatch(pattern, path):
                return template
        return None

    # --- raccourcis de parcours ---------------------------------------------------------------
    def session(self, msisdn=BOUND_MSISDN, device=BOUND_DEVICE):
        ch = self.call("GET", f"/mobile/v1/session/challenge?deviceId={device}").body["challenge"]
        r = self.call(
            "POST",
            "/mobile/v1/session",
            {"msisdn": msisdn, "pin": PIN, "deviceId": device, "challenge": ch, "signature": SIG},
        )
        assert r.status == 200, r.body
        return r.body["sessionToken"]

    def nonce(self, token):
        return self.call("POST", "/mobile/v1/nonce", token=token).body["nonce"]

    def signed(self, token):
        return {"nonce": self.nonce(token), "signature": SIG}


def _serve(demo=False):
    clock = Clock()
    app = server.build(RULESET, CONTRACT, FIXTURES, clock, demo)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(app))
    httpd.RequestHandlerClass.log_message = lambda *a, **k: None
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    client = Client(f"http://127.0.0.1:{httpd.server_port}", miniyaml.load(CONTRACT), app, clock)
    return httpd, client


@pytest.fixture
def mock():
    httpd, client = _serve()
    yield client
    httpd.shutdown()
    httpd.server_close()


@pytest.fixture
def demo_mock():
    httpd, client = _serve(demo=True)
    yield client
    httpd.shutdown()
    httpd.server_close()
