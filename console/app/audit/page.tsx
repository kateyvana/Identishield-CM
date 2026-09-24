'use client';

// Deux vues de LECTURE (T2-19) : l'intégrité du journal d'audit (EF-34) et les notifications émises
// (EF-37). La console AFFICHE ; elle ne vérifie pas et ne modifie rien. Le verdict d'intégrité est
// celui de la vérification RÉELLE de T2-06 (fonctions pdp.audit_verify / audit_anchor_check), obtenu
// via /api/integrity ; la console ne réimplémente aucune logique de vérification, et n'expose AUCUNE
// route d'écriture vers le journal. Bilingue EN/FR (anglais par défaut), comme T2-17.
// @implements EF-34
// @implements EF-37

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';

type Lang = 'en' | 'fr';
type Integrity = {
  intact: boolean;
  entryCount: number;
  lastSeq: number;
  anchorMatches: boolean;
  firstBrokenIndex: number | null;
  brokenIndexes: number[];
};
type Notif = {
  msisdn: string;
  recipientRef: string;
  channel: string;
  type: string;
  body: string;
  sentAt: string;
};

const STR: Record<Lang, Record<string, string>> = {
  en: {
    brandTag: 'Policy Decision Point · Mobile Money',
    navAttack: 'Attack',
    navAudit: 'Audit',
    live: 'live',
    offline: 'core unreachable',
    integrityTitle: 'Audit log integrity',
    verdictIntact: 'CHAIN INTACT',
    verdictBroken: 'BREAK DETECTED',
    atEntry: 'at entry #',
    alsoAt: 'other altered entries:',
    entries: 'Entries',
    lastSeq: 'Last sequence',
    anchor: 'Anchor (chain head)',
    anchorOk: 'consistent',
    anchorBad: 'MISMATCH',
    verifiedBy:
      'Verdict from the real audit-chain verification (T2-06). The console displays it — it does not re-verify, and exposes no way to modify the log.',
    notifTitle: 'Emitted notifications',
    cHeure: 'Time',
    cNum: 'Number',
    cCanal: 'Channel',
    cType: 'Type',
    cContenu: 'Content',
    noNotif: 'No notification emitted yet.',
    waiting: 'Waiting for the core…',
  },
  fr: {
    brandTag: 'Point de décision · Mobile Money',
    navAttack: 'Attaque',
    navAudit: 'Audit',
    live: 'en direct',
    offline: 'cœur injoignable',
    integrityTitle: 'Intégrité du journal d’audit',
    verdictIntact: 'CHAÎNE INTÈGRE',
    verdictBroken: 'RUPTURE DÉTECTÉE',
    atEntry: 'à l’entrée n°',
    alsoAt: 'autres entrées altérées :',
    entries: 'Entrées',
    lastSeq: 'Dernière séquence',
    anchor: 'Ancre (chain_head)',
    anchorOk: 'cohérente',
    anchorBad: 'INCOHÉRENTE',
    verifiedBy:
      'Verdict de la vérification réelle de la chaîne d’audit (T2-06). La console l’AFFICHE — elle ne re-vérifie pas, et n’expose aucun moyen de modifier le journal.',
    notifTitle: 'Notifications émises',
    cHeure: 'Heure',
    cNum: 'Numéro',
    cCanal: 'Canal',
    cType: 'Type',
    cContenu: 'Contenu',
    noNotif: 'Aucune notification émise.',
    waiting: 'En attente du cœur…',
  },
};

function heure(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('fr-FR', { hour12: false });
}

