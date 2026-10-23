"""Générateur de charge de bout en bout pour POST /evaluate (ENF-01, SQ-30), stdlib seulement.

Il se place là où est le cœur : un conteneur sur isc-net, avec le certificat client du cœur, qui
parle au PDP en mTLS sur des connexions PERSISTANTES (comme PdpClient, OB-6), et envoie de vrais
POST /evaluate en JSON.

BOUCLE OUVERTE. Les requêtes sont planifiées à un rythme fixe (--rate par seconde, pendant
--seconds). La latence de chaque requête est comptée depuis l'instant PRÉVU de son envoi, pas
depuis son envoi réel : si le PDP prend du retard et que les fils sont tous occupés, l'attente est
comptée au lieu d'être cachée. La durée depuis l'envoi réel est aussi relevée, pour comparaison.

Mélange : celui de tools/bench_evaluate (moitié FREEZE, moitié ALLOW, comptes neufs). FREEZE =
changement d'IMSI une heure avant, sortie du solde entier ; ALLOW = 1 000 FCFA, aucun changement
d'IMSI. La décision rendue est relevée, jamais imposée.

Ce qui n'est PAS mesuré : le temps ajouté par le client Java du cœur lui-même (limite déclarée,
voir README).

Usage (dans un conteneur python:3.12-alpine sur isc-net, certificats montés en /certs) :
  python e2e_load.py --out /b/e2e.csv --tag main-1 [--rate 50] [--seconds 600] [--workers 32]
"""

import argparse
import http.client
import json
import queue
import ssl
import sys
import threading
import time
import uuid
from datetime import UTC, datetime, timedelta

BALANCE = 100_000


def _context(cert: str, key: str, ca: str) -> ssl.SSLContext:
    ctx = ssl.create_default_context(cafile=ca)
    ctx.load_cert_chain(cert, key)
    return ctx


def _request(i: int, tag: str) -> tuple[str, bytes]:
    freeze = i % 2 == 0
    now = datetime.now(UTC)
    body = {
        "decisionId": str(uuid.uuid4()),
        "accountId": f"e2e-{tag}-{i}-{'f' if freeze else 'a'}-{uuid.uuid4().hex[:8]}",
        "msisdn": f"67700{i % 10000:04d}",
        "currentImsi": f"62401{(900000000 if freeze else 100000000) + i % 100000000:010d}",
        "channel": "USSD",
        "type": "P2P",
        "amountXaf": BALANCE if freeze else 1_000,
        "beneficiaryId": "677009000",
        "serverTimestamp": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "proofType": "NONE",
        "balanceXaf": BALANCE,
    }
    if freeze:
        body["imsiChangedAt"] = (now - timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return ("FREEZE" if freeze else "ALLOW"), json.dumps(body).encode()


class Worker(threading.Thread):
    """Un fil = une connexion mTLS persistante, reconnectée seulement en cas d'erreur."""

    def __init__(self, host, port, ctx, jobs, out, tag, origin):
        super().__init__(daemon=True)
        self.host, self.port, self.ctx = host, port, ctx
        self.jobs, self.out, self.tag, self.origin = jobs, out, tag, origin
        self.conn = None

    def _connection(self):
        if self.conn is None:
            self.conn = http.client.HTTPSConnection(
                self.host, self.port, context=self.ctx, timeout=30
            )
        return self.conn

    def run(self):
        while True:
            job = self.jobs.get()
            if job is None:
                return
            i, due = job
            kind, body = _request(i, self.tag)
            sent = time.perf_counter()
            status, decision, latency_ms = 0, "ERROR", -1
            try:
                c = self._connection()
                c.request("POST", "/evaluate", body, {"Content-Type": "application/json"})
                r = c.getresponse()
                payload = r.read()
                status = r.status
                if status == 200:
                    doc = json.loads(payload)
                    decision, latency_ms = doc.get("decision", "?"), doc.get("latencyMs", -1)
            except (OSError, http.client.HTTPException):
                if self.conn is not None:
                    self.conn.close()
                self.conn = None
            done = time.perf_counter()
            self.out.append(
                (
                    i,
                    kind,
                    decision,
                    status,
                    int((done - due) * 1e6),
                    int((done - sent) * 1e6),
                    latency_ms,
                    int((due - self.origin) * 1e3),
                )
            )


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="pdp")
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--cert", default="/certs/core.crt")
    p.add_argument("--key", default="/certs/core.key")
    p.add_argument("--ca", default="/certs/ca.crt")
    p.add_argument("--rate", type=float, default=50.0)
    p.add_argument("--seconds", type=int, default=600)
    p.add_argument("--workers", type=int, default=32)
    p.add_argument("--warmup", type=int, default=60)
    p.add_argument("--tag", required=True)
    p.add_argument("--out", required=True)
    a = p.parse_args()

    ctx = _context(a.cert, a.key, a.ca)
    jobs: queue.Queue = queue.Queue()
    out: list = []
    origin = time.perf_counter()
    workers = [Worker(a.host, a.port, ctx, jobs, out, a.tag, origin) for _ in range(a.workers)]
    for w in workers:
        w.start()

    # Rodage jeté (le premier appel à froid n'est pas représentatif), en séquentiel.
    for i in range(a.warmup):
        jobs.put((-1 - i, time.perf_counter()))
        while len(out) <= i:
            time.sleep(0.005)
    out.clear()

    # Boucle ouverte : la requête n est envoyée à t0 + n / rate, quoi qu'il arrive aux précédentes.
    t0 = time.perf_counter()
    total = int(a.rate * a.seconds)
    for n in range(total):
        due = t0 + n / a.rate
        wait = due - time.perf_counter()
        if wait > 0:
            time.sleep(wait)
        jobs.put((n, due))
    for _ in workers:
        jobs.put(None)
    for w in workers:
        w.join()
    elapsed = time.perf_counter() - t0

    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("i,kind,decision,status,fromDueMicros,fromSendMicros,latencyMs,dueAtMs\n")
        f.writelines(",".join(str(x) for x in row) + "\n" for row in sorted(out))
    errors = sum(1 for r in out if r[3] != 200)
    print(f"e2e {a.tag} : {len(out)} requêtes en {elapsed:.1f} s, {errors} erreur(s) -> {a.out}")
    return 0 if errors == 0 and len(out) == total else 1


if __name__ == "__main__":
    sys.exit(main())
