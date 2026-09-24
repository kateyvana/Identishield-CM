'use client';

// Vue de démonstration du jury (T2-17). Trois éléments : la CHRONOLOGIE de l'attaque (EF-41), l'ÉTAT
// du HLR du numéro visé (EF-31/EF-32) et — dans le layout, au-dessus — le bandeau de mode (EF-07/
// EF-36). La chronologie vient d'isc-sim (donnée de simulation, JAMAIS du cœur : ADR-034) ; l'OTP y
// est déjà masqué par la route serveur. L'état du HLR est une lecture LÉGITIME du cœur.
//
// BILINGUE anglais/français, ANGLAIS PAR DÉFAUT, sélecteur visible (décision propriétaire 2026-09-24).
// Le flux (T2-09) et le bandeau (T2-15) restent en français — traduction ultérieure (carte dédiée).
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
    brandTag: 'Policy Decision Point · Mobile Money',
    live: 'live',
    offline: 'network unreachable',
    timelineTitle: 'Attack timeline',
    noAttack: 'No attack in progress.',
    step: 'Step',
    hlrTitle: 'HLR state',
    imsiChanged: 'IMSI changed',
    waiting: 'Waiting for attack data…',
    targetNumber: 'Target number',
    currentImsi: 'Current IMSI',
    originalImsi: 'Original IMSI',
    updated: 'Updated',
    imsiAlert: 'IMSI change detected (step 2) — the SIM was reassigned to another device.',
    contextTitle: 'Scenario',
    contextBody:
      'AC-02 — takeover after SIM swap. A colluding agent reassigns the victim’s SIM, so the one-time code is delivered to the attacker’s device, intercepted, and used to drain the account.',
  },
  fr: {
    brandTag: 'Point de décision · Mobile Money',
    live: 'en direct',
    offline: 'réseau injoignable',
    timelineTitle: 'Chronologie de l’attaque',
    noAttack: 'Aucune attaque en cours.',
    step: 'Étape',
    hlrTitle: 'État du HLR',
    imsiChanged: 'IMSI changé',
    waiting: 'En attente de données d’attaque…',
    targetNumber: 'Numéro visé',
    currentImsi: 'IMSI courant',
    originalImsi: 'IMSI d’origine',
    updated: 'Mis à jour',
    imsiAlert:
      'Changement d’IMSI détecté (étape 2) — la SIM a été réattribuée à un autre appareil.',
    contextTitle: 'Scénario',
    contextBody:
      'AC-02 — détournement après échange de SIM. Un agent complice réattribue la SIM de la victime : le code à usage unique part vers l’appareil de l’attaquant, il est intercepté et sert à vider le compte.',
  },
};

const fcfa = new Intl.NumberFormat('fr-FR');

function heure(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('fr-FR', { hour12: false });
}

function Logo() {
  return (
    <svg className="logo" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id="lg" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop stopColor="#5ab0ff" />
          <stop offset="1" stopColor="#3ad0c8" />
        </linearGradient>
      </defs>
      <path
        d="M16 3 5 7.3v7.6c0 6.6 4.5 11.3 11 14.6 6.5-3.3 11-8 11-14.6V7.3L16 3Z"
        fill="url(#lg)"
        fillOpacity="0.14"
        stroke="url(#lg)"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path
        d="M11.4 16.1l3.1 3.1 6-6.2"
        stroke="url(#lg)"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export default function Console() {
  const [lang, setLang] = useState<Lang>('en'); // anglais par défaut
  const [steps, setSteps] = useState<Step[]>([]);
  const [hlr, setHlr] = useState<Hlr | null>(null);
  const [flux, setFlux] = useState<FluxEntry[]>([]);
  const [enLigne, setEnLigne] = useState(false);
  const victimRef = useRef<string>('');

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
      <header className="topbar">
        <div className="marque">
          <Logo />
          <div>
            <div className="marque-nom">
              Identi<b>Shield</b>-CM
            </div>
            <div className="marque-tag">{t.brandTag}</div>
          </div>
        </div>
        <div className="barre-droite">
          <span className="scenario-chip">AC-02</span>
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
          <div className="panneau-tete">
            <h2>{t.timelineTitle}</h2>
            {steps.length > 0 ? <span className="compteur">{steps.length}/8</span> : null}
          </div>
          {steps.length === 0 ? (
            <p className="vide">{t.noAttack}</p>
          ) : (
            <ol className="chronologie">
              {steps.map((s) => (
                <li key={s.step} className={s.sensitive ? 'etape sensible' : 'etape'}>
                  <span className="noeud">{s.step}</span>
                  <div className="etape-corps">
                    <div className="etape-tete">
                      <span className="numero">
                        {t.step} {s.step}
                      </span>
                      <span className="heure mono">{heure(s.ts)}</span>
                    </div>
                    <div className="texte">{texteEtape(s)}</div>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </section>

        <div className="colonne-droite">
          {/* État du HLR (EF-31/EF-32) : IMSI actif du numéro visé ; changement signalé à l'étape 2. */}
          <section className="panneau carte-hlr">
            <div className="panneau-tete">
              <h2>{t.hlrTitle}</h2>
              {changed ? <span className="badge-change">{t.imsiChanged}</span> : null}
            </div>
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

          {/* Contexte du scénario pour le jury. */}
          <section className="panneau carte-contexte">
            <div className="panneau-tete">
              <h2>{t.contextTitle}</h2>
            </div>
            <p>{t.contextBody}</p>
          </section>
        </div>
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
