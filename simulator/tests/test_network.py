"""Tests du simulateur de réseau SMS (isc-sim) : la boîte SIM livre et se lit par IMSI."""

from __future__ import annotations

from isc_simulator.network import SimInbox


def test_deliver_then_read_returns_the_code() -> None:
    inbox = SimInbox()
    inbox.deliver("624010000000666", "483920")
    assert inbox.read("624010000000666") == "483920"


def test_read_unknown_imsi_returns_none() -> None:
    inbox = SimInbox()
    assert inbox.read("624010000000010") is None


def test_delivery_is_per_imsi() -> None:
    inbox = SimInbox()
    inbox.deliver("imsi-attaquant", "111111")
    inbox.deliver("imsi-victime", "222222")
    assert inbox.read("imsi-attaquant") == "111111"
    assert inbox.read("imsi-victime") == "222222"
