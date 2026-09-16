"""Simulateur de réseau SMS (isc-sim) — le TRANSPORT de l'OTP, hors du cœur.

Dans le monde réel, le cœur (serveur d'authentification) émet l'OTP et le remet à une passerelle SMS ;
le RÉSEAU le livre à la SIM de l'IMSI courant ; qui détient cette SIM lit le code. Ce composant joue
le réseau : le cœur lui REMET l'OTP (POST /deliver, appel cœur→réseau), et qui interroge la boîte
d'un IMSI — donc qui détient cette SIM — lit le code (GET /inbox).

C'est là, et JAMAIS dans le cœur, que vit le canal d'interception d'AC-02 : le cœur de production ne
doit contenir aucun moyen de lire un OTP livré (ADR-034, conforme à ISC-SAD §5 qui nomme isc-sim).

Stdlib uniquement (http.server + json ; ADR-033), comme les autres outils. Livraison en mémoire,
volatile : c'est un canal, pas un journal.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class SimInbox:
    """Boîtes SIM par IMSI : dernier code livré à chaque IMSI. En mémoire, volatile."""

    def __init__(self) -> None:
        self._delivered: dict[str, str] = {}

    def deliver(self, imsi: str, code: str) -> None:
        self._delivered[imsi] = code

    def read(self, imsi: str) -> str | None:
        return self._delivered.get(imsi)


INBOX = SimInbox()


class Handler(BaseHTTPRequestHandler):
    """POST /deliver {imsi, code} (le cœur remet un SMS) ; GET /inbox?imsi= (interception) ; /health."""

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # nom imposé par http.server (BaseHTTPRequestHandler)
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send(200, {"status": "ok"})
            return
        if parsed.path == "/inbox":
            imsi = parse_qs(parsed.query).get("imsi", [""])[0]
            code = INBOX.read(imsi)
            if code is None:
                self._send(404, {"error": f"aucun message pour l'IMSI {imsi}"})
                return
            self._send(200, {"imsi": imsi, "code": code})
            return
        self._send(404, {"error": "ressource inconnue"})

    def do_POST(self) -> None:  # nom imposé par http.server (BaseHTTPRequestHandler)
        parsed = urlparse(self.path)
        if parsed.path == "/deliver":
            length = int(self.headers.get("Content-Length", 0) or 0)
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                self._send(400, {"error": "corps JSON invalide"})
                return
            imsi, code = body.get("imsi"), body.get("code")
            if not imsi or not code:
                self._send(400, {"error": "imsi et code obligatoires"})
                return
            INBOX.deliver(imsi, code)
            self._send(200, {"imsi": imsi, "delivered": True})
            return
        self._send(404, {"error": "ressource inconnue"})

    def log_message(self, *args: object) -> None:  # silencieux : pas de bruit dans les logs
        pass


def main() -> None:
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
