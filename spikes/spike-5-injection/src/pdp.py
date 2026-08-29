"""SPIKE-5 composant 2 — PDP factice. Regles TRIVIALES et PURES : chaque regle
recompte sa fenetre a chaque evaluation a partir de l'historique, jamais un
compteur elague au fil des ticks (obligation ADR-019 issue de SPIKE-2). La
fenetre de velocite est elaguee par HORODATAGE D'EVENEMENT (deterministe,
independant de la granularite d'avance) : le PDP n'evalue qu'aux instants des
transactions, donc lots et unitaire donnent des decisions identiques (Q7).

Ce qui est mesure est le COUT D'ECRITURE : decision + entree d'audit chainee."""
import json
from collections import defaultdict, deque
from datetime import datetime

VEL = 3            # plus de 3 sorties en 10 min -> FREEZE
VEL_WINDOW = 600   # secondes
NIGHT = (23, 5)    # heure UTC [23h, 05h)
AMOUNT_THR = 150000


class FakePDP:
    def __init__(self):
        self._outflows = defaultdict(deque)   # account -> deque[epoch seconds]

    def reset_state(self):
        self._outflows.clear()

    def evaluate(self, txn):
        ts = datetime.fromisoformat(txn["at"])
        epoch = ts.timestamp()
        reason = "-"
        decision = "ALLOW"
        if txn["op"] == "OUTFLOW":
            dq = self._outflows[txn["account"]]
            dq.append(epoch)
            # prune by EVENT timestamp, not by clock ticks -> Q7-safe
            while dq and dq[0] <= epoch - VEL_WINDOW:
                dq.popleft()
            if len(dq) > VEL:
                decision, reason = "FREEZE", "VEL"
        if decision == "ALLOW":
            h = ts.hour
            if h >= NIGHT[0] or h < NIGHT[1]:
                decision, reason = "STEP_UP", "NIGHT"
            elif txn["amount_xaf"] > AMOUNT_THR:
                decision, reason = "STEP_UP", "AMOUNT"
        return decision, reason


def decision_row(txn, decision, reason):
    return (txn["id"], txn["account"], decision, reason, txn["at"])


def audit_row(txn, decision, reason):
    payload = json.dumps({"id": txn["id"], "account": txn["account"],
                          "decision": decision, "reason": reason}, separators=(",", ":"))
    return (payload, "", "", txn["at"])   # prev_hash/hash filled by the trigger


DECISION_SQL = "INSERT INTO pdp.decision(id,account,decision,reason,at) VALUES (%s,%s,%s,%s,%s)"
AUDIT_SQL = "INSERT INTO pdp.audit_entry(payload,prev_hash,hash,written_at) VALUES (%s,%s,%s,%s)"


def write_batch(cur, dec_rows, aud_rows, with_chain):
    cur.executemany(DECISION_SQL, dec_rows)
    if with_chain:
        cur.executemany(AUDIT_SQL, aud_rows)


def reset_db(conn):
    with conn.cursor() as cur:
        cur.execute("TRUNCATE pdp.decision, pdp.audit_entry RESTART IDENTITY")
        cur.execute("UPDATE pdp.chain_head SET last_index=0,last_hash=repeat('0',64),n_records=0 WHERE id")
    conn.commit()


def decision_fingerprint(conn):
    """SHA-256 of the full decision sequence, ordered by virtual time (= injection
    order, timestamps strictly increasing)."""
    import hashlib
    h = hashlib.sha256()
    with conn.cursor() as cur:
        cur.execute("SELECT decision,reason FROM pdp.decision ORDER BY at, id")
        for d, r in cur:
            h.update(f"{d}|{r}\n".encode())
    return h.hexdigest()
