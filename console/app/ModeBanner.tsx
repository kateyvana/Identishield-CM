// Bandeau d'état rendu CÔTÉ SERVEUR (EF-07, EF-36) : le mode sans couche et l'état du PDP, affichés
// SANS AMBIGUÏTÉ. Rendu à chaque requête (dynamic, no-store) pour refléter l'état courant du cœur ;
// le texte est donc dans le HTML servi (démonstration vérifiable). En mode sans couche, le bandeau
// dit explicitement que la protection est DÉSACTIVÉE — il ne doit jamais laisser croire l'inverse.

type ModeStatus = { layerless: boolean; pdpState: string };

const PDP_LABEL: Record<string, string> = {
  DISPONIBLE: 'disponible',
  DEGRADE: 'dégradé',
  INDISPONIBLE: 'indisponible',
};

async function fetchMode(): Promise<ModeStatus | null> {
  const base = process.env.CORE_URL ?? 'http://core:8080';
  try {
    const res = await fetch(`${base}/mode`, { cache: 'no-store' });
    if (!res.ok) return null;
    return (await res.json()) as ModeStatus;
  } catch {
    return null;
  }
}

export default async function ModeBanner() {
  const mode = await fetchMode();

  if (!mode) {
    return (
      <div className="bandeau bandeau-inconnu" role="status">
        État du cœur inconnu — cœur injoignable.
      </div>
    );
  }

  if (mode.layerless) {
    return (
      <div className="bandeau bandeau-sans-couche" role="alert">
        <strong>MODE SANS COUCHE — PROTECTION DÉSACTIVÉE.</strong> Démonstration « avant » : les
        opérations s’exécutent sans passer par le point de décision. Ce n’est PAS l’état protégé.
      </div>
    );
  }

  const pdp = PDP_LABEL[mode.pdpState] ?? mode.pdpState.toLowerCase();
  return (
    <div className="bandeau bandeau-protege" role="status">
      <strong>Protection active.</strong> Point de décision (PDP) : {pdp}.
    </div>
  );
}
