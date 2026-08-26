import type { ReactNode } from 'react';

export const metadata = {
  title: 'IdentiShield-CM — Console',
  description: 'Console opérateur et SOC (squelette, TACHE-00)',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="fr">
      <body>{children}</body>
    </html>
  );
}
