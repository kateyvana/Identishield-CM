# Scénario de démonstration de B3b — flux A puis flux D sur le même compte

Date : 2026-10-24. Couloir B (Mobile). Décision de la propriétaire et du superviseur technique du
2026-10-24 : **aucun amorçage d'un compte « déjà lié » au PDP** (le PDP ne connaît pas l'UUID du compte
créé par le cœur et aucune route ne pose une clé ACTIVE sans attestation ; en créer une serait un
changement de conception). La démonstration enchaîne donc le premier enrôlement (flux A) puis le
réenrôlement (flux D) du **même** compte, sur un second téléphone ou après réinstallation.

## Comptes de démonstration utilisés

| Rôle | Compte | Source | Pourquoi |
|---|---|---|---|
| Titulaire enrôlé puis réenrôlé | ~~**677000200 — Hervé ESSOMBA**~~ (joué le 2026-10-25 en passe B7 ; **reste gelé** FROZEN / NOT_ME : preuve de B7, levée ES-08 non construite = limite déclarée, mois 5). **Décision du superviseur du 2026-10-25 : répétition générale ≈ 3/11 sur 677001102, démonstration du 6/11 sur 677001103** (soldes amorcés par le CSV B8, **PIN tapés par la propriétaire**) ; avant chaque passage, **désinstaller l'application** puis flux A sur le nouveau numéro | CSV B8 (hors dépôt) | Compte neuf : aucun appareil, c'est le flux A nominal ; après le flux A il est lié, c'est le flux D nominal |
| Compte visé par un tiers (freinage) | **677001101** (liste nominative de la propriétaire, hors dépôt) | `seed-comptes-demo.csv` (PIN connu de la propriétaire seule) | Montre qu'un tiers qui tape des codes faux bloque SON origine, jamais le compte (ES-21) |

Préalable : pile démarrée sous le profil `demo` (`make up`, `ISC_DEMO_SEED_CSV` dans `infra/.env`),
téléphone sur le tailnet, APK pointant sur `https://isc-core:8443/mobile/v1` (L7).

## Acte 1 — flux A : premier enrôlement de 677000200 (téléphone 1)

1. Numéro, PIN → le serveur répond **OTP** (aucune clé enregistrée) ; l'OTP est lu dans la console
   (onglet SMS simulés) et saisi.
2. Clé née dans la puce avec le défi d'attestation ; `/enrolment/complete` → clé **EN ATTENTE**, code
   de récupération affiché une fois (E-08) ; l'usager le note sur papier.
3. `/recovery-code/confirm` (code retapé en entier) → clé **ACTIVE**, session ouverte, accueil.

Constat attendu dans la base PDP : une clé ACTIVE pour le compte.

## Acte 2 — flux D : réenrôlement du même compte (téléphone 2, ou téléphone 1 réinstallé)

1. Numéro, PIN → le serveur répond **RECOVERY** avec un `enrolmentId` (clé ACTIVE existante, pas de
   signature valide) ; **aucun OTP** (EF-46) : la SIM ne prouve rien.
2. L'usager tape son code de récupération (E-20) ; `/enrolment/recover` émet le défi d'attestation et ne
   vérifie rien (ADR-041).
3. Nouvelle clé née dans la puce ; `/enrolment/complete` part avec l'attestation ET le code ; le PDP
   vérifie les deux en une fois (`/device/register`) → nouvelle clé **EN ATTENTE**, nouveau code affiché
   une fois ; l'ancienne clé **reste ACTIVE**.
4. `/recovery-code/confirm` (nouveau code) → dans la **même transaction** : ancienne clé **RÉVOQUÉE**,
   nouvelle **ACTIVE** (ES-14b, ES-20). Accueil.
5. Sur le téléphone 1 : `/session` répond **403 KEY_REVOKED** ; l'ancien code ne vaut plus rien.

Constat attendu dans la base PDP : exactement une clé ACTIVE (la nouvelle), l'ancienne REVOKED avec
`revoked_at`.

## Acte 3 — le tiers et le freinage (ES-21, ES-22), compte 677001101

Depuis un troisième appareil (ou le faux attaquant), numéro 677001101 et PIN inconnu : **401
CREDENTIALS_INVALID** identique pour un PIN faux et pour un compte inexistant. Avec le PIN juste mais un
code de récupération faux à `/enrolment/complete` : **401 RECOVERY_INVALID**, même réponse qu'une
attestation refusée ; après `REENROL_ATTEMPTS` échecs, **429 THROTTLED + Retry-After** (LOCK_BASE,
doublé) sur l'origine et l'installation du tiers. Pendant ce temps, le titulaire de 677001101 ouvre une
session depuis son téléphone lié : **jamais bloqué**. Constat en base PDP : `pdp.enrolment_attempt`
porte l'origine du tiers (et son `X-ISC-Installation`), le compte est compté, jamais verrouillé.

## Ce que le scénario ne montre pas (déclaré)

- L'alerte ES-24/ES-27 au téléphone lié pendant les tentatives du tiers : carte B5 (#48).
- Le gel des réenrôlements après « ce n'est pas moi » (`REENROL_FREEZE`) : carte B5.
- Les actes 1 et 2 exigent une vraie attestation matérielle : étape du vrai téléphone (après L7-b).
