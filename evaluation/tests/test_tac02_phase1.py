"""T-AC-02 — phase 1 (ISC-TEST §4 ; ENF-09 « les cas d'abus sont des tests » ; CR-01).

Prouve, en test AUTOMATISÉ et versionné, que l'attaque AC-02 (vidage immédiat post-SIM swap, USSD)
RÉUSSIT quand la couche de sécurité est désactivée — l'état « avant » de la démonstration du mois 2.
C'est une preuve permanente : si un jour l'attaque cesse de réussir SANS la couche, ce test échoue.

Règle des deux phases (ISC-TEST §4) : ce fichier ne couvre que la PHASE 1 (sans couche). La phase 2
(l'attaque ÉCHOUE AVEC la couche) est le mois 4, quand les règles existeront — hors de ce test.

ASSERTIONS. Les positives prouvent que l'attaque marche ; les NÉGATIVES prouvent qu'elle marche PARCE
QU'il n'y a pas de couche (ISC-TEST §4 : « l'assertion négative est la partie que les projets
oublient »). L'assertion négative centrale — l'opération a été exécutée SANS qu'aucune décision de la
couche soit émise (aucune entrée d'audit) — est FAUSSE dès que la couche est active : c'est elle qui
fait échouer ce test en mode protégé (voir test_la_couche_active_ferait_echouer_la_phase1).

INTÉGRATION. Ce test exige la pile debout (postgres, cœur, PDP, isc-sim) et l'outil attaquant. Il
S'IGNORE proprement (skip) quand Docker ou la pile sont absents — ainsi `make check` (portes légères,
sans Docker) reste VERT. Il s'exécute pour de vrai via `make abuse` ou dans la VM, contre la pile.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import urllib.error
import urllib.request

import pytest

CONSOLE = os.environ.get("ISC_CONSOLE_URL", "http://localhost:3000")
PG = "isc-postgres"
NET = "isc-net"

VICTIM = "677000007"
BENEFICIARY = "677000008"
VICTIM_IMSI = "624010000000010"  # ancien appareil de la victime
ATTACKER_IMSI = "624010000000666"  # SIM de l'attaquant
INITIAL_BALANCE = 500000

_UUID = re.compile(r"decision ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

# Crochet de démonstration (ISC-TEST §4, « prouve-le maintenant ») : par défaut la phase 1 tourne
# SANS couche (le vrai test). ISC_TAC02_FORCE_PROTECTED=1 rejoue LA MÊME phase 1 avec la couche
# ACTIVE, pour montrer qu'elle ÉCHOUE alors — l'assertion négative « aucune décision émise » devient
# fausse (une décision ALLOW est journalisée). N'affecte JAMAIS un run normal (défaut = sans couche).
_PHASE1_LAYERLESS = os.environ.get("ISC_TAC02_FORCE_PROTECTED", "").lower() not in (
    "1",
    "true",
    "yes",
)


# --------------------------------------------------------------------------------------------------
# Disponibilité de la pile : sans Docker ni pile joignable, le test S'IGNORE (make check reste vert).
# --------------------------------------------------------------------------------------------------
def _stack_available() -> bool:
    if shutil.which("docker") is None:
        return False
    try:
        with urllib.request.urlopen(f"{CONSOLE}/api/mode", timeout=4) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


pytestmark = pytest.mark.skipif(
    not _stack_available(),
    reason="pile IdentiShield absente (Docker + postgres/cœur/PDP/isc-sim debout requis) — "
    "test d'intégration, exécuté par `make abuse` ou dans la VM",
)


# --------------------------------------------------------------------------------------------------
# Aides : psql (oracle en base), bascule de mode, mise en scène, lancement de l'attaque.
# --------------------------------------------------------------------------------------------------
def _sql(query: str) -> str:
    """Une valeur scalaire lue en base par le propriétaire du schéma (oracle indépendant)."""
    out = subprocess.run(
        ["docker", "exec", PG, "psql", "-U", "isc_admin", "-d", "identishield", "-tAc", query],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    return out.stdout.strip()


def _set_mode(*, layerless: bool) -> None:
    body = ('{"layerless": %s}' % ("true" if layerless else "false")).encode()
    req = urllib.request.Request(
        f"{CONSOLE}/api/mode",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        assert resp.status == 200, "bascule de mode refusée par la console"


def _seed_victim() -> None:
    """État initial déterministe (ISC-TEST §4 préconditions) : victime approvisionnée, SIM d'origine,
    aucun résidu d'une exécution précédente. `docker exec -i` : le SQL passe par stdin.

    Le compte de la victime est RECRÉÉ à chaque passage, donc avec un identifiant neuf : l'état de
    confiance du PDP (probation, gel) est tenu par identifiant de compte, dans le schéma du PDP, que
    ce test ne possède pas. Réutiliser le compte ferait dépendre le résultat de ce qui a tourné avant
    (un gel laissé par un passage précédent rend REJECTED au lieu de FREEZE)."""
    sql = f"""
    DELETE FROM core.transaction WHERE account_id IN
      (SELECT id FROM core.account WHERE msisdn IN ('{VICTIM}','{BENEFICIARY}'));
    DELETE FROM core.notification WHERE msisdn='{VICTIM}';
    DELETE FROM core.otp WHERE msisdn='{VICTIM}';
    DELETE FROM core.hlr_reassignment WHERE msisdn='{VICTIM}';
    DELETE FROM core.account WHERE msisdn='{VICTIM}';
    INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('{VICTIM}', {INITIAL_BALANCE}, now());
    INSERT INTO core.account(msisdn, balance_xaf, created_at) VALUES ('{BENEFICIARY}', 0, now())
      ON CONFLICT (msisdn) DO NOTHING;
    INSERT INTO core.hlr_subscriber(msisdn, current_imsi, updated_at) VALUES ('{VICTIM}', '{VICTIM_IMSI}', now())
      ON CONFLICT (msisdn) DO UPDATE SET current_imsi='{VICTIM_IMSI}', updated_at=now();
    """
    r = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            PG,
            "psql",
            "-U",
            "isc_admin",
            "-d",
            "identishield",
            "-qtA",
            "-v",
            "ON_ERROR_STOP=1",
        ],
        input=sql,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert r.returncode == 0, f"mise en scène échouée : {r.stderr}"
    assert _sql(f"SELECT balance_xaf FROM core.account WHERE msisdn='{VICTIM}'") == str(
        INITIAL_BALANCE
    )


def _run_attack() -> str:
    """Lance isc-attacker ac02 contre le cœur, sur isc-net. Renvoie sa sortie brute (chronologie +
    ORACLE_*). L'outil ne connaît RIEN du mode : c'est CE test qui l'a fixé avant."""
    src = os.path.join(os.getcwd(), "attacker", "src")
    r = subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            NET,
            "-e",
            "CORE_URL=http://core:8080",
            "-e",
            "SIM_URL=http://sim:8080",
            "-e",
            "PYTHONPATH=/src",
            "-v",
            f"{src}:/src:ro",
            "python:3.12-alpine",
            "python",
            "-m",
            "isc_attacker",
            "ac02",
            "--victim",
            VICTIM,
            "--victim-imsi",
            VICTIM_IMSI,
            "--attacker-imsi",
            ATTACKER_IMSI,
            "--beneficiary",
            BENEFICIARY,
            "--agent-id",
            "agent-kyc-07",
            "--motif",
            "remplacement SIM",
        ],
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )
    assert r.returncode == 0, (
        f"l'outil attaquant a échoué (rc={r.returncode}) : {r.stdout}\n{r.stderr}"
    )
    return r.stdout


