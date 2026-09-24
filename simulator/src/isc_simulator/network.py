"""Simulateur de réseau SMS (isc-sim) — le TRANSPORT de l'OTP, hors du cœur.

Dans le monde réel, le cœur (serveur d'authentification) émet l'OTP et le remet à une passerelle SMS ;
le RÉSEAU le livre à la SIM de l'IMSI courant ; qui détient cette SIM lit le code. Ce composant joue
le réseau : le cœur lui REMET l'OTP (POST /deliver, appel cœur→réseau), et qui interroge la boîte
d'un IMSI — donc qui détient cette SIM — lit le code (GET /inbox).

C'est là, et JAMAIS dans le cœur, que vit le canal d'interception d'AC-02 : le cœur de production ne
doit contenir aucun moyen de lire un OTP livré (ADR-034, conforme à ISC-SAD §5 qui nomme isc-sim).

isc-sim porte aussi la CHRONOLOGIE d'attaque (T2-17, EF-41) : l'outil attaquant PUBLIE chaque étape
ici (POST /timeline), la console la LIT ici (GET /timeline). La chronologie est une donnée de
SIMULATION ; la faire transiter par le cœur y réintroduirait l'infrastructure d'attaque qu'ADR-034
en a sortie. Le cœur n'en sait donc rien.

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


class Timeline:
    """Chronologie d'attaque (T2-17, EF-41) : étapes publiées par l'outil attaquant, en mémoire.

    Une exécution d'attaque commence à l'étape 1 : recevoir une étape 1 REMET la chronologie à zéro,
    de sorte qu'un nouveau lancement efface le précédent (la console montre la course en cours, pas un
    cumul). Volatile : c'est un canal de démonstration, pas un journal.
    """

    def __init__(self) -> None:
        self._steps: list[dict] = []

    def publish(self, entry: dict) -> int:
        if entry.get("step") == 1:
            self._steps = []
        self._steps.append(entry)
        return len(self._steps)

    def all(self) -> list[dict]:
        return list(self._steps)

    def reset(self) -> None:
        self._steps = []


TIMELINE = Timeline()


class Handler(BaseHTTPRequestHandler):
    """Canal SMS + chronologie d'attaque.

    POST /deliver {imsi, code} (le cœur remet un SMS) ; GET /inbox?imsi= (interception) ;
    POST /timeline {step, message, ts, ...} (l'attaquant publie une étape) ; GET /timeline (la console
    lit) ; POST /timeline/reset ; GET /health.
    """

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
        if parsed.path == "/timeline":
            self._send(200, {"steps": TIMELINE.all()})
            return
        self._send(404, {"error": "ressource inconnue"})

    def do_POST(self) -> None:  # nom imposé par http.server (BaseHTTPRequestHandler)
        parsed = urlparse(self.path)
        if parsed.path == "/timeline/reset":
            TIMELINE.reset()
            self._send(200, {"reset": True})
            return
        if parsed.path == "/timeline":
            length = int(self.headers.get("Content-Length", 0) or 0)
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                self._send(400, {"error": "corps JSON invalide"})
                return
            step, message = body.get("step"), body.get("message")
            if not isinstance(step, int) or not message:
                self._send(400, {"error": "step (entier) et message obligatoires"})
                return
            count = TIMELINE.publish(body)
            self._send(200, {"stored": True, "count": count})
            return
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
