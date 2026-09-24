// Proxy serveur vers l'état d'intégrité du journal d'audit (GET /audit/integrity sur le cœur, EF-34).
// LECTURE SEULE : la console AFFICHE la vérification RÉELLE de T2-06 renvoyée par le cœur, elle ne la
// réécrit pas. Aucune route d'écriture/altération du journal n'existe côté console (le journal est
// inaltérable ; l'altération de démonstration passe par le rôle administrateur, jamais par ici).
export const dynamic = 'force-dynamic';

export async function GET() {
  const base = process.env.CORE_URL ?? 'http://core:8080';
  try {
    const res = await fetch(`${base}/audit/integrity`, { cache: 'no-store' });
    if (!res.ok) {
      return Response.json({ error: 'coeur indisponible' }, { status: 502 });
    }
    return Response.json(await res.json());
  } catch {
    return Response.json({ error: 'coeur injoignable' }, { status: 502 });
  }
}
