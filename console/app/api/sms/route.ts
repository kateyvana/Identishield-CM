// Onglet SMS (carte #65) : le dernier SMS simulé reçu par la SIM ACTUELLE d'un numéro, en LECTURE.
// Décisions du superviseur technique du 2026-10-26 :
//  - D2 : numéro -> IMSI par la route existante GET /hlr du cœur ; le code est lu sur isc-sim
//    (GET /inbox?imsi=). La console ne lit AUCUNE base.
//  - D3 : la réponse donne l'IMSI qui a reçu le SMS et l'heure du dernier changement HLR du numéro
//    (updatedAt), telles que les sources les rendent ; aucune déduction.
//  - D1 : le code est rendu EN CLAIR (but de l'onglet : lire l'OTP du titulaire pendant la démo) ;
//    protection = console en boucle locale (#66). AUCUN code dans un journal : rien n'est écrit ici.
//  - D4 : ni nom du titulaire, ni historique (isc-sim ne garde que le dernier code par IMSI).
// Les erreurs sont des CODES (INVALID_MSISDN, UNKNOWN_MSISDN, HLR_UNAVAILABLE, CORE_UNREACHABLE,
// SIM_UNAVAILABLE, SIM_UNREACHABLE) : la page les traduit en FR/EN avec la conduite à tenir.
export const dynamic = 'force-dynamic';

// Plage fictive ED-09 : 677000000 à 677009999.
const MSISDN = /^67700\d{4}$/;

export async function GET(req: Request) {
  const core = process.env.CORE_URL ?? 'http://core:8080';
  const sim = process.env.SIM_URL ?? 'http://sim:8080';
  const msisdn = new URL(req.url).searchParams.get('msisdn') ?? '';
  if (!MSISDN.test(msisdn)) {
    return Response.json({ error: 'INVALID_MSISDN' }, { status: 400 });
  }

  let hlr: { currentImsi?: string; updatedAt?: string };
  try {
    const res = await fetch(`${core}/hlr?msisdn=${encodeURIComponent(msisdn)}`, {
      cache: 'no-store',
    });
    if (res.status === 404) {
      return Response.json({ error: 'UNKNOWN_MSISDN' }, { status: 404 });
    }
    if (!res.ok) {
      return Response.json({ error: 'HLR_UNAVAILABLE' }, { status: 502 });
    }
    hlr = await res.json();
  } catch {
    return Response.json({ error: 'CORE_UNREACHABLE' }, { status: 502 });
  }

  const imsi = String(hlr.currentImsi ?? '');
  let sms: { code: string; deliveredAt: string | null } | null = null;
  try {
    const res = await fetch(`${sim}/inbox?imsi=${encodeURIComponent(imsi)}`, {
      cache: 'no-store',
    });
    if (res.ok) {
      const d = await res.json();
      sms = { code: String(d.code), deliveredAt: d.deliveredAt ?? null };
    } else if (res.status !== 404) {
      return Response.json({ error: 'SIM_UNAVAILABLE' }, { status: 502 });
    }
  } catch {
    return Response.json({ error: 'SIM_UNREACHABLE' }, { status: 502 });
  }

  return Response.json({ msisdn, imsi, hlrUpdatedAt: hlr.updatedAt ?? null, sms });
}
