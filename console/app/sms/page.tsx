'use client';

// Onglet SMS (carte #65) : le dernier SMS simulé reçu par la SIM actuelle d'un numéro, en LECTURE.
// Décisions du superviseur technique du 2026-10-26 (D1 à D4) : le code s'affiche EN CLAIR (c'est le
// but : lire l'OTP du titulaire pendant la démo ; la chronologie d'attaque garde son masquage) ;
// numéro -> IMSI par GET /hlr du cœur, via /api/sms ; chaque ligne montre l'IMSI qui a reçu le SMS
// et l'heure du dernier changement HLR, sans déduction ; ni nom du titulaire, ni historique (isc-sim
// ne garde que le dernier code par IMSI). Rien n'est journalisé. Bilingue EN/FR, comme T2-17.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';

type Lang = 'en' | 'fr';
type Sms = {
  msisdn: string;
  imsi: string;
  hlrUpdatedAt: string | null;
  sms: { code: string; deliveredAt: string | null } | null;
};

const STR: Record<Lang, Record<string, string>> = {
  en: {
    brandTag: 'Policy Decision Point · Mobile Money',
    navAttack: 'Attack',
    navAudit: 'Audit',
    navSms: 'SMS',
    title: 'Simulated SMS by number',
    numberLabel: 'Number',
    show: 'Show',
    hint: 'Last SMS received by the SIM currently attached to this number.',
    cNum: 'Number',
    cImsi: 'IMSI that received it',
    cHlr: 'Last HLR change',
    cCode: 'Code',
    cHeure: 'Delivered at',
    none: 'No SMS received by this SIM.',
    noTime: 'not recorded',
    noChange: 'not provided',
    limits:
      'Limits: the holder name is not shown; the simulated network keeps only the last code per SIM.',
    invalid: 'Enter 9 digits in the fictitious range 677000000–677009999.',
  },
  fr: {
    brandTag: 'Point de décision · Mobile Money',
    navAttack: 'Attaque',
    navAudit: 'Audit',
    navSms: 'SMS',
    title: 'SMS simulés par numéro',
    numberLabel: 'Numéro',
    show: 'Afficher',
    hint: 'Dernier SMS reçu par la SIM actuellement rattachée à ce numéro.',
    cNum: 'Numéro',
    cImsi: 'IMSI qui l’a reçu',
    cHlr: 'Dernier changement HLR',
    cCode: 'Code',
    cHeure: 'Remis à',
    none: 'Aucun SMS reçu par cette SIM.',
    noTime: 'non enregistrée',
    noChange: 'non fournie',
    limits:
      'Limites : le nom du titulaire n’est pas affiché ; le réseau simulé ne garde que le dernier code par SIM.',
    invalid: 'Saisissez 9 chiffres dans la plage fictive 677000000–677009999.',
  },
};

// Plage fictive ED-09 : 677000000 à 677009999.
const MSISDN = /^67700\d{4}$/;

function heure(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('fr-FR', { hour12: false });
}

function Logo() {
  return (
    <svg className="logo" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id="lg3" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop stopColor="#5ab0ff" />
          <stop offset="1" stopColor="#3ad0c8" />
        </linearGradient>
      </defs>
      <path
        d="M16 3 5 7.3v7.6c0 6.6 4.5 11.3 11 14.6 6.5-3.3 11-8 11-14.6V7.3L16 3Z"
        fill="url(#lg3)"
        fillOpacity="0.14"
        stroke="url(#lg3)"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path
        d="M11.4 16.1l3.1 3.1 6-6.2"
        stroke="url(#lg3)"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export default function SmsView() {
  const [lang, setLang] = useState<Lang>('en');
  const [saisie, setSaisie] = useState('');
  const [numero, setNumero] = useState<string | null>(null);
  const [ligne, setLigne] = useState<Sms | null>(null);
  const [erreur, setErreur] = useState<string | null>(null);
  const path = usePathname();

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
    if (!numero) return;
    let actif = true;
    async function tick() {
      try {
        const r = await fetch(`/api/sms?msisdn=${encodeURIComponent(numero ?? '')}`, {
          cache: 'no-store',
        });
        const d = await r.json();
        if (!actif) return;
        if (r.ok) {
          setLigne(d as Sms);
          setErreur(null);
        } else {
          setLigne(null);
          setErreur(typeof d.error === 'string' ? d.error : String(r.status));
        }
      } catch {
        if (actif) setErreur('console : requête impossible');
      }
    }
    tick();
    const t = setInterval(tick, 2000);
    return () => {
      actif = false;
      clearInterval(t);
    };
  }, [numero]);

  const t = STR[lang];
  const valide = MSISDN.test(saisie);

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
            <Link href="/sms" className={path === '/sms' ? 'actif' : ''}>
              {t.navSms}
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
        </div>
      </header>

      <section className="bloc-flux">
        <div className="panneau-tete">
          <h2>{t.title}</h2>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (valide) setNumero(saisie);
          }}
        >
          <label htmlFor="sms-numero">{t.numberLabel}</label>{' '}
          <input
            id="sms-numero"
            inputMode="numeric"
            maxLength={9}
            value={saisie}
            onChange={(e) => setSaisie(e.target.value.replace(/\D/g, ''))}
          />{' '}
          <button type="submit" disabled={!valide}>
            {t.show}
          </button>
        </form>
        {!valide && saisie.length > 0 ? <p className="vide">{t.invalid}</p> : null}
        <p className="note-verif">{t.hint}</p>
        {erreur ? (
          <p className="alerte-change" role="alert">
            {erreur}
          </p>
        ) : null}
        {ligne ? (
          <table>
            <thead>
              <tr>
                <th>{t.cNum}</th>
                <th>{t.cImsi}</th>
                <th>{t.cHlr}</th>
                <th>{t.cCode}</th>
                <th>{t.cHeure}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="mono">{ligne.msisdn}</td>
                <td className="mono">{ligne.imsi}</td>
                <td className="mono">{ligne.hlrUpdatedAt ? heure(ligne.hlrUpdatedAt) : t.noChange}</td>
                {ligne.sms ? (
                  <>
                    <td className="mono">{ligne.sms.code}</td>
                    <td className="mono">{ligne.sms.deliveredAt ? heure(ligne.sms.deliveredAt) : t.noTime}</td>
                  </>
                ) : (
                  <td colSpan={2} className="vide">
                    {t.none}
                  </td>
                )}
              </tr>
            </tbody>
          </table>
        ) : null}
        <p className="note-verif">{t.limits}</p>
      </section>
    </main>
  );
}
