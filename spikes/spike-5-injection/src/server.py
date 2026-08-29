"""SPIKE-5 — PDP factice en PROCESSUS SEPARE (Q1/Q2), derriere HTTP. Mono-thread :
une seule decision a la fois, comme un PDP unique. Commit par requete (donc par
lot). POST /batch [txns] ; POST /reset."""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

import psycopg
from pdp import FakePDP, write_batch, decision_row, audit_row, reset_db

DSN = os.environ["DSN"]
WITH_CHAIN = os.environ.get("WITH_CHAIN", "1") == "1"
conn = psycopg.connect(DSN, autocommit=False)
pdp = FakePDP()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _read(self):
        n = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(n)) if n else None

    def do_POST(self):
        body = self._read()
        if self.path == "/reset":
            reset_db(conn)
            pdp.reset_state()
            return self._ok({"reset": True})
        # /batch or /evaluate : body is a list of transactions
        txns = body if isinstance(body, list) else [body]
        dec, aud = [], []
        for t in txns:
            d, r = pdp.evaluate(t)
            dec.append(decision_row(t, d, r))
            if WITH_CHAIN:
                aud.append(audit_row(t, d, r))
        with conn.cursor() as cur:
            write_batch(cur, dec, aud, WITH_CHAIN)
        conn.commit()
        self._ok({"n": len(txns)})

    def _ok(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8099), H).serve_forever()
