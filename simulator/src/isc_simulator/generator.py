"""Legitimate-traffic generator of the synthetic population (T3-03, PERSONAS v1.1).

Every transaction produced here is LEGITIMATE (label LEGIT): fraud injection is T3-04, the frozen
500k dataset is T3-05. This module only turns the personas into a deterministic flow.

Determinism (PERSONAS §6): one master seed ISC_SEED -> named sub-seeds, one per (use, user). No
random generator is shared, so every draw is addressed by its sub-seed and the output does not
depend on iteration order. Same seed + same configuration -> byte-identical CSV.

Balance (PERSONAS v1.1 §1.6): every user holds a balance drawn from an archetype law. An IN
credits it, an OUT debits it, and an OUT larger than the current balance is SKIPPED, never
reduced: reducing it to the balance would make ordinary users routinely empty their account, the
very signature of an AC-02 drain, and would pollute the ground truth T3-04 builds upon.

Ledger scope: each user's ledger is independent (not double-entry). The counterparty of a flow is
a real user of the population (internal graph, PERSONAS §4.1) but its own balance is not touched,
which keeps each user's stream independent of the others'.

Stdlib only (ADR-033).
"""

# @implements EAR-01
# @implements EAR-04
# @implements ED-04
# @implements ED-09

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
import uuid
from datetime import UTC, date, datetime, timedelta
from itertools import pairwise
from pathlib import Path

from isc_simulator.personas import (
    AGENT_AMOUNT_KNOTS,
    ARCHETYPES,
    BASE_HOUR_WEIGHTS,
    BURST_SPAN_S,
    DAYS_PER_MONTH,
    IMSI_PREFIX,
    MSIN_DIGITS,
    MSISDN_HIGH,
    MSISDN_LOW,
    PAYDAY_DAYS,
    PERSONAS_VERSION,
    REPLACEMENT_MSIN_BASE,
    SALARY_DAY,
    SIM_EVENT_RATES_PER_MONTH,
    Archetype,
    Lognormal,
)

LABEL = "LEGIT"
SOURCE = "generator"
# Full internal record (PERSONAS v1.4 §1.11). It is NEVER written as such: each output file is a
# projection of it, so that the ground truth cannot reach the detector by construction.
FIELDS = (
    "id",
    "user",
    "archetype",
    "direction",
    "amount",
    "timestamp",
    "beneficiary",
    "is_new_beneficiary",
    "balance_before",
    "balance_after",
    "label",
    "current_imsi",
    "channel",
    "device_bound",
    "source",
    "sim_event_case",
)
# Detector view: what an operator observes, the only file the core / PDP consume.
DETECTOR_FIELDS = (
    "id",
    "user",
    "direction",
    "amount",
    "timestamp",
    "beneficiary",
    "balance_before",
    "balance_after",
    "current_imsi",
    "channel",
    "device_bound",
)
# Sidecar: ground truth and generator annotations, joined on id only. `archetype` (whose value
# "victime" tags a target) and `is_new_beneficiary` (computed from generator-only knowledge) are
# annotations the PDP could never observe, so they live here with the label.
LABEL_FIELDS = ("id", "label", "source", "archetype", "is_new_beneficiary", "sim_event_case")
# HLR view of SIM events: what the HLR really emits (IMSI changes only), without the case.
HLR_EVENT_FIELDS = ("msisdn", "old_imsi", "new_imsi", "timestamp", "agent_id")
HLR_AGENTS = 20  # customer-service agent pool, drawn independently of the case


def sub_seed(master: int, *parts: object) -> int:
    """Named sub-seed: a pure function of the master seed and the (use, user) address."""
    key = "|".join(str(p) for p in (master, *parts)).encode("utf-8")
    return int.from_bytes(hashlib.sha256(key).digest()[:8], "big")


def stream(master: int, *parts: object) -> random.Random:
    return random.Random(sub_seed(master, *parts))


def poisson(rng: random.Random, lam: float) -> int:
    """Knuth's method: exact and deterministic, fast enough for the rates used here (<= ~100)."""
    if lam <= 0:
        return 0
    limit = math.exp(-lam)
    k = 0
    p = rng.random()
    while p > limit:
        k += 1
        p *= rng.random()
    return k


def draw_lognormal(rng: random.Random, law: Lognormal) -> int:
    x = rng.lognormvariate(math.log(law.median), law.sigma)
    return min(law.cap, max(1, round(x)))


def draw_agent_amount(rng: random.Random) -> int:
    """Inverse CDF of the real amounts: log-linear interpolation between the reference quantiles."""
    u = rng.random()
    for (p0, a0), (p1, a1) in pairwise(AGENT_AMOUNT_KNOTS):
        if u <= p1:
            t = (u - p0) / (p1 - p0)
            return max(1, round(math.exp(math.log(a0) + t * (math.log(a1) - math.log(a0)))))
    return round(AGENT_AMOUNT_KNOTS[-1][1])


