"""Assemble, pour chaque commit comparé, un paquet autonome exécutable dans la VM.

Usage : python tools/bench_evaluate/bundle.py <travail> <commit>...

<travail>/<commit> est l'arbre extrait (pdp, config, data/eval) où printBenchCp a écrit
pdp/build/bench-cp.txt. Le paquet <travail>/vm/<commit> contient les classes, les jars du
classpath de test, le plan AC-02 et le jeu de règles, aux mêmes chemins relatifs que dans le dépôt.
"""

import shutil
import sys
from pathlib import Path


def bundle(work: Path, commit: str) -> int:
    src = work / commit
    root = work / "vm" / commit
    pdp = root / "pdp"
    (pdp / "lib").mkdir(parents=True, exist_ok=True)
    shutil.copytree(src / "config", root / "config", dirs_exist_ok=True)
    shutil.copytree(src / "data", root / "data", dirs_exist_ok=True)
    entries = []
    lines = (src / "pdp" / "build" / "bench-cp.txt").read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(x for x in lines if x):
        p = Path(line)
        if p.is_dir():
            dst = f"d{i}"
            shutil.copytree(p, pdp / dst, dirs_exist_ok=True)
        else:
            dst = f"lib/{i:03d}-{p.name}"
            shutil.copy2(p, pdp / dst)
        entries.append(dst)
    (pdp / "cp.txt").write_bytes(":".join(entries).encode("utf-8"))
    return len(entries)


def main() -> int:
    work = Path(sys.argv[1])
    for commit in sys.argv[2:]:
        print(f"{commit} : {bundle(work, commit)} entrées de classpath")
    return 0


if __name__ == "__main__":
    sys.exit(main())
