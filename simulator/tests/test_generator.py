"""Legitimate-traffic generator (T3-03): determinism, balances, fictitious MSISDNs, calibration."""

import csv
import json
import re
import uuid
from datetime import date, datetime
from pathlib import Path

import pytest

from isc_simulator.generator import (
    DETECTOR_FIELDS,
    FIELDS,
    HLR_EVENT_FIELDS,
    LABEL_FIELDS,
    amount_law,
    generate,
    write_csv,
)
from isc_simulator.personas import (
    BASE_HOUR_WEIGHTS,
    MSISDN_HIGH,
    MSISDN_LOW,
)
from isc_simulator.stats import ruleset_windows

ROOT = Path(__file__).resolve().parents[2]
ETALON = ROOT / "docs" / "03-donnees" / "profil_reel_v1.json"
ETALON_V11 = ROOT / "docs" / "03-donnees" / "profil_reel_v1.1.json"
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


# v2.0 (T3-05a): the amount law is no longer embedded constants but the reference profile v1.1
# itself (D1); the anti-drift check now compares the generator's law with that profile.
def test_calibrated_constants_match_the_reference_profile():
    ref = json.loads(ETALON.read_text(encoding="utf-8"))
    ref11 = json.loads(ETALON_V11.read_text(encoding="utf-8"))["f_v1_1"]
    m = ref11["montants_masses_ponctuelles"]
    p_mass, values, cum, knots = amount_law()
    assert p_mass == m["part_totale"]
    assert values == [round(x["montant"]) for x in m["masses"]]
    assert knots == [(k["p"], k["montant"]) for k in m["noeuds_du_reste"]]
    # each of the K parts is rounded to 4 decimals in the profile: the relative weights only
    # choose among the masses, so the gap is bounded by the rounding, not a bias
    assert abs(cum[-1] - m["part_totale"]) <= m["k"] * 0.00005
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


# --- PERSONAS v1.2: current IMSI, channel, device binding ---------------------------------------


# @covers ED-09
def test_current_imsi_is_a_15_digit_operator_imsi_stable_and_unique_per_user(sample):
    imsi_of = {}
    for r in sample[0]:
        assert re.fullmatch(r"62401\d{10}", r["current_imsi"]), r["current_imsi"]
        assert imsi_of.setdefault(r["user"], r["current_imsi"]) == r["current_imsi"]
    assert len(set(imsi_of.values())) == len(imsi_of), "two users share an IMSI"


def test_channel_is_ussd_or_app(sample):
    assert {r["channel"] for r in sample[0]} <= {"USSD", "APP"}
    assert {r["channel"] for r in sample[0]} == {"USSD", "APP"}, "both channels must occur"


# v1.3: device_bound is no longer constant per user (cases b and c change it); its stability
# between events is proven by test_imsi_and_binding_change_only_at_sim_events below.
def test_app_channel_implies_a_bound_device(sample):
    for r in sample[0]:
        assert r["device_bound"] in {"true", "false"}
        if r["channel"] == "APP":
            assert r["device_bound"] == "true"


# --- PERSONAS v1.3: legitimate SIM events (customer service) ------------------------------------

# High rates on purpose, so that every case occurs in a small population (the real rates are
# declared assumptions far below this).
DENSE = {"a": 3.0, "b": 3.0, "c": 3.0}


@pytest.fixture(scope="module")
def dense():
    return generate(SEED, USERS, DAYS, START, sim_event_rates=DENSE)


def epoch_of(ts):
    return datetime.fromisoformat(ts).timestamp()


def test_every_case_occurs_in_the_dense_run(dense):
    assert {e["case"] for e in dense[1]["sim_events"]} == {"a", "b", "c"}


def test_imsi_and_binding_change_only_at_sim_events(dense):
    rows, facts = dense
    events = {}
    for e in facts["sim_events"]:
        events.setdefault(e["user"], []).append(e)
    last = {}
    for r in rows:
        if r["user"] in last:
            prev = last[r["user"]]
            changed = (prev["current_imsi"], prev["device_bound"]) != (
                r["current_imsi"],
                r["device_bound"],
            )
            if changed:
                between = [
                    e
                    for e in events.get(r["user"], [])
                    if epoch_of(prev["timestamp"])
                    < epoch_of(e["timestamp"])
                    <= epoch_of(r["timestamp"])
                ]
                assert between, f"state changed without a SIM event for {r['user']}"
                assert between[-1]["new_imsi"] == r["current_imsi"]
                assert between[-1]["device_bound_after"] == r["device_bound"]
        last[r["user"]] = r


