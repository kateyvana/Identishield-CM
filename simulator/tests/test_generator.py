"""Legitimate-traffic generator (T3-03): determinism, balances, fictitious MSISDNs, calibration."""

import json
import uuid
from datetime import date
from pathlib import Path

import pytest

from isc_simulator.generator import generate, write_csv
from isc_simulator.personas import (
    AGENT_AMOUNT_KNOTS,
    BASE_HOUR_WEIGHTS,
    MSISDN_HIGH,
    MSISDN_LOW,
)
from isc_simulator.stats import ruleset_windows

ROOT = Path(__file__).resolve().parents[2]
ETALON = ROOT / "docs" / "03-donnees" / "profil_reel_v1.json"
RULESET = ROOT / "config" / "ruleset.yaml"

# Small but complete: every archetype present, and days 28-30 (salary + bill burst) covered.
SEED, USERS, DAYS, START = 20260925, 120, 10, date(2026, 10, 25)


@pytest.fixture(scope="module")
def sample():
    rows, facts = generate(SEED, USERS, DAYS, START)
    return rows, facts


# @covers EAR-04
def test_same_seed_gives_byte_identical_output(tmp_path, sample):
    first = write_csv(sample[0], tmp_path / "a.csv")
    second = write_csv(generate(SEED, USERS, DAYS, START)[0], tmp_path / "b.csv")
    assert first == second
    assert (tmp_path / "a.csv").read_bytes() == (tmp_path / "b.csv").read_bytes()


# @covers EAR-04
def test_another_seed_gives_another_output(tmp_path, sample):
    first = write_csv(sample[0], tmp_path / "a.csv")
    other = write_csv(generate(SEED + 1, USERS, DAYS, START)[0], tmp_path / "c.csv")
    assert first != other


def test_balance_never_negative_and_consistent(sample):
    rows, facts = sample
    assert rows, "the sample must not be empty"
    last = {}
    for r in rows:
        assert r["balance_before"] >= 0 and r["balance_after"] >= 0
        delta = -r["amount"] if r["direction"] == "OUT" else r["amount"]
        assert r["balance_after"] == r["balance_before"] + delta
        if r["user"] in last:
            assert r["balance_before"] == last[r["user"]], "a user's balance chain is broken"
        last[r["user"]] = r["balance_after"]
    # The skip rule is really exercised (OUT larger than the balance), not vacuously true.
    assert facts["skipped_out"] > 0


# @covers ED-09
def test_every_msisdn_is_in_the_fictitious_range(sample):
    for r in sample[0]:
        assert MSISDN_LOW <= int(r["user"]) <= MSISDN_HIGH
        assert MSISDN_LOW <= int(r["beneficiary"]) <= MSISDN_HIGH
        assert r["user"] != r["beneficiary"]


def test_population_beyond_the_fictitious_range_is_refused():
    with pytest.raises(ValueError):
        generate(SEED, MSISDN_HIGH - MSISDN_LOW + 2, 1, START)


# @covers ED-09
def test_transaction_ids_are_unique_uuid_v4(sample):
    ids = [r["id"] for r in sample[0]]
    assert len(set(ids)) == len(ids)
    assert all(uuid.UUID(i).version == 4 for i in ids)


def test_new_beneficiary_is_really_new_and_only_on_out(sample):
    seen = {}
    for r in sample[0]:
        mine = seen.setdefault(r["user"], set())
        if r["is_new_beneficiary"] == "true":
            assert r["direction"] == "OUT"
            assert r["beneficiary"] not in mine
        mine.add(r["beneficiary"])


def test_everything_is_labelled_legit(sample):
    assert {r["label"] for r in sample[0]} == {"LEGIT"}


def test_calibrated_constants_match_the_reference_profile():
    ref = json.loads(ETALON.read_text(encoding="utf-8"))
    pos = ref["a_montant"]["montants_strictement_positifs"]
    expected = [pos["min"]] + [
        pos["quantiles"][k] for k in ("p10", "p25", "p50", "p75", "p90", "p95", "p99")
    ]
    expected.append(pos["max"])
    assert [a for _, a in AGENT_AMOUNT_KNOTS] == expected
    hours = sorted(ref["b_heure_du_jour"]["par_heure"], key=lambda h: h["heure"])
    assert list(BASE_HOUR_WEIGHTS) == [h["compte"] for h in hours]


def test_night_and_velocity_windows_are_read_from_a_rule_set(tmp_path):
    snippet = tmp_path / "ruleset.yaml"
    snippet.write_text(
        'ruleset:\n  parameters:\n    NIGHT: "22:00-06:00" # x\n'
        "    VEL: # y\n      count: 5\n      window: PT15M\n",
        encoding="utf-8",
    )
    assert ruleset_windows(snippet) == ({22, 23, 0, 1, 2, 3, 4, 5}, 5, 900)
    ruleset_windows(RULESET)  # the real versioned rule set parses too