def hour_cum_weights(arch: Archetype) -> list[float]:
    cum, total = [], 0.0
    for h, w in enumerate(BASE_HOUR_WEIGHTS):
        total += w * (arch.hour_boost if h in arch.hour_boost_hours else 1.0)
        cum.append(total)
    return cum


def burst_probability(arch: Archetype) -> float:
    """Probability that an emission unit is a burst, so that bursts carry `burst_share` of txs."""
    b = arch.burst_share
    if b <= 0:
        return 0.0
    m = (arch.burst_size[0] + arch.burst_size[1]) / 2
    return b / (m * (1 - b) + b)


def archetype_counts(users: int) -> list[int]:
    """Users per archetype from the population shares (largest remainder, deterministic)."""
    raw = [a.share * users for a in ARCHETYPES]
    counts = [math.floor(r) for r in raw]
    order = sorted(range(len(raw)), key=lambda i: (-(raw[i] - counts[i]), i))
    for i in order[: users - sum(counts)]:
        counts[i] += 1
    return counts


def transaction_id(master: int, i: int, seq: int) -> str:
    """UUID v4 (ED-09) whose random bits come from the transaction's address: reproducible."""
    digest = hashlib.sha256(f"{master}|txid|{i}|{seq}".encode()).digest()
    return str(uuid.UUID(bytes=digest[:16], version=4))


def imsi(index: int) -> str:
    """Initial IMSI of a user (ADR-035): 624-01 + MSIN = index + 1 on 10 digits."""
    msin = index + 1
    if not 1 <= msin < 10**MSIN_DIGITS:
        raise ValueError(f"user index {index} has no MSIN")
    return f"{IMSI_PREFIX}{msin:0{MSIN_DIGITS}d}"


SIM_EVENT_FIELDS = (
    "user",
    "timestamp",
    "case",
    "old_imsi",
    "new_imsi",
    "device_bound_before",
    "device_bound_after",
)


def replacement_imsi(index: int, ordinal: int) -> str:
    """IMSI of a replacement SIM: separate MSIN block, unique per (user, ordinal)."""
    if not 1 <= ordinal <= 9:
        raise ValueError(f"replacement ordinal {ordinal} out of 1..9")
    return f"{IMSI_PREFIX}{REPLACEMENT_MSIN_BASE + index * 10 + ordinal:0{MSIN_DIGITS}d}"


def plan_sim_events(
    master: int, i: int, start: date, days: int, bound: bool, rates: dict[str, float]
) -> list[tuple]:
    """Legitimate SIM events of one user (PERSONAS v1.3 §1.10), applied in time order.

    Each case occurs at most once in the window, with probability 1 - exp(-rate x months), at a
    uniform instant. Case a needs a bound device at that instant; otherwise it does not occur.
    Returns (ts, case, old_imsi, new_imsi, bound_before, bound_after) tuples.
    """
    rs = stream(master, "sim_event", i)
    months = days / DAYS_PER_MONTH
    base = int(datetime(start.year, start.month, start.day, tzinfo=UTC).timestamp())
    drawn = []
    for case in ("a", "b", "c"):
        occurs = rs.random() < 1 - math.exp(-rates[case] * months)
        instant = base + rs.randrange(days * 86400)
        if occurs:
            drawn.append((instant, case))
    events, current, ordinal = [], imsi(i), 0
    for ts, case in sorted(drawn):
        if case == "a" and not bound:
            continue
        new_imsi = current
        if case in ("a", "b"):
            ordinal += 1
            new_imsi = replacement_imsi(i, ordinal)
        after = {"a": True, "b": False, "c": True}[case]
        events.append((ts, case, current, new_imsi, bound, after))
        current, bound = new_imsi, after
    return events


def msisdn(index: int) -> str:
    number = MSISDN_LOW + index
    if not MSISDN_LOW <= number <= MSISDN_HIGH:
        raise ValueError(f"user index {index} leaves the fictitious MSISDN range")
    return str(number)


