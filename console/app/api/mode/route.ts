// Proxy serveur vers le mode du cœur (GET/POST /mode sur isc-net). EF-07/EF-36 : la console lit et
// bascule le mode sans couche + lit l'état du PDP. Le cœur n'est pas exposé à l'hôte ; seule la
// console le lit, côté serveur.
export const dynamic = 'force-dynamic';

const base = (): string => process.env.CORE_URL ?? 'http://core:8080';

export async function GET() {
  try {
    const res = await fetch(`${base()}/mode`, { cache: 'no-store' });
    if (!res.ok) {
      return Response.json({ error: 'coeur indisponible' }, { status: 502 });
    }
    return Response.json(await res.json());
  } catch {
    return Response.json({ error: 'coeur injoignable' }, { status: 502 });
  }
}

export async function POST(req: Request) {
  try {
    const body = await req.text();
    const res = await fetch(`${base()}/mode`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      cache: 'no-store',
    });
    if (!res.ok) {
      return Response.json({ error: 'coeur indisponible' }, { status: 502 });
    }
    return Response.json(await res.json());
  } catch {
    return Response.json({ error: 'coeur injoignable' }, { status: 502 });
  }
}
