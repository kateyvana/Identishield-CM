// Proxy serveur vers le flux du cœur (GET /flux sur isc-net). Le cœur n'est pas
// exposé à l'hôte ; seule la console le lit, côté serveur. Le navigateur interroge
// cette route en temps réel (EF-30).
export const dynamic = 'force-dynamic';

export async function GET() {
  const base = process.env.CORE_URL ?? 'http://core:8080';
  try {
    const res = await fetch(`${base}/flux?limit=50`, { cache: 'no-store' });
    if (!res.ok) {
      return Response.json({ error: 'coeur indisponible' }, { status: 502 });
    }
    return Response.json(await res.json());
  } catch {
    return Response.json({ error: 'coeur injoignable' }, { status: 502 });
  }
}