def plan_events(master: int, i: int, arch: Archetype, start: date, days: int) -> list[tuple]:
    """Timestamps and directions of one user's transactions, before amounts and balance."""
    rt = stream(master, "timing", i)
    rh = stream(master, "hour", i)
    rd = stream(master, "direction", i)
    cum = hour_cum_weights(arch)
    q = burst_probability(arch)
    events: list[tuple] = []

    def instant(day_base: int) -> int:
        hour = rh.choices(range(24), cum_weights=cum)[0]
        return day_base + hour * 3600 + rh.randrange(3600)

    for d in range(days):
        day = start + timedelta(days=d)
        base = int(datetime(day.year, day.month, day.day, tzinfo=UTC).timestamp())
        lam = arch.rate
        if arch.dispersion is not None:
            lam = rt.gammavariate(arch.dispersion, arch.rate / arch.dispersion)
        remaining = poisson(rt, lam)
        while remaining > 0:
            size = 1
            if q > 0 and rt.random() < q:
                size = min(remaining, rt.randint(*arch.burst_size))
            t0 = instant(base)
            offsets = [0] + sorted(rt.randrange(BURST_SPAN_S) for _ in range(size - 1))
            for off in offsets:
                direction = "OUT" if rd.random() < arch.p_out else "IN"
                events.append((t0 + off, len(events), direction, "regular"))
            remaining -= size
        if arch.salary is not None and day.day == SALARY_DAY:
            events.append((instant(base), len(events), "IN", "salary"))
        if arch.payday_extra_out > 0 and day.day in PAYDAY_DAYS:
            for _ in range(poisson(rt, arch.payday_extra_out)):
                events.append((instant(base), len(events), "OUT", "regular"))
    events.sort()
    return events


