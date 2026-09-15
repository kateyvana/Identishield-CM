import type { ReactNode } from 'react';
import './globals.css';
import ModeBanner from './ModeBanner';

export const metadata = {
  title: 'IdentiShield-CM — Console opérateur',
  description: 'Flux des transactions et décisions en temps réel (EF-30).',
};

// Le bandeau d'état (EF-07/EF-36) est lu au cœur à CHAQUE requête : rendu dynamique, jamais figé au
// build (le cœur n'est pas joignable au build).
export const dynamic = 'force-dynamic';

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="fr">
      <body>
        {/* Bandeau d'état (EF-07/EF-36), rendu côté serveur au-dessus de toute la console. */}
        <ModeBanner />
        {children}
      </body>
    </html>
  );
}
