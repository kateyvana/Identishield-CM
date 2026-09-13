import type { ReactNode } from 'react';
import './globals.css';

export const metadata = {
  title: 'IdentiShield-CM — Console opérateur',
  description: 'Flux des transactions et décisions en temps réel (EF-30).',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="fr">
      <body>{children}</body>
    </html>
  );
}
