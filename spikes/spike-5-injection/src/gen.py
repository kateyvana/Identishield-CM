"""SPIKE-5 composant 1 — generateur de flux deterministe a graine fixee.
Produit N transactions minimales (S 4), horodatage virtuel croissant. Le
realisme n'importe pas : ce spike mesure un debit. Plage MSISDN fictive
699000000-699009999 (sandbox)."""
import hashlib
import uuid
from datetime import datetime, timedelta, timezone

ACCOUNTS = 1000
CHANNELS = ("USSD", "APP", "AGENT")
OPS = ("OUTFLOW", "INFLOW", "BALANCE")
START = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _rand(seed, i):
    # deterministic per (seed, i) without Math.random-style global state
    h = hashlib.sha256(f"{seed}:{i}".encode()).digest()
    return int.from_bytes(h[:8], "big")


def _mk(seed, i, acct, op, r, t):
    raw = hashlib.sha256(f"{seed}:id:{i}".encode()).digest()[:16]
    return {
        "id": str(uuid.UUID(bytes=raw, version=4)),
        "account": str(acct),
        "msisdn": str(acct),
        "imsi": str(624010000000000 + (r % 5000)),
        "channel": CHANNELS[(r >> 3) % 3],
        "op": op,
        "amount_xaf": 500 * (1 + (r >> 7) % 400),
        "beneficiary": str(699000000 + ((r >> 9) % ACCOUNTS)),
        "at": t.isoformat(),
        "locality": ("Yaounde", "Douala", "Garoua")[(r >> 11) % 3],
    }


def generate(n, seed=42):
    """Yield n deterministic transactions with strictly increasing virtual time.
    Background traffic advances 1..5 s/txn. About 1 in 15 transactions starts a
    BURST of 5 same-account OUTFLOWs 20..80 s apart (5 in ~5 min) so the velocity
    rule (VEL, > 3 outflows / 10 min) actually fires — otherwise Q7 would not
    exercise the time-order-sensitive path, the only one batching can break."""
    t = START
    i = 0
    while i < n:
        r = _rand(seed, i)
        if r % 15 == 0 and i + 5 <= n:                 # burst of 5 same-account outflows
            acct = 699000000 + (r % ACCOUNTS)
            for k in range(5):
                rk = _rand(seed, i)
                t = t + timedelta(seconds=20 + (rk >> (k + 1)) % 60)
                yield _mk(seed, i, acct, "OUTFLOW", rk, t)
                i += 1
        else:
            acct = 699000000 + (r % ACCOUNTS)
            t = t + timedelta(seconds=1 + (r >> 8) % 5)
            yield _mk(seed, i, acct, OPS[(r >> 5) % 3], r, t)
            i += 1
