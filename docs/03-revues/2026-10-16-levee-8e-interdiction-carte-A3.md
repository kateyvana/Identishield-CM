# Levée de la 8e interdiction — carte A3, 2026-10-16

Propriétaire : YENGUE MESSAH KATE YVANA
Objet : autorisation de pousser la branche `t4-03-application-decisions`.

## Portée
Poussée de la branche `t4-03-application-decisions` vers `origin`.
Elle porte deux commits de contenu — `c99f50c` (ES-11, fail-closed sur
CAP_FAILCLOSED) et `8195d2a` (ES-06, application des décisions) — plus le
présent commit de levée. Aucune écriture directe sur `main` : la fusion
relève de la propriétaire.

Cette rédaction remplace celle du commit `89d41bf` (même branche, non
poussé), qui limitait par erreur la portée à deux commits.

## Preuves
- La branche porte désormais quatre commits — `c99f50c`, `8195d2a`, `89d41bf`
  et le présent correctif.
- `make check` : VERT (relancé après l'ajout de ce fichier).
- Base réelle (VM) : DecisionEnforcerDbTest 4/4, TrustStateMachineDbTest 27/27,
  TransactionRepositoryDbTest 6/6.
- Revue tech lead ligne à ligne : APPROUVÉE, 0 défaut bloquant.
- Audit pré-poussée : aucun secret en dur (mots de passe générés à l'exécution
  par `openssl rand -hex 16`), MSISDN fictifs seulement, 0 CRLF, aucun binaire,
  aucun fichier du couloir B touché, branche fusionnable sans rebase.

## Réserves consignées (non bloquantes)
1. `init.sql` : `CHECK ((state='DEFERRED') <= (execute_at IS NOT NULL))` est
   correct mais illisible — à réécrire
   `CHECK (state <> 'DEFERRED' OR execute_at IS NOT NULL)`.
2. LIMITE DÉCLARÉE : si le processus s'arrête entre la prise d'une opération
   différée et son commit, elle reste en PENDING_DECISION et n'est plus
   reprise. Balayage de reprise = carte ultérieure.
3. `DecisionEnforcer` : le catch de `Refused` est correct aujourd'hui (motif
   unique dans `frozenByDecision`) mais fragile si un second motif est ajouté.
4. Machine à états documentée (§7.4) mais seules 2 transitions sont gardées.
5. Écart de méthode : pas de phase rouge pour le SQL, aucun test de mutation
   sur cette carte. Accepté et consigné.

## Écarts documentaires à traiter
- SAD §7.4 contredit ADR-016-A (échéance de DEFER) : ADR-016-A prime, ADR à écrire.
- SAD §7.4 : transaction gelée = REJECTED, pas d'état FROZEN de transaction.
- Base de la VM à réinitialiser (colonne `execute_at` absente de la pile en service).
