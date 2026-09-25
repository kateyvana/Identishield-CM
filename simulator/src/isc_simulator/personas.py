"""Synthesis parameters of the legitimate population (docs/03-donnees/PERSONAS_v1.md, v1.1).

These are DATA-SYNTHESIS parameters, not product parameters: they drive the traffic generator
(T3-03), never the PDP. Product parameters (CAP_PROB, VEL, NIGHT, ...) live only in
config/ruleset.yaml.

CALIBRATED values copy the T3-01 reference profile (docs/03-donnees/profil_reel_v1.json,
aggregates only); tests/test_generator.py fails if they drift from it. Every other value is
MODELED (an assumption documented in PERSONAS, to be sensitivity-tested in T3-07).
"""

# @implements EAR-01
# @implements EAR-02

from __future__ import annotations

from dataclasses import dataclass

PERSONAS_VERSION = "v1.1"

# Fictitious MSISDN range (ED-09): users AND beneficiaries are drawn here, nowhere else.
MSISDN_LOW = 677000000
MSISDN_HIGH = 677009999

# CALIBRATED — quantile function of real amounts > 0 (FCFA), as (probability, amount) knots:
# min, p10, p25, p50, p75, p90, p95, p99, max of profil_reel_v1.json. The agent amount law samples
# it by log-linear interpolation (PERSONAS v1.1 §1.4: a single lognormal misses the real tail).
AGENT_AMOUNT_KNOTS = (
    (0.00, 0.5),
    (0.10, 128.2),
    (0.25, 500.0),
    (0.50, 1854.0),
    (0.75, 5000.0),
    (0.90, 15340.0),
    (0.95, 50750.0),
    (0.99, 500000.0),
    (1.00, 2015424.0),
)

# CALIBRATED — real transaction counts per hour 0..23: the diurnal profile shared by all archetypes.
BASE_HOUR_WEIGHTS = (
    773, 221, 143, 131, 129, 223, 705, 1207, 1515, 1469, 1624, 1749,
    1665, 1655, 1587, 1593, 1544, 1659, 1728, 1605, 1540, 1229, 935, 360,
)  # fmt: skip


@dataclass(frozen=True)
class Lognormal:
    """Lognormal law given by its median and log standard deviation, clipped to [1, cap]."""

    median: float
    sigma: float
    cap: int


@dataclass(frozen=True)
class Archetype:
    name: str
    share: float  # share of USERS in the population (PERSONAS §2)
    rate: float  # mean transactions per day (lambda)
    dispersion: float | None  # Gamma shape of the daily rate (over-dispersion); None = pure Poisson
    burst_share: float  # share of transactions emitted inside bursts
    burst_size: tuple[int, int]  # transactions per burst, inclusive range
    p_out: float  # probability that a regular transaction is an OUT (v1.1)
    amount_out: Lognormal | None  # None = empirical agent law (AGENT_AMOUNT_KNOTS)
    amount_in: Lognormal | None
    hour_boost_hours: tuple[int, ...]  # hours whose base weight is multiplied...
    hour_boost: float  # ...by this factor (1.0 = base profile)
    known_beneficiaries: tuple[int, int]  # K at initialisation, inclusive range
    p_new: float  # probability that an OUT goes to a never-seen beneficiary
    opening_balance: Lognormal  # v1.1
    salary: Lognormal | None = None  # monthly salary IN on SALARY_DAY (salaried only)
    payday_extra_out: float = 0.0  # extra OUT per day on PAYDAY_DAYS (salaried only)


# Salaried seasonality (PERSONAS §1.2): salary credited on day 28, bill burst on days 28-30.
SALARY_DAY = 28
PAYDAY_DAYS = (28, 29, 30)

# Two-second bursts are not realistic: burst members fall inside this many seconds.
BURST_SPAN_S = 600

ARCHETYPES = (
    Archetype(
        name="ordinaire",
        share=0.50,
        rate=0.30,
        dispersion=None,
        burst_share=0.0,
        burst_size=(1, 1),
        p_out=0.50,
        amount_out=Lognormal(1000, 1.1, 150_000),
        amount_in=Lognormal(1500, 1.1, 200_000),
        hour_boost_hours=(),
        hour_boost=1.0,
        known_beneficiaries=(2, 4),
        p_new=0.05,
        opening_balance=Lognormal(5_000, 1.0, 500_000),
    ),
    Archetype(
        name="salarie",
        share=0.25,
        rate=1.20,
        dispersion=None,
        burst_share=0.0,
        burst_size=(1, 1),
        p_out=0.40,
        amount_out=Lognormal(5000, 1.0, 300_000),
        amount_in=Lognormal(2000, 1.0, 500_000),
        hour_boost_hours=(12, 13, 18, 19, 20),
        hour_boost=1.3,
        known_beneficiaries=(3, 6),
        p_new=0.03,
        opening_balance=Lognormal(25_000, 0.9, 1_000_000),
        salary=Lognormal(80_000, 0.3, 500_000),
        payday_extra_out=1.0,
    ),
    Archetype(
        name="victime",  # an ordinary user tagged as an AC-02 target: same baseline as "ordinaire"
        share=0.05,
        rate=0.30,
        dispersion=None,
        burst_share=0.0,
        burst_size=(1, 1),
        p_out=0.50,
        amount_out=Lognormal(1000, 1.1, 150_000),
        amount_in=Lognormal(1500, 1.1, 200_000),
        hour_boost_hours=(),
        hour_boost=1.0,
        known_beneficiaries=(2, 4),
        p_new=0.05,
        opening_balance=Lognormal(5_000, 1.0, 500_000),
    ),
    Archetype(
        name="marchand",
        share=0.15,
        rate=6.0,
        dispersion=5.0,
        burst_share=0.10,
        burst_size=(2, 5),
        p_out=0.15,
        amount_out=Lognormal(8000, 1.3, 500_000),
        amount_in=Lognormal(1500, 1.2, 200_000),
        hour_boost_hours=tuple(range(8, 19)),  # 8h-19h weighted x1.5, no truncation (v1.1)
        hour_boost=1.5,
        known_beneficiaries=(10, 30),
        p_new=0.10,
        opening_balance=Lognormal(60_000, 0.9, 2_000_000),
    ),
    Archetype(
        name="agent",
        share=0.05,
        rate=30.0,
        dispersion=4.0,
        burst_share=0.25,
        burst_size=(4, 8),
        p_out=0.50,
        amount_out=None,
        amount_in=None,
        hour_boost_hours=(),
        hour_boost=1.0,
        known_beneficiaries=(50, 200),
        p_new=0.15,
        opening_balance=Lognormal(300_000, 0.7, 5_000_000),
    ),
)
