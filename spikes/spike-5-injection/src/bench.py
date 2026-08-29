"""SPIKE-5 composant 4 — banc de mesure. Modes : direct, http-unit, http-100,
http-1000. Protocole S7 : reset avant chaque mesure, chauffe non comptee, 3
repetitions, moyenne + ecart-type, empreinte disque par palier. Q7 avant tout
debit."""
import http.client
import json
import os
import statistics
import subprocess
import sys
import time

import psycopg
from pdp import (FakePDP, write_batch, decision_row, audit_row, reset_db,
                 decision_fingerprint)
from gen import generate

DSN = os.environ["DSN"]


def disk_bytes(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT pg_total_relation_size('pdp.decision')+pg_total_relation_size('pdp.audit_entry')")
        return cur.fetchone()[0]


# ---------- injection paths ----------

def inject_direct(conn, txns, with_chain, commit_every=1000):
    pdp = FakePDP()
    dec, aud = [], []
    with conn.cursor() as cur:
        for i, t in enumerate(txns, 1):
            d, r = pdp.evaluate(t)
            dec.append(decision_row(t, d, r))
            if with_chain:
                aud.append(audit_row(t, d, r))
            if i % commit_every == 0:
                write_batch(cur, dec, aud, with_chain); conn.commit(); dec, aud = [], []
        if dec:
            write_batch(cur, dec, aud, with_chain); conn.commit()


def http_post(cli, path, obj):
    b = json.dumps(obj).encode()
    cli.request("POST", path, body=b, headers={"Content-Length": str(len(b))})
    r = cli.getresponse(); r.read()


def inject_http(txns, batch):
    cli = http.client.HTTPConnection("127.0.0.1", 8099)   # persistent connection
    buf = []
    for t in txns:
        buf.append(t)
        if len(buf) >= batch:
            http_post(cli, "/batch", buf); buf = []
    if buf:
        http_post(cli, "/batch", buf)
    cli.close()


def server_reset():
    cli = http.client.HTTPConnection("127.0.0.1", 8099)
    http_post(cli, "/reset", {}); cli.close()


# ---------- measurement ----------

def measure(mode, n, conn, with_chain=True):
    """One timed run of n transactions in `mode`. Returns (seconds, tps)."""
    if mode in ("direct", "direct-unit"):
        # direct-unit = commit PER TRANSACTION (regime reel de l'evaluation en
        # ligne : une decision a la fois, un verrou chain_head par ecriture).
        # direct = commit par lot de 1000 (regime injection).
        commit_every = 1 if mode == "direct-unit" else 1000
        reset_db(conn)
        inject_direct(conn, generate(1000, seed=7), with_chain, commit_every)   # warmup
        reset_db(conn)
        t0 = time.perf_counter()
        inject_direct(conn, generate(n), with_chain, commit_every)
        dt = time.perf_counter() - t0
    else:
        batch = {"http-unit": 1, "http-100": 100, "http-1000": 1000}[mode]
        server_reset()
        inject_http(generate(1000, seed=7), batch)   # warmup
        server_reset()
        t0 = time.perf_counter()
        inject_http(generate(n), batch)
        dt = time.perf_counter() - t0
    return dt, n / dt


def q7(conn):
    """Same 10k, unitary vs batch-1000, identical decision fingerprints."""
    reset_db(conn)
    inject_direct(conn, generate(10000), with_chain=True, commit_every=1)   # unitaire
    fu = decision_fingerprint(conn)
    reset_db(conn)
    inject_direct(conn, generate(10000), with_chain=True, commit_every=1000)  # lots
    fb = decision_fingerprint(conn)
    print("Q7 unitaire.sha256 =", fu)
    print("Q7 lots-1000.sha256 =", fb)
    print("Q7 identiques =", fu == fb)


def main():
    cmd = sys.argv[1]
    conn = psycopg.connect(DSN, autocommit=False)
    if cmd == "q7":
        q7(conn)
    elif cmd == "measure":
        mode, n, reps = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
        wc = os.environ.get("WITH_CHAIN", "1") == "1"
        tpss = []
        for _ in range(reps):
            dt, tps = measure(mode, n, conn, with_chain=wc)
            tpss.append(tps)
            print(f"  {mode} n={n} chain={wc} : {dt:.2f}s  {tps:.0f} tx/s", flush=True)
        mean = statistics.mean(tpss)
        sd = statistics.pstdev(tpss) if len(tpss) > 1 else 0.0
        db = disk_bytes(conn)
        print(f"RESULT mode={mode} n={n} chain={wc} tps_mean={mean:.0f} tps_sd={sd:.0f} "
              f"secs_for_500k={500000/mean:.0f} disk_MB={db/1e6:.1f}", flush=True)


if __name__ == "__main__":
    main()
