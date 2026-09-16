"""Tests de l'outil attaquant (EF-40) : l'orchestration d'AC-02 et l'ABSENCE de toute connaissance
du mode sans couche — sans cœur vivant (un faux cœur en mémoire)."""

from __future__ import annotations

import contextlib
import io
from urllib.parse import parse_qs, urlparse

from isc_attacker import cli

ATTACKER_IMSI = "624010000000666"
VICTIM_IMSI = "624010000000001"


class FakeCore:
    """Un cœur en mémoire : juste assez pour dérouler AC-02 et vérifier l'orchestration."""

    def __init__(self, balance: int = 500_000) -> None:
        self.balance = balance
        self.current_imsi = VICTIM_IMSI
        self.inbox: dict[str, str] = {}
        self.notifs: list[dict] = []

    def request(self, method: str, url: str, body: dict | None = None) -> tuple[int, dict]:
        path = urlparse(url).path
        query = parse_qs(urlparse(url).query)
        if method == "GET" and path == "/account":
            return 200, {"msisdn": query["msisdn"][0], "balanceXaf": self.balance}
        if method == "POST" and path == "/hlr/reassign":
            old = self.current_imsi
            self.current_imsi = body["newImsi"]
            return 200, {"oldImsi": old, "newImsi": self.current_imsi, "eventEmitted": True}
        if method == "POST" and path == "/otp/issue":
            self.inbox[self.current_imsi] = "483920"  # livraison vers l'IMSI courant
            return 200, {"targetImsi": self.current_imsi, "delivered": True}
        if method == "GET" and path == "/sim/inbox":
            imsi = query["imsi"][0]
            if imsi in self.inbox:
                return 200, {"imsi": imsi, "code": self.inbox[imsi]}
            return 404, {"error": "aucun message"}
        if method == "POST" and path == "/otp/verify":
            return 200, {"outcome": "ACCEPTED"}
        if method == "POST" and path == "/operation":
            self.balance -= body["amountXaf"]
            self.notifs.append({"recipientRef": self.current_imsi, "type": "P2P_EXECUTED"})
            return 200, {"state": "EXECUTED", "decisionId": "did-fake"}
        if method == "GET" and path == "/notifications":
            return 200, self.notifs
        raise AssertionError(f"appel inattendu : {method} {path}")


def _args():
    return cli._parser().parse_args(
        [
            "ac02",
            "--victim",
            "699000007",
            "--victim-imsi",
            VICTIM_IMSI,
            "--attacker-imsi",
            ATTACKER_IMSI,
            "--beneficiary",
            "699000008",
            "--agent-id",
            "agent-kyc-07",
            "--motif",
            "remplacement SIM",
        ]
    )


def test_ac02_empties_the_account_and_reports_facts(monkeypatch, capsys) -> None:
    fake = FakeCore()
    monkeypatch.setattr(cli, "_request", fake.request)

    code = cli.run_ac02("http://core:8080", _args())

    assert code == 0
    out = capsys.readouterr().out
    assert "ORACLE_BALANCE_INITIAL=500000" in out
    assert "ORACLE_BALANCE_FINAL=0" in out
    assert "ORACLE_OUTPUTS=1" in out
    assert f"ORACLE_OTP_TARGET_IMSI={ATTACKER_IMSI}" in out  # l'IMSI attaquant, pas la victime
    assert "ORACLE_OLD_DEVICE_NOTIFICATIONS=0" in out


def test_otp_targets_the_attacker_imsi_after_the_swap(monkeypatch) -> None:
    """L'OTP part vers l'IMSI COURANT, qui après le swap est celui de l'attaquant."""
    fake = FakeCore()
    monkeypatch.setattr(cli, "_request", fake.request)

    cli.run_ac02("http://core:8080", _args())

    assert fake.current_imsi == ATTACKER_IMSI
    assert ATTACKER_IMSI in fake.inbox  # le code a été livré à l'appareil attaquant, pas à l'ancien
    assert VICTIM_IMSI not in fake.inbox


def test_tool_has_no_knowledge_of_the_mode() -> None:
    """La force de la preuve tient à l'identité des deux exécutions : aucun drapeau de mode."""
    help_text = io.StringIO()
    with contextlib.redirect_stdout(help_text), contextlib.suppress(SystemExit):
        cli._parser().parse_args(["ac02", "-h"])
    text = help_text.getvalue().lower()
    for forbidden in ("--mode", "layerless", "sans-couche", "protection", "protege", "--couche"):
        assert forbidden not in text
