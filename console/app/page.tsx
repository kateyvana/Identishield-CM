'use client';

import { useEffect, useState } from 'react';

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

function heure(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString('fr-FR', { hour12: false });
}

export default function Console() {
  const [entries, setEntries] = useState<FluxEntry[]>([]);
  const [enLigne, setEnLigne] = useState(false);

  useEffect(() => {
    let actif = true;
    async function charger() {
      try {
        const res = await fetch('/api/flux', { cache: 'no-store' });
        const data = await res.json();
        if (actif && Array.isArray(data)) {
          setEntries(data);
          setEnLigne(true);
        } else if (actif) {
          setEnLigne(false);
        }
      } catch {
        if (actif) setEnLigne(false);
      }
    }
    charger();
    const t = setInterval(charger, 1000); // temps reel : < 2 s (EF-30)
    return () => {
      actif = false;
      clearInterval(t);
    };
  }, []);

  return (
    <main>
      <header className="barre">
        <div>
          <h1>Console opérateur</h1>
          <p className="sous-titre">IdentiShield-CM — flux des transactions en temps réel</p>
        </div>
        <span className={enLigne ? 'etat en-ligne' : 'etat hors-ligne'}>
          <span className="point" />
          {enLigne ? 'en direct' : 'cœur injoignable'}
        </span>
      </header>

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
          {entries.length === 0 ? (
            <tr>
              <td colSpan={8} className="vide">
                Aucune transaction pour l’instant.
              </td>
            </tr>
          ) : (
            entries.map((e) => (
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
    </main>
  );
}
