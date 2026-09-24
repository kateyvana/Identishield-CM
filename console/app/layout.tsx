import type { ReactNode } from 'react';
import { Sora, IBM_Plex_Mono } from 'next/font/google';
import './globals.css';
import ModeBanner from './ModeBanner';

// Typographie auto-hébergée (téléchargée au build, aucun appel externe au runtime) : Sora pour
// l'interface, IBM Plex Mono pour les données techniques (IMSI, MSISDN, horodatages, codes).
const sora = Sora({
  subsets: ['latin'],
  weight: ['400', '500', '600', '700'],
  variable: '--font-sans',
  display: 'swap',
});
const mono = IBM_Plex_Mono({
  subsets: ['latin'],
  weight: ['400', '500', '600'],
  variable: '--font-mono',
  display: 'swap',
});

export const metadata = {
  title: 'IdentiShield-CM — Console opérateur',
  description:
    'Point de décision Mobile Money — supervision temps réel des décisions et des attaques.',
};

// Le bandeau d'état (EF-07/EF-36) est lu au cœur à CHAQUE requête : rendu dynamique, jamais figé au
// build (le cœur n'est pas joignable au build).
export const dynamic = 'force-dynamic';

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="fr" className={`${sora.variable} ${mono.variable}`}>
      <body>
        {/* Bandeau d'état (EF-07/EF-36), rendu côté serveur au-dessus de toute la console. */}
        <ModeBanner />
        {children}
      </body>
    </html>
  );
}