function Logo() {
  return (
    <svg className="logo" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id="lg2" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop stopColor="#5ab0ff" />
          <stop offset="1" stopColor="#3ad0c8" />
        </linearGradient>
      </defs>
      <path
        d="M16 3 5 7.3v7.6c0 6.6 4.5 11.3 11 14.6 6.5-3.3 11-8 11-14.6V7.3L16 3Z"
        fill="url(#lg2)"
        fillOpacity="0.14"
        stroke="url(#lg2)"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path
        d="M11.4 16.1l3.1 3.1 6-6.2"
        stroke="url(#lg2)"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export default function AuditView() {
  const [lang, setLang] = useState<Lang>('en');
  const [integrity, setIntegrity] = useState<Integrity | null>(null);
  const [notifs, setNotifs] = useState<Notif[]>([]);
  const [enLigne, setEnLigne] = useState(false);
  const path = usePathname();
  const started = useRef(false);

  useEffect(() => {
    try {
      const saved = localStorage.getItem('isc-lang');
      if (saved === 'fr' || saved === 'en') setLang(saved);
    } catch {
      /* défaut anglais */
    }
  }, []);

  function choisirLangue(l: Lang) {
    setLang(l);
    try {
      localStorage.setItem('isc-lang', l);
    } catch {
      /* session seulement */
    }
  }

  useEffect(() => {
    let actif = true;
    async function tick() {
      try {
        const r = await fetch('/api/integrity', { cache: 'no-store' });
        const d = await r.json();
        if (actif && typeof d.intact === 'boolean') {
          setIntegrity(d as Integrity);
          setEnLigne(true);
          started.current = true;
        } else if (actif) {
          setEnLigne(false);
        }
      } catch {
        if (actif) setEnLigne(false);
      }
      try {
        const r = await fetch('/api/notifications?limit=50', { cache: 'no-store' });
        const d = await r.json();
        if (actif && Array.isArray(d)) setNotifs(d as Notif[]);
      } catch {
        /* notifications indisponibles ce tick */
      }
    }
    tick();
    const t = setInterval(tick, 1000);
    return () => {
      actif = false;
      clearInterval(t);
    };
  }, []);

  const t = STR[lang];

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
          <nav className="nav">
            <Link href="/" className={path === '/' ? 'actif' : ''}>
              {t.navAttack}
            </Link>
            <Link href="/audit" className={path === '/audit' ? 'actif' : ''}>
              {t.navAudit}
            </Link>
          </nav>
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

      {/* Vue d'intégrité du journal (EF-34) : verdict de la vérification RÉELLE de T2-06. */}
      <section className="panneau">
        <div className="panneau-tete">
          <h2>{t.integrityTitle}</h2>
        </div>
        {!integrity ? (
          <p className="vide">{t.waiting}</p>
        ) : (
          <>
            <div className={integrity.intact ? 'verdict verdict-ok' : 'verdict verdict-rompu'}>
              <span className="verdict-icone">{integrity.intact ? '🛡' : '⚠'}</span>
              <span className="verdict-texte">
                {integrity.intact ? t.verdictIntact : t.verdictBroken}
                {!integrity.intact && integrity.firstBrokenIndex !== null ? (
                  <span className="verdict-pos">
                    {' '}
                    {t.atEntry}
                    {integrity.firstBrokenIndex}
                  </span>
                ) : null}
              </span>
            </div>
            {!integrity.intact && integrity.brokenIndexes.length > 1 ? (
              <p className="alerte-change" role="alert">
                {t.alsoAt} <span className="mono">{integrity.brokenIndexes.join(', ')}</span>
              </p>
            ) : null}
            <dl className="hlr integrite-details">
              <dt>{t.entries}</dt>
              <dd className="mono">{integrity.entryCount}</dd>
              <dt>{t.lastSeq}</dt>
              <dd className="mono">{integrity.lastSeq}</dd>
              <dt>{t.anchor}</dt>
              <dd className={integrity.anchorMatches ? 'mono' : 'mono imsi-change'}>
                {integrity.anchorMatches ? t.anchorOk : t.anchorBad}
              </dd>
            </dl>
            <p className="note-verif">{t.verifiedBy}</p>
          </>
        )}
      </section>

      {/* Vue des notifications émises (EF-37) : contenu contraint par le garde-fou de T2-14. */}
      <section className="bloc-flux">
        <div className="panneau-tete">
          <h2>{t.notifTitle}</h2>
        </div>
        <table>
          <thead>
            <tr>
              <th>{t.cHeure}</th>
              <th>{t.cNum}</th>
              <th>{t.cCanal}</th>
              <th>{t.cType}</th>
              <th>{t.cContenu}</th>
            </tr>
          </thead>
          <tbody>
            {notifs.length === 0 ? (
              <tr>
                <td colSpan={5} className="vide">
                  {t.noNotif}
                </td>
              </tr>
            ) : (
              notifs.map((n, i) => (
                <tr key={`${n.sentAt}-${i}`}>
                  <td className="mono">{heure(n.sentAt)}</td>
                  <td className="mono">{n.msisdn}</td>
                  <td>
                    <span className="canal">{n.channel}</span>
                  </td>
                  <td>{n.type}</td>
                  <td>{n.body}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
    </main>
  );
}
