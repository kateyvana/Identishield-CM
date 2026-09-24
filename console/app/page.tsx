'use client';

// Vue de démonstration du jury (T2-17). Trois éléments : la CHRONOLOGIE de l'attaque (EF-41), l'ÉTAT
// du HLR du numéro visé (EF-31/EF-32) et — dans le layout, au-dessus — le bandeau de mode (EF-07/
// EF-36). La chronologie vient d'isc-sim (donnée de simulation, JAMAIS du cœur : ADR-034) ; l'OTP y
// est déjà masqué par la route serveur. L'état du HLR est une lecture LÉGITIME du cœur.
// @implements EF-41
// @implements EF-31
// @implements EF-32

import { useEffect, useRef, useState } from 'react';

type Step = {
  step: number;
  message: string;
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

const fcfa = new Intl.NumberFormat('fr-FR');

function heure(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('fr-FR', { hour12: false });
}

export default function Console() {
  const [steps, setSteps] = useState<Step[]>([]);
  const [hlr, setHlr] = useState<Hlr | null>(null);
  const [flux, setFlux] = useState<FluxEntry[]>([]);
  const [enLigne, setEnLigne] = useState(false);
  const victimRef = useRef<string>('');

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

  const step2 = steps.find((s) => s.step === 2);
  const originalImsi = step2?.oldImsi;
  const swappedImsi = step2?.newImsi;
  const changed = !!hlr && !!swappedImsi && hlr.currentImsi === swappedImsi;

  return (
    <main>
      <header className="barre">
        <div>
          <h1>Console opérateur</h1>
          <p className="sous-titre">
            IdentiShield-CM — démonstration AC-02 : détournement après échange de SIM
          </p>
        </div>
        <span className={enLigne ? 'etat en-ligne' : 'etat hors-ligne'}>
          <span className="point" />
          {enLigne ? 'en direct' : 'réseau injoignable'}
        </span>
      </header>

      <div className="grille">
        {/* Chronologie de l'attaque (EF-41) : les huit étapes publiées sur isc-sim, dans l'ordre. */}
        <section className="panneau">
          <h2>Chronologie de l’attaque (AC-02)</h2>
          {steps.length === 0 ? (
            <p className="vide">Aucune attaque en cours.</p>
          ) : (
            <ol className="chronologie">
              {steps.map((s) => (
                <li key={s.step} className={s.sensitive ? 'etape sensible' : 'etape'}>
                  <span className="mono heure">{heure(s.ts)}</span>
                  <span className="numero">Étape {s.step}</span>
                  <span className="texte">{s.message}</span>
                </li>
              ))}
            </ol>
          )}
        </section>

        {/* État du HLR (EF-31/EF-32) : IMSI actif du numéro visé ; changement signalé à l'étape 2. */}
        <section className="panneau">
          <h2>État du HLR</h2>
          {!hlr ? (
            <p className="vide">En attente de données d’attaque…</p>
          ) : (
            <dl className="hlr">
              <dt>Numéro visé</dt>
              <dd className="mono">{hlr.msisdn}</dd>
              <dt>IMSI courant</dt>
              <dd className={changed ? 'mono imsi-change' : 'mono'}>{hlr.currentImsi}</dd>
              {originalImsi ? (
                <>
                  <dt>IMSI d’origine</dt>
                  <dd className="mono sourdine">{originalImsi}</dd>
                </>
              ) : null}
              <dt>Mis à jour</dt>
              <dd className="mono">{heure(hlr.updatedAt)}</dd>
            </dl>
          )}
          {changed ? (
            <p className="alerte-change" role="alert">
              ⚠ Changement d’IMSI détecté (étape 2) — la SIM a été réattribuée à un autre appareil.
            </p>
          ) : null}
        </section>
      </div>

      {/* Flux des transactions (EF-30) : le vidage de la victime apparaît ici en temps réel. */}
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
