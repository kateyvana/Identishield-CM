// Proxy serveur vers les notifications émises (GET /notifications/recent sur le cœur, EF-37). Lecture
// seule. Le contenu (body) est servi tel qu'il a été émis, déjà contraint par le garde-fou de T2-14.
export const dynamic = 'force-dynamic';

export async function GET(req: Request) {
  const base = process.env.CORE_URL ?? 'http://core:8080';
  const limit = new URL(req.url).searchParams.get('limit') ?? '50';
  try {
    const res = await fetch(`${base}/notifications/recent?limit=${encodeURIComponent(limit)}`, {
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