def test_case_c_keeps_the_imsi_and_binds_a_device(dense):
    for e in dense[1]["sim_events"]:
        if e["case"] == "c":
            assert e["old_imsi"] == e["new_imsi"]
            assert e["device_bound_after"] == "true"
        else:
            assert e["old_imsi"] != e["new_imsi"]


def test_case_a_needs_a_bound_device_and_case_b_unbinds(dense):
    for e in dense[1]["sim_events"]:
        if e["case"] == "a":
            assert e["device_bound_before"] == e["device_bound_after"] == "true"
        if e["case"] == "b":
            assert e["device_bound_after"] == "false"


def test_replacement_imsis_are_unique_operator_imsis(dense):
    new = [e["new_imsi"] for e in dense[1]["sim_events"] if e["case"] in ("a", "b")]
    assert len(set(new)) == len(new)
    initial = {
        r["current_imsi"]
        for r in generate(SEED, USERS, DAYS, START, {"a": 0.0, "b": 0.0, "c": 0.0})[0]
    }
    assert all(re.fullmatch(r"62401\d{10}", i) for i in new)
    assert not set(new) & initial, "a replacement IMSI collides with an initial one"


def test_app_implies_bound_device_under_sim_events(dense):
    for r in dense[0]:
        if r["channel"] == "APP":
            assert r["device_bound"] == "true"


def test_sim_events_leave_the_base_columns_untouched(dense):
    base = FIELDS[: FIELDS.index("label") + 1]
    quiet = generate(SEED, USERS, DAYS, START, {"a": 0.0, "b": 0.0, "c": 0.0})[0]
    assert [[r[k] for k in base] for r in quiet] == [[r[k] for k in base] for r in dense[0]]


# --- PERSONAS v1.4: detector view, label sidecar, HLR events (anti-leak) ------------------------

ORACLE_NAMES = {
    "label",
    "labels",
    "case",
    "sim_event_case",
    "legit",
    "fraud",
    "ground_truth",
    "intent",
    "archetype",
    "is_new_beneficiary",
    "source",
}
ORACLE_PATTERN = re.compile(
    r"label|case|legit|fraud|ground.?truth|intent|archetype|victim", re.IGNORECASE
)


def run_cli(tmp_path, name="flow", users="300", days="60"):
    from isc_simulator.generator import main

    out = tmp_path / f"{name}.csv"
    argv = ["--seed", str(SEED), "--users", users, "--days", days, "--start", "2026-10-01"]
    assert main([*argv, "--out", str(out)]) == 0
    labels = tmp_path / f"{name}.labels.csv"
    hlr = tmp_path / f"{name}.hlr_events.csv"
    return out, labels, hlr


def header(path):
    return path.read_text(encoding="utf-8").splitlines()[0].split(",")


