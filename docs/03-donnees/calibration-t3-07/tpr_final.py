"""TPR AC-02 attendu avec les valeurs exactes écrites dans le ruleset (réutilise adr016_study.py)."""
import json
from pathlib import Path
src = (Path(__file__).parent / "adr016_study.py").read_text(encoding="utf-8")
head, rest = src.split('res = {"fenetre_mesure"')
tpr_src = "def tpr" + rest.split("def tpr", 1)[1].split('res["7_tpr_ac02"]')[0]
exec(compile(head, "adr016_study.py", "exec"))
plan = list(csv.DictReader(open(PLAN, newline="", encoding="utf-8")))
exec(compile(tpr_src, "adr016_study.py", "exec"))
P = variant(W={"newBeneficiary": 15, "newBeneficiaryHigh": 45, "vel": 70, "night": 25})
print(json.dumps(tpr(P), ensure_ascii=False, indent=1))
