// Proxy serveur vers la LECTURE d'état d'abonné du HLR (GET /hlr?msisdn= sur le cœur). C'est une
// donnée LÉGITIME (l'IMSI actif du numéro visé), pas de l'infrastructure d'attaque : la chronologie,
// elle, ne passe jamais par le cœur (ADR-034) et se lit sur isc-sim. Le cœur n'est pas exposé à
// l'hôte ; seule la console le lit, côté serveur.
export const dynamic = 'force-dynamic';

export async function GET(req: Request) {
  const base = process.env.CORE_URL ?? 'http://core:8080';
  const msisdn = new URL(req.url).searchParams.get('msisdn') ?? '';
  if (!msisdn) {
    return Response.json({ error: 'msisdn requis' }, { status: 400 });
  }
  try {
    const res = await fetch(`${base}/hlr?msisdn=${encodeURIComponent(msisdn)}`, {
      cache: 'no-store',
    });
    if (!res.ok) {
      return Response.json({ error: 'etat HLR indisponible' }, { status: res.status });
    }
    return Response.json(await res.json());
  } catch {
    return Response.json({ error: 'coeur injoignable' }, { status: 502 });
  }
}
