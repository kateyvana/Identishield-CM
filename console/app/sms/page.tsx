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
    INVALID_MSISDN:
      'This number is outside the fictitious range: enter 9 digits from 677000000 to 677009999.',
    UNKNOWN_MSISDN: 'This number is not in the simulated HLR: check the demo account number.',
    HLR_UNAVAILABLE:
      'The simulated core did not return the HLR state: check that the stack is running, then try again.',
    CORE_UNREACHABLE:
      'The simulated core is not answering: check that the stack is running, then try again.',
    SIM_UNAVAILABLE:
      'The simulated network (isc-sim) returned an error: check the stack, then try again.',
    SIM_UNREACHABLE:
      'The simulated network (isc-sim) is not answering: check the stack, then try again.',
    FETCH_FAILED: 'The console could not reach its own server: reload the page.',
    UNKNOWN_ERROR: 'Unexpected error: reload the page; if it persists, check the console logs.',
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
    INVALID_MSISDN:
      'Ce numéro est hors de la plage fictive : saisissez 9 chiffres de 677000000 à 677009999.',
    UNKNOWN_MSISDN:
      'Ce numéro n’existe pas dans le HLR simulé : vérifiez le numéro du compte de démonstration.',
    HLR_UNAVAILABLE:
      'Le cœur simulé n’a pas rendu l’état HLR : vérifiez que la pile est démarrée, puis réessayez.',
    CORE_UNREACHABLE:
      'Le cœur simulé ne répond pas : vérifiez que la pile est démarrée, puis réessayez.',
    SIM_UNAVAILABLE:
      'Le réseau simulé (isc-sim) a renvoyé une erreur : vérifiez la pile, puis réessayez.',
    SIM_UNREACHABLE: 'Le réseau simulé (isc-sim) ne répond pas : vérifiez la pile, puis réessayez.',
    FETCH_FAILED: 'La console n’a pas pu joindre son propre serveur : rechargez la page.',
    UNKNOWN_ERROR:
      'Erreur inattendue : rechargez la page ; si elle persiste, consultez les journaux de la console.',
  },
};

// Plage fictive ED-09 : 677000000 à 677009999.
const MSISDN = /^67700\d{4}$/;

// Heure locale du navigateur, fuseau affiché (les sources sont en UTC).
function heure(iso: string, lang: Lang): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleString(lang === 'fr' ? 'fr-FR' : 'en-GB', { hour12: false, timeZoneName: 'short' });
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
          setErreur(typeof d.error === 'string' ? d.error : 'UNKNOWN_ERROR');
        }
      } catch {
        if (actif) setErreur('FETCH_FAILED');
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
          className="form-sms"
          onSubmit={(e) => {
            e.preventDefault();
            if (valide) setNumero(saisie);
          }}
        >
          <label htmlFor="sms-numero">{t.numberLabel}</label>{' '}
          <input
            id="sms-numero"
            autoComplete="off"
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
            {t[erreur] ?? t.UNKNOWN_ERROR}
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
                <td className="mono">
                  {ligne.hlrUpdatedAt ? heure(ligne.hlrUpdatedAt, lang) : t.noChange}
                </td>
                {ligne.sms ? (
                  <>
                    <td className="mono">{ligne.sms.code}</td>
                    <td className="mono">
                      {ligne.sms.deliveredAt ? heure(ligne.sms.deliveredAt, lang) : t.noTime}
                    </td>
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
