"""Tests de l'outil attaquant (EF-40) : l'orchestration d'AC-02 et l'ABSENCE de toute connaissance
du mode sans couche — sans cœur ni réseau vivants (un faux cœur + faux isc-sim en mémoire).

Le refactor : l'OTP s'intercepte sur isc-sim (GET /inbox), plus sur le cœur ; le vidage passe par
le canal USSD (dial + saisies), plus par un /operation générique.
"""

from __future__ import annotations

import contextlib
import io
from urllib.parse import parse_qs, urlparse

from isc_attacker import cli

ATTACKER_IMSI = "624010000000666"
VICTIM_IMSI = "624010000000001"


class FakeStack:
    """Cœur + isc-sim en mémoire : juste assez pour dérouler AC-02 et vérifier l'orchestration."""

    def __init__(self, balance: int = 500_000) -> None:
        self.balance = balance
        self.current_imsi = VICTIM_IMSI
        self.inbox: dict[str, str] = {}  # tenu par isc-sim, pas par le cœur
        self.notifs: list[dict] = []
        self.session: dict = {}
        self.timeline: list[dict] = []  # chronologie publiée sur isc-sim (T2-17), pas sur le cœur

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
            self.inbox[self.current_imsi] = "483920"  # le cœur REMET au réseau (isc-sim)
            return 200, {"targetImsi": self.current_imsi, "delivered": True}
        if method == "GET" and path == "/inbox":  # lecture sur isc-sim = interception
            imsi = query["imsi"][0]
            if imsi in self.inbox:
                return 200, {"imsi": imsi, "code": self.inbox[imsi]}
            return 404, {"error": "aucun message"}
        if method == "POST" and path == "/otp/verify":
            return 200, {"outcome": "ACCEPTED"}
        if method == "POST" and path == "/ussd/dial":
            self.session = {"step": "amount"}
            return 200, {"sessionId": "sess-fake", "message": "Montant ?", "sessionOpen": True}
        if method == "POST" and path == "/ussd/input":
            return self._ussd_input(body["text"])
        if method == "GET" and path == "/notifications":
            return 200, self.notifs
        if method == "POST" and path == "/timeline":  # publication de la chronologie sur isc-sim
            self.timeline.append(body)
            return 200, {"stored": True, "count": len(self.timeline)}
        raise AssertionError(f"appel inattendu : {method} {path}")

    def _ussd_input(self, text: str) -> tuple[int, dict]:
        step = self.session.get("step")
        if step == "amount":
            self.session["amount"] = int(text)
            self.session["step"] = "beneficiary"
            return 200, {"message": "Beneficiaire ?", "sessionOpen": True}
        if step == "beneficiary":
            self.session["step"] = "confirm"
            return 200, {"message": "Confirmer ? 1=Oui", "sessionOpen": True}
        if step == "confirm":
            self.balance -= self.session["amount"]
            self.notifs.append({"recipientRef": self.current_imsi, "type": "P2P_EXECUTED"})
            return 200, {"message": "Transfert EXECUTED. Reference did-fake", "sessionOpen": False}
        raise AssertionError("etape USSD inattendue")


def _args():
    return cli._parser().parse_args(
        [
            "ac02",
            "--victim",
            "677000007",
            "--victim-imsi",
            VICTIM_IMSI,
            "--attacker-imsi",
            ATTACKER_IMSI,
            "--beneficiary",
            "677000008",
            "--agent-id",
            "agent-kyc-07",
            "--motif",
            "remplacement SIM",
        ]
    )


def test_ac02_empties_the_account_and_reports_facts(monkeypatch, capsys) -> None:
    fake = FakeStack()
    monkeypatch.setattr(cli, "_request", fake.request)

    code = cli.run_ac02("http://core:8080", "http://sim:8080", _args())

    assert code == 0
    out = capsys.readouterr().out
    assert "ORACLE_BALANCE_INITIAL=500000" in out
    assert "ORACLE_BALANCE_FINAL=0" in out
    assert "ORACLE_OUTPUTS=1" in out
    assert f"ORACLE_OTP_TARGET_IMSI={ATTACKER_IMSI}" in out  # l'IMSI attaquant, pas la victime
    assert "ORACLE_OLD_DEVICE_NOTIFICATIONS=0" in out


def test_otp_targets_the_attacker_imsi_after_the_swap(monkeypatch) -> None:
    """L'OTP part vers l'IMSI COURANT, qui après le swap est celui de l'attaquant."""
    fake = FakeStack()
    monkeypatch.setattr(cli, "_request", fake.request)

    cli.run_ac02("http://core:8080", "http://sim:8080", _args())

    assert fake.current_imsi == ATTACKER_IMSI
    assert ATTACKER_IMSI in fake.inbox  # le code a été livré à l'appareil attaquant, pas à l'ancien
    assert VICTIM_IMSI not in fake.inbox


def test_publishes_the_eight_steps_to_isc_sim_in_order(monkeypatch) -> None:
    """La chronologie (T2-17, EF-41) est publiée VERS isc-sim, dans l'ordre, jamais vers le cœur.
    L'étape d'interception OTP est marquée `sensitive` pour que la CONSOLE masque le code."""
    fake = FakeStack()
    monkeypatch.setattr(cli, "_request", fake.request)

    cli.run_ac02("http://core:8080", "http://sim:8080", _args())

    steps = fake.timeline
    assert [s["step"] for s in steps] == [1, 2, 3, 4, 5, 6, 7, 8]  # 8 étapes, dans l'ordre
    assert all("ts" in s and "message" in s for s in steps)
    # Chaque étape est publiée en FR ET EN (console bilingue, anglais par défaut).
    assert all(s.get("messageEn") for s in steps)
    assert "victim" in steps[0]["messageEn"]  # l'anglais est bien rempli, pas un doublon du FR
    assert steps[0]["victim"] == "677000007"  # l'étape 1 porte la victime (la console lit son HLR)
    otp = next(s for s in steps if s.get("sensitive"))  # l'étape OTP est marquée sensible
    assert otp["step"] == 4


def test_tool_has_no_knowledge_of_the_mode() -> None:
    """La force de la preuve tient à l'identité des deux exécutions : aucun drapeau de mode."""
    help_text = io.StringIO()
    with contextlib.redirect_stdout(help_text), contextlib.suppress(SystemExit):
        cli._parser().parse_args(["ac02", "-h"])
    text = help_text.getvalue().lower()
    for forbidden in ("--mode", "layerless", "sans-couche", "protection", "protege", "--couche"):
        assert forbidden not in text