def _decision_id(attack_output: str) -> str:
    m = _UUID.search(attack_output)
    assert m, "impossible de lire le decisionId du vidage dans la sortie de l'attaque"
    return m.group(1)


def _audit_count(decision_id: str) -> int:
    return int(
        _sql(f"SELECT count(*) FROM pdp.audit_entry WHERE payload->>'decisionId'='{decision_id}'")
    )


@pytest.fixture(autouse=True)
def _restore_protected_mode():
    yield
    # Ne jamais laisser la pile désarmée après le test.
    try:
        _set_mode(layerless=False)
    except (urllib.error.URLError, OSError):
        pass


# --------------------------------------------------------------------------------------------------
# T-AC-02 phase 1 : l'attaque réussit SANS la couche. Oracle ISC-TM v1.2.1 §8, vérifié EN BASE.
# --------------------------------------------------------------------------------------------------
def test_tac02_phase1_attaque_reussit_sans_couche():
    # La couche de sécurité est DÉSACTIVÉE (le propriétaire bascule, pas l'outil). Le crochet de
    # démonstration peut forcer le mode protégé pour prouver que la phase 1 échoue alors.
    _set_mode(layerless=_PHASE1_LAYERLESS)
    _seed_victim()

    output = _run_attack()
    did = _decision_id(output)

    # --- POSITIVES : l'attaque a réussi -----------------------------------------------------------
    balance = _sql(f"SELECT balance_xaf FROM core.account WHERE msisdn='{VICTIM}'")
    assert balance == "0", (
        f"[positive] le solde de la victime doit être vidé (0), constaté {balance}"
    )

    outflows = _sql(
        f"SELECT count(*) FROM core.transaction t JOIN core.account a ON a.id=t.account_id "
        f"WHERE a.msisdn='{VICTIM}' AND t.beneficiary_id='{BENEFICIARY}' AND t.state='EXECUTED'"
    )
    assert outflows == "1", (
        f"[positive] exactement UNE sortie exécutée vers l'attaquant, constaté {outflows}"
    )

    otp_target = _sql(
        f"SELECT target_imsi FROM core.otp WHERE msisdn='{VICTIM}' AND consumed_at IS NOT NULL "
        f"ORDER BY expires_at DESC LIMIT 1"
    )
    assert otp_target == ATTACKER_IMSI, (
        f"[positive] l'OTP consommé était parti vers l'IMSI de l'attaquant ({ATTACKER_IMSI}), "
        f"constaté {otp_target}"
    )

    # --- NÉGATIVES : ce qui NE DOIT PAS s'être produit (ISC-TEST §4) -------------------------------
    old_device_notifs = _sql(
        f"SELECT count(*) FROM core.notification WHERE msisdn='{VICTIM}' AND recipient_ref='{VICTIM_IMSI}'"
    )
    assert old_device_notifs == "0", (
        f"[négative] AUCUNE notification sur l'ancien appareil de la victime, constaté {old_device_notifs}"
    )

    # Le vidage a été exécuté SANS que la couche décide : aucun /evaluate, donc aucune entrée d'audit
    # pour ce decisionId. C'est l'expression exacte de « parce qu'il n'y a pas de couche ». Cette
    # assertion est FAUSSE dès que la couche est active (une décision ALLOW y est journalisée) : c'est
    # elle qui fait échouer T-AC-02 phase 1 en mode protégé.
    assert _audit_count(did) == 0, (
        "[négative] AUCUNE décision de la couche n'a été émise pour le vidage — aucune entrée d'audit "
        "(le PDP n'a pas été consulté). Une entrée existe : la couche est active, ce n'est pas la phase 1."
    )

    # AUCUNE décision de gel / step-up / refus, et AUCun état de probation / gel : au mois 2 le PDP ne
    # peut renvoyer qu'ALLOW et les tables trust_state/freeze n'existent pas encore (créées au mois 4).
    # On vérifie l'absence via l'audit du decisionId ; sans couche il n'y a de toute façon aucune entrée.
    defensive = _sql(
        f"SELECT count(*) FROM pdp.audit_entry WHERE payload->>'decisionId'='{did}' "
        f"AND (payload->>'decision' IN ('FREEZE','STEP_UP','REJECTED') OR payload->>'vetoApplied' <> 'NONE')"
    )
    assert defensive == "0", (
        f"[négative] AUCUNE décision de gel, de step-up ou de refus émise ; le compte n'est jamais "
        f"entré en probation ni en gel, constaté {defensive} décision(s) défensive(s)"
    )


