"""Tests du simulateur de réseau SMS (isc-sim) : la boîte SIM livre et se lit par IMSI ;
la chronologie d'attaque se publie, se lit et se réinitialise (T2-17)."""

from __future__ import annotations

from isc_simulator.network import SimInbox, Timeline


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


def test_timeline_publishes_and_reads_in_order() -> None:
    tl = Timeline()
    tl.publish({"step": 1, "message": "constat initial"})
    tl.publish({"step": 2, "message": "SIM reattribuee"})
    steps = tl.all()
    assert [s["step"] for s in steps] == [1, 2]
    assert steps[0]["message"] == "constat initial"


def test_timeline_step_one_resets_previous_run() -> None:
    tl = Timeline()
    tl.publish({"step": 1, "message": "ancienne execution"})
    tl.publish({"step": 2, "message": "ancienne suite"})
    # Un nouveau lancement recommence a l'etape 1 : la chronologie precedente est effacee.
    tl.publish({"step": 1, "message": "nouvelle execution"})
    steps = tl.all()
    assert [s["step"] for s in steps] == [1]
    assert steps[0]["message"] == "nouvelle execution"


def test_timeline_reset_empties_it() -> None:
    tl = Timeline()
    tl.publish({"step": 1, "message": "x"})
    tl.reset()
    assert tl.all() == []
