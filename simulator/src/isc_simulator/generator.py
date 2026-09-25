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
    MSISDN_HIGH,
    MSISDN_LOW,
    PAYDAY_DAYS,
    PERSONAS_VERSION,
    SALARY_DAY,
    Archetype,
    Lognormal,
)

LABEL = "LEGIT"
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
)


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


def generate(seed: int, users: int, days: int, start: date) -> tuple[list[dict], dict]:
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
    for i in range(users):
        arch = by_name[names[i]]
        rk = stream(seed, "known", i)
        k = min(rk.randint(*arch.known_beneficiaries), users - 1)
        known_list = [j if j < i else j + 1 for j in rk.sample(range(users - 1), k)]
        known = set(known_list)

        ra = stream(seed, "amount", i)
        rb = stream(seed, "beneficiary", i)
        balance = draw_lognormal(stream(seed, "opening", i), arch.opening_balance)
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
            rows.append((ts, i, seq, arch.name, direction, amount, j, is_new, before, balance))

    rows.sort()
    out = []
    for ts, i, seq, name, direction, amount, j, is_new, before, after in rows:
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
            }
        )
    facts = {
        "archetype_users": dict(
            zip([a.name for a in ARCHETYPES], archetype_counts(users), strict=True)
        ),
        "skipped_out": skipped,
    }
    return out, facts


def write_csv(rows: list[dict], path: Path) -> str:
    """Write the sample with fixed '\\n' line endings (byte-stable across OSes); return SHA-256."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate legitimate synthetic MoMo traffic.")
    parser.add_argument("--seed", type=int, required=True, help="master seed ISC_SEED")
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--days", type=int, required=True)
    parser.add_argument("--start", type=date.fromisoformat, required=True, help="YYYY-MM-DD (UTC)")
    parser.add_argument("--out", type=Path, required=True, help="output CSV")
    args = parser.parse_args(argv)

    rows, facts = generate(args.seed, args.users, args.days, args.start)
    digest = write_csv(rows, args.out)
    manifest = {
        "personas_version": PERSONAS_VERSION,
        "isc_seed": args.seed,
        "users": args.users,
        "days": args.days,
        "start": args.start.isoformat(),
        "transactions": len(rows),
        "skipped_out": facts["skipped_out"],
        "archetype_users": facts["archetype_users"],
        "fields": list(FIELDS),
        "sha256": digest,
    }
    manifest_path = args.out.with_suffix(".manifest.json")
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"{len(rows)} transactions -> {args.out} (sha256 {digest})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