def read_rows(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


@pytest.fixture(scope="module")
def cli_files(tmp_path_factory):
    return run_cli(tmp_path_factory.mktemp("cli"))


# @covers EAR-03
def test_detector_view_and_sidecar_share_only_the_join_key(cli_files):
    view, labels, _ = cli_files
    assert set(DETECTOR_FIELDS) & set(LABEL_FIELDS) == {"id"}
    assert set(header(view)) & set(header(labels)) == {"id"}
    assert header(view) == list(DETECTOR_FIELDS)
    assert header(labels) == list(LABEL_FIELDS)


# @covers EAR-03
def test_no_oracle_column_in_the_detector_or_hlr_views(cli_files):
    view, _, hlr = cli_files
    for columns in (header(view), header(hlr), DETECTOR_FIELDS, HLR_EVENT_FIELDS):
        assert not set(columns) & ORACLE_NAMES, columns  # by list
        assert not [c for c in columns if ORACLE_PATTERN.search(c)], columns  # by regex


def test_no_ground_truth_value_leaks_into_the_detector_view(cli_files):
    text = cli_files[0].read_text(encoding="utf-8")
    for value in ("LEGIT", "FRAUD", "generator", "ordinaire", "salarie", "victime", "marchand"):
        assert value not in text, value


def test_every_view_row_has_exactly_one_sidecar_row(cli_files):
    view, labels, _ = cli_files
    view_ids = [r["id"] for r in read_rows(view)]
    label_ids = [r["id"] for r in read_rows(labels)]
    assert len(set(view_ids)) == len(view_ids) and len(set(label_ids)) == len(label_ids)
    assert set(view_ids) == set(label_ids)
    assert {r["label"] for r in read_rows(labels)} == {"LEGIT"}
    assert {r["source"] for r in read_rows(labels)} == {"generator"}


def test_hlr_events_carry_imsi_changes_only_and_no_case(dense):
    facts = dense[1]
    changes = [e for e in facts["sim_events"] if e["case"] in ("a", "b")]
    assert len(facts["hlr_events"]) == len(changes), "case c is not an HLR event"
    for e in facts["hlr_events"]:
        assert set(e) == set(HLR_EVENT_FIELDS)
        assert e["old_imsi"] != e["new_imsi"]
        assert re.fullmatch(r"agent-kyc-\d{2}", e["agent_id"])


def test_sim_event_case_is_attached_to_the_first_transaction_after_the_event(dense):
    rows, facts = dense
    for e in facts["sim_events"]:
        after = [
            r
            for r in rows
            if r["user"] == e["user"] and epoch_of(r["timestamp"]) >= epoch_of(e["timestamp"])
        ]
        if after:  # an event after the user's last transaction has no row to attach to
            assert e["case"] in after[0]["sim_event_case"].split("+")


def test_the_three_files_are_byte_identical_for_the_same_seed(tmp_path):
    first = run_cli(tmp_path / "one")
    second = run_cli(tmp_path / "two")
    for a, b in zip(first, second, strict=True):
        assert a.read_bytes() == b.read_bytes(), a.name
    manifest = json.loads(first[0].with_suffix(".manifest.json").read_text(encoding="utf-8"))
    assert {f["file"] for f in manifest["files"].values()} == {p.name for p in first}


# --- PERSONAS v2.0 (T3-05a): config/synthese.yaml, per-user rates, amount mixture, sessions ------


def test_synthese_reader_refuses_unsupported_yaml():
    from isc_simulator import synthese

    for bad in (
        "synthese:\n  mix: [1, 2]\n",
        "synthese:\n   x: 1\n",
        "synthese:\n  a: 1\n  a: 2\n",
    ):
        with pytest.raises(ValueError):
            synthese.parse(bad)


def test_synthese_mix_must_sum_to_one(tmp_path):
    from isc_simulator import synthese

    text = synthese.CONFIG.read_text(encoding="utf-8").replace("agent: 0.010000", "agent: 0.020000")
    bad = tmp_path / "synthese.yaml"
    bad.write_text(text, encoding="utf-8")
    with pytest.raises(ValueError, match="sum"):
        synthese.load(bad)


def test_agents_are_one_percent_of_the_users():
    from isc_simulator.generator import archetype_counts
    from isc_simulator.personas import ARCHETYPES

    counts = dict(zip([a.name for a in ARCHETYPES], archetype_counts(2480), strict=True))
    assert sum(counts.values()) == 2480
    assert counts["agent"] == round(0.01 * 2480)


def test_rates_follow_the_bands_order(dense):
    per_user = {}
    for r in dense[0]:
        per_user.setdefault((r["archetype"], r["user"]), 0)
        per_user[(r["archetype"], r["user"])] += 1
    mean = {}
    for (arch, _), n in per_user.items():
        mean.setdefault(arch, []).append(n)
    mean = {a: sum(v) / len(v) for a, v in mean.items()}
    assert mean["ordinaire"] < mean["salarie"] < mean["marchand"]


def test_amounts_follow_the_reference_mixture(dense):
    p_mass, values, _, _ = amount_law()
    amounts = [r["amount"] for r in dense[0]]
    assert min(amounts) >= 1
    share = sum(1 for a in amounts if a in set(values)) / len(amounts)
    assert abs(share - p_mass) < 0.05, share  # salaries (own law) aside, the masses carry p_mass


def test_the_public_groups_its_transactions_into_sessions(dense):
    from isc_simulator import synthese

    span = synthese.load()["sessions"]["span_s"]
    times = {}
    for r in dense[0]:
        if r["archetype"] in ("ordinaire", "victime", "salarie"):
            times.setdefault(r["user"], []).append(epoch_of(r["timestamp"]))
    gaps = [
        b - a for ts in times.values() for a, b in zip(sorted(ts), sorted(ts)[1:], strict=False)
    ]
    assert sum(1 for g in gaps if g <= span) / len(gaps) > 0.05


def test_session_directions_large_is_one_direction_and_regular_alternates():
    import dataclasses
    import random

    from isc_simulator.generator import session_directions
    from isc_simulator.personas import ARCHETYPES

    base = next(a for a in ARCHETYPES if a.name == "ordinaire")
    rng = random.Random(1)
    for _ in range(200):
        d = session_directions(rng, base, 5, True)
        assert len(set(d)) == 1  # a large session takes ONE direction
    alt = dataclasses.replace(base, burst_alternate=1.0)
    for _ in range(200):
        d = session_directions(rng, alt, 3, False)
        assert d[0] != d[1] and d[1] != d[2]  # a regular session alternates