def generate(
    seed: int,
    users: int,
    days: int,
    start: date,
    sim_event_rates: dict[str, float] | None = None,
) -> tuple[list[dict], dict]:
    """Deterministic legitimate flow -> (rows sorted by time, generation facts)."""
    if users < 2:
        raise ValueError("at least two users are needed to form a beneficiary graph")
    msisdn(users - 1)  # fail fast if the population exceeds the fictitious range

    names = [
        a.name for a, c in zip(ARCHETYPES, archetype_counts(users), strict=True) for _ in range(c)
    ]
    stream(seed, "population").shuffle(names)
    by_name = {a.name: a for a in ARCHETYPES}

    rows: list[tuple] = []
    skipped = 0
    sim_events: list[tuple] = []
    hlr_events: list[tuple] = []
    for i in range(users):
        arch = by_name[names[i]]
        rk = stream(seed, "known", i)
        k = min(rk.randint(*arch.known_beneficiaries), users - 1)
        known_list = [j if j < i else j + 1 for j in rk.sample(range(users - 1), k)]
        known = set(known_list)

        ra = stream(seed, "amount", i)
        rb = stream(seed, "beneficiary", i)
        balance = draw_lognormal(stream(seed, "opening", i), arch.opening_balance)
        # v1.2: separate sub-seeds, so the draws of the existing fields are untouched.
        bound = stream(seed, "device", i).random() < arch.p_device_bound
        rc = stream(seed, "channel", i)
        # v1.3: legitimate SIM events change current_imsi and device_bound from their instant on.
        rates = SIM_EVENT_RATES_PER_MONTH if sim_event_rates is None else sim_event_rates
        user_events = plan_sim_events(seed, i, start, days, bound, rates)
        sim_events.extend((ev[0], i, *ev[1:]) for ev in user_events)
        # v1.4: the HLR sees IMSI changes only (cases a, b); the agent is drawn independently of
        # the case, from its own sub-seed, so that it cannot reveal it.
        ragent = stream(seed, "hlr_agent", i)
        for ts_ev, case, old, new, _, _ in user_events:
            if old != new:
                agent = f"agent-kyc-{ragent.randrange(1, HLR_AGENTS + 1):02d}"
                hlr_events.append((ts_ev, i, old, new, agent))
        current, pending = imsi(i), list(user_events)
        for seq, (ts, _, direction, kind) in enumerate(plan_events(seed, i, arch, start, days)):
            if kind == "salary":
                amount = draw_lognormal(ra, arch.salary)
            else:
                law = arch.amount_out if direction == "OUT" else arch.amount_in
                amount = draw_agent_amount(ra) if law is None else draw_lognormal(ra, law)
            is_new = False
            if direction == "OUT":
                if amount > balance:
                    skipped += 1
                    continue
                if len(known) < users - 1 and rb.random() < arch.p_new:
                    while True:
                        j = rb.randrange(users)
                        if j != i and j not in known:
                            break
                    known.add(j)
                    known_list.append(j)
                    is_new = True
                else:
                    j = known_list[rb.randrange(len(known_list))]
                before, balance = balance, balance - amount
            else:
                j = known_list[rb.randrange(len(known_list))]
                before, balance = balance, balance + amount
            applied = []
            while pending and pending[0][0] <= ts:
                _, case, _, current, _, bound = pending.pop(0)
                applied.append(case)
            channel = "APP" if bound and rc.random() < arch.p_app_if_bound else "USSD"
            rows.append(
                (
                    ts,
                    i,
                    seq,
                    arch.name,
                    direction,
                    amount,
                    j,
                    is_new,
                    before,
                    balance,
                    channel,
                    bound,
                    current,
                    "+".join(applied),
                )
            )

    rows.sort()
    out = []
    for row in rows:
        (
            ts,
            i,
            seq,
            name,
            direction,
            amount,
            j,
            is_new,
            before,
            after,
            channel,
            bound,
            cur,
            cases,
        ) = row
        out.append(
            {
                "id": transaction_id(seed, i, seq),
                "user": msisdn(i),
                "archetype": name,
                "direction": direction,
                "amount": amount,
                "timestamp": datetime.fromtimestamp(ts, UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "beneficiary": msisdn(j),
                "is_new_beneficiary": "true" if is_new else "false",
                "balance_before": before,
                "balance_after": after,
                "label": LABEL,
                "current_imsi": cur,
                "channel": channel,
                "device_bound": "true" if bound else "false",
                "source": SOURCE,
                # SIM event case(s) attached to the user's first transaction after the event.
                "sim_event_case": cases,
            }
        )
    facts = {
        "archetype_users": dict(
            zip([a.name for a in ARCHETYPES], archetype_counts(users), strict=True)
        ),
        "skipped_out": skipped,
        "sim_events": [
            {
                "user": msisdn(i),
                "timestamp": datetime.fromtimestamp(ts, UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "case": case,
                "old_imsi": old,
                "new_imsi": new,
                "device_bound_before": "true" if b0 else "false",
                "device_bound_after": "true" if b1 else "false",
            }
            for ts, i, case, old, new, b0, b1 in sorted(sim_events)
        ],
        "hlr_events": [
            {
                "msisdn": msisdn(i),
                "old_imsi": old,
                "new_imsi": new,
                "timestamp": datetime.fromtimestamp(ts, UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "agent_id": agent,
            }
            for ts, i, old, new, agent in sorted(hlr_events)
        ],
    }
    return out, facts


def sidecar_paths(view: Path) -> tuple[Path, Path]:
    """<stem>.labels.csv and <stem>.hlr_events.csv beside the detector view."""
    return view.with_name(view.stem + ".labels.csv"), view.with_name(view.stem + ".hlr_events.csv")


def write_csv(rows: list[dict], path: Path, fields: tuple[str, ...] = DETECTOR_FIELDS) -> str:
    """Write a PROJECTION of the records on `fields` (other keys are dropped on purpose), with
    fixed '\\n' line endings (byte-stable across OSes); return SHA-256. Defaults to the detector
    view, so that nothing but observable columns is written unless explicitly asked."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate legitimate synthetic MoMo traffic.")
    parser.add_argument("--seed", type=int, required=True, help="master seed ISC_SEED")
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--days", type=int, required=True)
    parser.add_argument("--start", type=date.fromisoformat, required=True, help="YYYY-MM-DD (UTC)")
    parser.add_argument(
        "--out",
        type=Path,
        required=True,
        help="detector view CSV; <stem>.labels.csv and <stem>.hlr_events.csv are written beside it",
    )
    args = parser.parse_args(argv)

    rows, facts = generate(args.seed, args.users, args.days, args.start)
    labels_path, hlr_path = sidecar_paths(args.out)
    digest = write_csv(rows, args.out)
    files = {
        "vue_detecteur": {"file": args.out.name, "sha256": digest, "fields": list(DETECTOR_FIELDS)},
        "sidecar_labels": {
            "file": labels_path.name,
            "sha256": write_csv(rows, labels_path, LABEL_FIELDS),
            "fields": list(LABEL_FIELDS),
        },
        "hlr_events": {
            "file": hlr_path.name,
            "sha256": write_csv(facts["hlr_events"], hlr_path, HLR_EVENT_FIELDS),
            "fields": list(HLR_EVENT_FIELDS),
            "events": len(facts["hlr_events"]),
        },
    }
    manifest = {
        "personas_version": PERSONAS_VERSION,
        "isc_seed": args.seed,
        "users": args.users,
        "days": args.days,
        "start": args.start.isoformat(),
        "transactions": len(rows),
        "skipped_out": facts["skipped_out"],
        "archetype_users": facts["archetype_users"],
        "files": files,
        "sha256": digest,
        # Ground-truth summary (the detector consumes the view file only, never this manifest).
        "verite_terrain_sim_events": {
            "total": len(facts["sim_events"]),
            "per_case": {c: sum(e["case"] == c for e in facts["sim_events"]) for c in "abc"},
        },
    }
    manifest_path = args.out.with_suffix(".manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"{len(rows)} transactions -> {args.out} (sha256 {digest})")
    for name, f in files.items():
        print(f"  {name}: {f['file']} sha256 {f['sha256']}")
    print(f"  verite-terrain SIM events: {manifest['verite_terrain_sim_events']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
