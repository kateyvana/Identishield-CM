// Proxy serveur vers la CHRONOLOGIE d'attaque, servie par isc-sim (T2-17, EF-41). La chronologie est
// une donnée de SIMULATION : elle vit sur isc-sim, JAMAIS dans le cœur (ADR-034). Le navigateur ne
// joint pas isc-sim (réseau interne) ; il passe par cette route. C'est ICI, côté serveur, qu'on
// MASQUE l'OTP intercepté (étape marquée `sensitive`) : la console est exposée au réseau local, elle
// ne doit pas devenir une surface de lecture de codes, même factices. Le code en clair ne quitte
// donc jamais le serveur de la console.
export const dynamic = 'force-dynamic';

type Step = {
  step: number;
  message: string;
  messageEn?: string;
  ts?: string;
  sensitive?: boolean;
  [k: string]: unknown;
};

// Masque le dernier groupe de chiffres d'un message : deux premiers et le dernier visibles, le reste
// remplacé par des points. « 769910 » -> « 76•••0 ». Le jury comprend l'interception sans voir le code.
function maskCode(message: string): string {
  return message.replace(/\d{3,}(?=\D*$)/, (d) =>
    d.length <= 3 ? d : d.slice(0, 2) + '•'.repeat(d.length - 3) + d.slice(-1),
  );
}

export async function GET() {
  const base = process.env.SIM_URL ?? 'http://sim:8080';
  try {
    const res = await fetch(`${base}/timeline`, { cache: 'no-store' });
    if (!res.ok) {
      return Response.json({ error: 'reseau simule indisponible' }, { status: 502 });
    }
    const data = (await res.json()) as { steps?: Step[] };
    // On masque l'OTP dans LES DEUX langues : le code clair ne quitte jamais le serveur de la console.
    const steps = (data.steps ?? []).map((s) =>
      s.sensitive
        ? {
            ...s,
            message: maskCode(s.message),
            messageEn: s.messageEn ? maskCode(s.messageEn) : s.messageEn,
          }
        : s,
    );
    return Response.json({ steps });
  } catch {
    return Response.json({ error: 'reseau simule injoignable' }, { status: 502 });
  }
}
