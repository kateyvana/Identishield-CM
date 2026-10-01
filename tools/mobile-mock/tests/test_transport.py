"""ADR-038 : le faux serveur parle HTTPS, obligatoirement, avec une CA de DÉVELOPPEMENT distincte.

La CA et le certificat sont générés pour le test par gen-dev-tls.sh (openssl), dans un dossier
temporaire : rien n'est écrit dans le dépôt. openssl et bash sont requis (fail-closed).
"""

import http.client
import json
import os
import shutil
import ssl
import subprocess
import threading
import urllib.error
import urllib.request

import pytest
from conftest import CONTRACT, FIXTURES, PIN, RULESET, Clock

from isc_mobile_mock import server

SCRIPT = FIXTURES.parent / "gen-dev-tls.sh"


@pytest.fixture(scope="module")
def dev_tls(tmp_path_factory):
    assert shutil.which("openssl"), "openssl requis pour générer la CA de développement (ADR-038)"
    # shutil.which respecte le PATH ; subprocess seul prendrait le bash de WSL (System32) sous Windows.
    bash = shutil.which("bash")
    assert bash, "bash requis pour gen-dev-tls.sh (ADR-038)"
    out = tmp_path_factory.mktemp("tls")
    subprocess.run(
        [bash, SCRIPT.as_posix()],
        check=True,
        capture_output=True,
        env={**os.environ, "ISC_DEV_TLS_DIR": out.as_posix()},
    )
    return out


@pytest.fixture
def https_mock(dev_tls):
    app = server.build(RULESET, CONTRACT, FIXTURES, Clock())
    httpd = server.serve(app, "127.0.0.1", 0, dev_tls / "mock.crt", dev_tls / "mock.key")
    httpd.RequestHandlerClass.log_message = lambda *a, **k: None
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield httpd.server_port
    httpd.shutdown()
    httpd.server_close()


def _start(port, context):
    req = urllib.request.Request(
        f"https://127.0.0.1:{port}/mobile/v1/enrolment/start",
        data=json.dumps({"msisdn": "677000200", "pin": PIN}).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, context=context, timeout=10) as resp:
        return resp.status, json.loads(resp.read())


def test_dev_ca_carries_the_release_refusal_marker(dev_tls):
    subject = subprocess.run(
        ["openssl", "x509", "-in", (dev_tls / "dev-ca.crt").as_posix(), "-noout", "-subject"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert "ISC-DEV-MOBILE-MOCK" in subject


def test_https_end_to_end_with_the_dev_ca_only(https_mock, dev_tls):
    trusting = ssl.create_default_context(cafile=str(dev_tls / "dev-ca.crt"))
    status, body = _start(https_mock, trusting)
    assert status == 200 and body["next"] == "OTP"


def test_client_that_does_not_trust_the_dev_ca_is_refused(https_mock):
    no_trust = ssl.create_default_context()
    no_trust.load_default_certs()
    with pytest.raises(urllib.error.URLError) as excinfo:
        _start(https_mock, no_trust)
    assert isinstance(excinfo.value.reason, ssl.SSLCertVerificationError)


def test_no_cleartext_fallback(https_mock):
    conn = http.client.HTTPConnection("127.0.0.1", https_mock, timeout=10)
    with pytest.raises((http.client.HTTPException, ConnectionError, OSError)):
        conn.request("POST", "/mobile/v1/enrolment/start", body=b"{}")
        conn.getresponse()
    conn.close()


def test_cli_refuses_to_start_without_certificate():
    with pytest.raises(SystemExit):
        server.main(
            [
                "--port",
                "0",
                "--ruleset",
                str(RULESET),
                "--contract",
                str(CONTRACT),
                "--fixtures",
                str(FIXTURES),
            ]
        )


def test_silent_client_does_not_block_other_clients(https_mock, dev_tls):
    """Un client qui se connecte sans parler ne bloque que son propre fil (poignée par connexion)."""
    import socket

    silent = socket.create_connection(("127.0.0.1", https_mock))
    try:
        trusting = ssl.create_default_context(cafile=str(dev_tls / "dev-ca.crt"))
        status, _ = _start(https_mock, trusting)
        assert status == 200
    finally:
        silent.close()