# --------------------------------------------------------------------------------------------------
# LE PIÈGE (ISC-TEST §4) : ce test DOIT échouer avec la couche active. On le prouve MAINTENANT — même
# sans règles, le mode protégé laisse une trace (une décision ALLOW journalisée) que l'assertion
# négative centrale de la phase 1 (« aucune décision émise ») interdit. Ce test vérifie exactement
# cette différence de comportement : il restera vrai au mois 4 (le PDP journalisera FREEZE).
# --------------------------------------------------------------------------------------------------
def test_la_couche_active_ferait_echouer_la_phase1():
    _set_mode(layerless=False)  # couche ACTIVE
    _seed_victim()

    output = _run_attack()
    did = _decision_id(output)

    # La couche a été consultée : une décision est journalisée pour le vidage. Donc l'assertion
    # négative « aucune décision émise » de la phase 1 est FAUSSE ici — la phase 1 ne peut pas passer
    # avec la couche active. (Au mois 2 la décision vaut ALLOW et le solde est tout de même vidé ; au
    # mois 4 elle vaudra FREEZE et le solde sera préservé — dans les deux cas, la phase 1 échoue.)
    assert _audit_count(did) >= 1, (
        "en mode protégé, le PDP DOIT être consulté et sa décision journalisée : c'est la trace qui "
        "distingue « avec couche » de « sans couche » et qui fait échouer T-AC-02 phase 1 ici"
    )
    decision = _sql(
        f"SELECT payload->>'decision' FROM pdp.audit_entry WHERE payload->>'decisionId'='{did}'"
    )
    # Les décisions de contracts/evaluate.yaml (le contrat fait foi ; REJECTED existe depuis A4).
    assert decision in ("ALLOW", "STEP_UP", "FREEZE", "REJECTED"), (
        f"la décision journalisée doit être une décision de la couche, constaté {decision!r}"
    )
