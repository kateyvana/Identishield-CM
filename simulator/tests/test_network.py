"""Tests du simulateur de réseau SMS (isc-sim) : la boîte SIM livre et se lit par IMSI ;
la chronologie d'attaque se publie, se lit et se réinitialise (T2-17)."""

from __future__ import annotations

from datetime import UTC, datetime

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


# Carte #65 (D4) : l'heure de remise est gardée avec le code, en UTC, depuis une horloge injectée.
T1 = datetime(2026, 10, 26, 14, 5, 9, tzinfo=UTC)
T2 = datetime(2026, 10, 26, 14, 7, 0, tzinfo=UTC)


def test_delivery_time_comes_from_the_injected_clock_in_utc() -> None:
    inbox = SimInbox(now=lambda: T1)
    inbox.deliver("624010677000200", "483920")
    assert inbox.delivered_at("624010677000200") == "2026-10-26T14:05:09Z"


def test_a_new_delivery_replaces_the_code_and_its_time() -> None:
    times = iter([T1, T2])
    inbox = SimInbox(now=lambda: next(times))
    inbox.deliver("624010677000200", "111111")
    inbox.deliver("624010677000200", "222222")
    # La boîte ne garde que le dernier SMS (limite déclarée D4).
    assert inbox.read("624010677000200") == "222222"
    assert inbox.delivered_at("624010677000200") == "2026-10-26T14:07:00Z"


def test_unknown_imsi_has_no_delivery_time() -> None:
    assert SimInbox(now=lambda: T1).delivered_at("624010000000010") is None
