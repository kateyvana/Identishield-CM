# Maquettes de l'application mobile — référence visuelle

**Source :** `IdentiShield-CM_Maquettes_ecrans_et_identite_v1.0.zip` (dossier de design du propriétaire, 25/09/2026). Les PNG sont déposés tels quels, sous leur nom d'origine. Le dossier complet (33 diapositives : identité, 27 écrans, flux A/B/C/D/F, cas d'échec, composants) est `../IdentiShield-CM_Design_Application_Mobile_v1.0.pptx` (livrable L-8 d'ISC-MOB-CDC).
**Documents de conception :** ISC-MOB-CDC v1.2, ISC-MOB-SPEC v1.2 (§11 inventaire des écrans, §19 traçabilité), diagrammes `../diagrams/`.

## Règle

Ces maquettes sont la **référence visuelle** des cartes M-06 à M-08. Tout écart entre l'application et une maquette est **documenté** (dans le rapport de la carte, avec le motif), **jamais silencieux**.

Les numéros (plage 677000000–677009999) et le nom « Amina NDONGO ÉLOMO » visibles sur les écrans sont **fictifs** (ADR-035).

## Écrans

Colonnes « Flux » et « Exigences » : reprises d'ISC-MOB-SPEC v1.2 §11 (inventaire) et §19 (traçabilité). Quand le §19 regroupe plusieurs écrans sous plusieurs exigences, le groupe est repris tel quel. MOB-F-17 (interface en français et en anglais) couvre tous les écrans. 🔒 = capture d'écran bloquée (MOB-S-05).

| Écran (SPEC §11) | Fichier | Flux | Exigences MOB-F |
|---|---|---|---|
| E-01 Lancement | `E01.png` | A, B | MOB-F-01 |
| E-02 Bienvenue | `E02.png` | A | MOB-F-16 |
| E-03 Numéro | `E03.png` | A, D | MOB-F-02, MOB-F-18 |
| E-04 Attente (maquette : « Vérification ») | `E04.png` | A, B, D | MOB-F-02 |
| E-05 OTP (maquette : « Code SMS ») | `E05.png` | A | MOB-F-03 |
| E-06 PIN 🔒 | `E06.png` | A, D | MOB-F-02 |
| E-07 Sécurisation | `E07.png` | A, D | MOB-F-03 |
| E-08 Clé de récupération 🔒 | `E08.png` | A, D | MOB-F-04, MOB-F-05 ; MOB-F-11 (flux D) |
| E-09 Confirmation 🔒 | `E09.png` | A, D | MOB-F-04, MOB-F-05 |
| E-10 Déverrouillage | `E10.png` | B | MOB-F-06 |
| E-11 Accueil | `E11.png` | B | MOB-F-14 |
| E-12 Bénéficiaire | `E12.png` | C | MOB-F-07 à MOB-F-09 (groupe E-12 à E-18) |
| E-13 Montant | `E13.png` | C | MOB-F-07 à MOB-F-09 |
| E-14 Confirmation 🔒 | `E14.png` | C | MOB-F-07 à MOB-F-09 |
| E-15 Autorisation 🔒 | `E15.png` | C | MOB-F-07 à MOB-F-09 |
| E-16 Succès (maquette : « Envoyé ») | `E16.png` | C | MOB-F-07 à MOB-F-09 |
| E-17 Exécution différée | `E17.png` | C | MOB-F-07 à MOB-F-09 |
| E-18 Bloquée / Rejetée | `E18.png` | C | MOB-F-07 à MOB-F-09 |
| E-19 Historique | `E19.png` | B | MOB-F-14 |
| E-20 Clé de récupération 🔒 (saisie) | `E20.png` | D | MOB-F-10, MOB-F-11 (groupe E-20 à E-22) |
| E-21 Attente imposée | `E21.png` | D | MOB-F-10, MOB-F-11 |
| E-22 Réenrôlement réussi (maquette : « Téléphone lié ») | `E22.png` | D | MOB-F-10, MOB-F-11 |
| E-23 Alertes | `E23.png` | F | MOB-F-12, MOB-F-13 |
| E-24 Détail d'alerte | `E24.png` | F | MOB-F-12, MOB-F-13 |
| E-25 Sécurité | `E25.png` | autres cas (SPEC §10) | MOB-F-15, MOB-F-16, MOB-F-19 |
| E-26 Clé perdue | `E26.png` | D | — (non tracé en §19 ; en attente de QM-05) |
| E-27 Erreurs — clé révoquée | `E27a.png` | B (SPEC §6) | — (non tracé en §19 ; notification informative, EF-47) |
| E-27 Erreurs — appareil incompatible | `E27b.png` | A (SPEC §5.3) | — (non tracé en §19 ; MOB-S-02, MOB-NF-01 : Android 11 minimum) |

L'écran E-27 est dessiné en deux variantes : `E27a` (clé révoquée) et `E27b` (appareil incompatible). D'où 28 PNG pour 27 écrans.

## Identité visuelle

| Fichier | Contenu |
|---|---|
| `logo_mark.png`, `logo_lockup.png`, `logo_dark.png` | Logo : symbole seul, symbole et nom, version sur fond sombre |
| `app_icon.png` | Icône Android |
| `palette.png` | Palette |
| `typo.png` | Typographie |
| `components.png` | Composants et états |
| `map.png` | Carte des 27 écrans, en six groupes |

**Palette**

| Rôle | Couleur |
|---|---|
| Marine (dominante) | `#10375C` |
| Vert « protégé » (état vérifié par le serveur) | `#1B998B` |
| Ambre (alerte) | `#F2A93B` |
| Rouge (danger) | `#D7263D` |
| Fond | `#F4F7FB` |
| Texte | `#14213D` |

**Police :** Roboto, la police système d'Android.
