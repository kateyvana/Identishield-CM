'use client';

// Vue de démonstration du jury (T2-17). Trois éléments : la CHRONOLOGIE de l'attaque (EF-41), l'ÉTAT
// du HLR du numéro visé (EF-31/EF-32) et — dans le layout, au-dessus — le bandeau de mode (EF-07/
// EF-36). La chronologie vient d'isc-sim (donnée de simulation, JAMAIS du cœur : ADR-034) ; l'OTP y
// est déjà masqué par la route serveur. L'état du HLR est une lecture LÉGITIME du cœur.
//
// BILINGUE anglais/français, ANGLAIS PAR DÉFAUT, sélecteur visible (décision propriétaire 2026-09-24 :
// mémoire et soutenance en anglais ; EF-26/EF-38 à amender au SRS). Chaque étape est publiée en FR ET
// EN par l'outil ; la console choisit selon la langue. Le flux (T2-09) et le bandeau (T2-15) restent
// en français pour l'instant — traduction ultérieure (carte dédiée).
// @implements EF-41
// @implements EF-31
// @implements EF-32
// @implements EF-26
// @implements EF-38

import { useEffect, useRef, useState } from 'react';

type Lang = 'en' | 'fr';

type Step = {
  step: number;
  message: string;
  messageEn?: string;
  ts?: string;
  sensitive?: boolean;
  victim?: string;
  oldImsi?: string;
  newImsi?: string;
};
type Hlr = { msisdn: string; currentImsi: string; updatedAt?: string };
type FluxEntry = {
  decisionId: string;
  msisdn: string;
  type: string;
  amountXaf: number;
  beneficiary: string;
  decision: string;
  score: number;
  state: string;
  createdAt: string;
};

const STR: Record<Lang, Record<string, string>> = {
  en: {
    title: 'Operator console',
    subtitle: 'IdentiShield-CM — AC-02 demonstration: account takeover after SIM swap',
    live: 'live',
    offline: 'network unreachable',
    timelineTitle: 'Attack timeline (AC-02)',
    noAttack: 'No attack in progress.',
    step: 'Step',
    hlrTitle: 'HLR state',
    waiting: 'Waiting for attack data…',
    targetNumber: 'Target number',
    currentImsi: 'Current IMSI',
    originalImsi: 'Original IMSI',
    updated: 'Updated',
    imsiAlert: '⚠ IMSI change detected (step 2) — the SIM was reassigned to another device.',
  },
  fr: {
    title: 'Console opérateur',
    subtitle: 'IdentiShield-CM — démonstration AC-02 : détournement après échange de SIM',
    live: 'en direct',
    offline: 'réseau injoignable',
    timelineTitle: 'Chronologie de l’attaque (AC-02)',
    noAttack: 'Aucune attaque en cours.',
    step: 'Étape',
    hlrTitle: 'État du HLR',
    waiting: 'En attente de données d’attaque…',
    targetNumber: 'Numéro visé',
    currentImsi: 'IMSI courant',
    originalImsi: 'IMSI d’origine',
    updated: 'Mis à jour',
    imsiAlert:
      '⚠ Changement d’IMSI détecté (étape 2) — la SIM a été réattribuée à un autre appareil.',
  },
};

const fcfa = new Intl.NumberFormat('fr-FR');

function heure(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('fr-FR', { hour12: false });
}

export default function Console() {
  const [lang, setLang] = useState<Lang>('en'); // anglais par défaut
  const [steps, setSteps] = useState<Step[]>([]);
  const [hlr, setHlr] = useState<Hlr | null>(null);
  const [flux, setFlux] = useState<FluxEntry[]>([]);
  const [enLigne, setEnLigne] = useState(false);
  const victimRef = useRef<string>('');

  // Préférence de langue par visiteur (confort local ; défaut anglais si absente/illisible).
  useEffect(() => {
    try {
      const saved = localStorage.getItem('isc-lang');
      if (saved === 'fr' || saved === 'en') setLang(saved);
    } catch {
      /* localStorage indisponible : on garde l'anglais */
    }
  }, []);

  function choisirLangue(l: Lang) {
    setLang(l);
    try {
      localStorage.setItem('isc-lang', l);
    } catch {
      /* sans persistance, le choix vaut pour la session */
    }
  }

  useEffect(() => {
    let actif = true;
    async function tick() {
      // 1) Chronologie d'attaque : lue sur isc-sim (jamais le cœur). L'OTP est masqué côté serveur.
      try {
        const r = await fetch('/api/timeline', { cache: 'no-store' });
        const d = await r.json();
        if (actif && Array.isArray(d.steps)) {
          const ord = [...(d.steps as Step[])].sort((a, b) => a.step - b.step);
          setSteps(ord);
          setEnLigne(true);
          const v = ord.find((s) => s.step === 1)?.victim;
          if (v) victimRef.current = v;
        } else if (actif) {
          setEnLigne(false);
        }
      } catch {
        if (actif) setEnLigne(false);
      }
      // 2) État du HLR du numéro visé : lecture légitime au cœur (l'IMSI actif du sujet).
      const victim = victimRef.current;
      if (victim) {
        try {
          const r = await fetch(`/api/hlr?msisdn=${encodeURIComponent(victim)}`, {
            cache: 'no-store',
          });
          if (r.ok && actif) setHlr((await r.json()) as Hlr);
        } catch {
          /* le HLR reste inconnu ce tick */
        }
      }
      // 3) Flux des transactions : le vidage y apparaît (EF-30).
      try {
        const r = await fetch('/api/flux', { cache: 'no-store' });
        const d = await r.json();
        if (actif && Array.isArray(d)) setFlux(d as FluxEntry[]);
      } catch {
        /* flux indisponible ce tick */
      }
    }
    tick();
    const t = setInterval(tick, 1000); // temps réel : < 2 s (EF-30, EF-41)
    return () => {
      actif = false;
      clearInterval(t);
    };
  }, []);

  const t = STR[lang];
  const step2 = steps.find((s) => s.step === 2);
  const originalImsi = step2?.oldImsi;
  const swappedImsi = step2?.newImsi;
  const changed = !!hlr && !!swappedImsi && hlr.currentImsi === swappedImsi;
  const texteEtape = (s: Step) => (lang === 'en' ? (s.messageEn ?? s.message) : s.message);

  return (
    <main>
      <header className="barre">
        <div>
          <h1>{t.title}</h1>
          <p className="sous-titre">{t.subtitle}</p>
        </div>
        <div className="barre-droite">
          <div className="langue" role="group" aria-label="Language / Langue">
            <button
              type="button"
              className={lang === 'en' ? 'actif' : ''}
              aria-pressed={lang === 'en'}
              onClick={() => choisirLangue('en')}
            >
              EN
            </button>
            <button
              type="button"
              className={lang === 'fr' ? 'actif' : ''}
              aria-pressed={lang === 'fr'}
              onClick={() => choisirLangue('fr')}
            >
              FR
            </button>
          </div>
          <span className={enLigne ? 'etat en-ligne' : 'etat hors-ligne'}>
            <span className="point" />
            {enLigne ? t.live : t.offline}
          </span>
        </div>
      </header>

      <div className="grille">
        {/* Chronologie de l'attaque (EF-41) : les huit étapes publiées sur isc-sim, dans l'ordre. */}
        <section className="panneau">
          <h2>{t.timelineTitle}</h2>
          {steps.length === 0 ? (
            <p className="vide">{t.noAttack}</p>
          ) : (
            <ol className="chronologie">
              {steps.map((s) => (
                <li key={s.step} className={s.sensitive ? 'etape sensible' : 'etape'}>
                  <span className="mono heure">{heure(s.ts)}</span>
                  <span className="numero">
                    {t.step} {s.step}
                  </span>
                  <span className="texte">{texteEtape(s)}</span>
                </li>
              ))}
            </ol>
          )}
        </section>

        {/* État du HLR (EF-31/EF-32) : IMSI actif du numéro visé ; changement signalé à l'étape 2. */}
        <section className="panneau">
          <h2>{t.hlrTitle}</h2>
          {!hlr ? (
            <p className="vide">{t.waiting}</p>
          ) : (
            <dl className="hlr">
              <dt>{t.targetNumber}</dt>
              <dd className="mono">{hlr.msisdn}</dd>
              <dt>{t.currentImsi}</dt>
              <dd className={changed ? 'mono imsi-change' : 'mono'}>{hlr.currentImsi}</dd>
              {originalImsi ? (
                <>
                  <dt>{t.originalImsi}</dt>
                  <dd className="mono sourdine">{originalImsi}</dd>
                </>
              ) : null}
              <dt>{t.updated}</dt>
              <dd className="mono">{heure(hlr.updatedAt)}</dd>
            </dl>
          )}
          {changed ? (
            <p className="alerte-change" role="alert">
              {t.imsiAlert}
            </p>
          ) : null}
        </section>
      </div>

      {/* Flux des transactions (EF-30, T2-09) : en français pour l'instant (traduction ultérieure). */}
      <section className="bloc-flux">
        <h2>Transactions en temps réel</h2>
        <table>
          <thead>
            <tr>
              <th>Heure</th>
              <th>MSISDN</th>
              <th>Type</th>
              <th className="num">Montant</th>
              <th>Bénéficiaire</th>
              <th>Décision</th>
              <th className="num">Score</th>
              <th>Identifiant de décision</th>
            </tr>
          </thead>
          <tbody>
            {flux.length === 0 ? (
              <tr>
                <td colSpan={8} className="vide">
                  Aucune transaction pour l’instant.
                </td>
              </tr>
            ) : (
              flux.map((e) => (
                <tr key={e.decisionId}>
                  <td className="mono">{heure(e.createdAt)}</td>
                  <td className="mono">{e.msisdn}</td>
                  <td>{e.type}</td>
                  <td className="num">{fcfa.format(e.amountXaf)} FCFA</td>
                  <td className="mono">{e.beneficiary}</td>
                  <td>
                    <span className={'badge ' + (e.decision === 'ALLOW' ? 'allow' : 'refuse')}>
                      {e.decision === 'ALLOW' ? 'AUTORISÉ' : e.decision}
                    </span>
                  </td>
                  <td className="num">{e.score}</td>
                  <td className="mono id" title={e.decisionId}>
                    {e.decisionId.slice(0, 8)}…
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
    </main>
  );
}
