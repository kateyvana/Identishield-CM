"""T3-04b — Tests de l'évaluation AC-02.

Aucun de ces tests ne lance l'attaque : le consignateur est appelé en --dry-run,
et le plan est du pur calcul déterministe. On vérifie la séparation vérité-terrain
(anti-fuite), le déterminisme, et la forme des sorties.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tools" / "eval_harness"))

import ac02_consign
import ac02_plan

FORBIDDEN = re.compile(r"(?i)(label|archetype|victime|fraud|ground[_-]?truth|intent|legit)")


def _make_dev(tmp: Path) -> tuple[Path, Path, Path]:
    det = tmp / "dev.csv"
    det.write_text(
        "id,user,direction,amount,timestamp,beneficiary,balance_before,balance_after,current_imsi,channel,device_bound\n"
        + "".join(
            f"t{i},67700{i:04d},OUT,{1000 + i},2026-10-{(i % 27) + 1:02d}T10:00:00Z,"
            f"677001111,{9000 + i},{8000 + i},6240100000{i:05d},USSD,false\n"
            for i in range(400)
        ),
        encoding="utf-8",
    )
    # sidecar au format réel : PAS de colonne 'user', jointure par 'id' ;
    # archétypes mélangés (340 ordinaire, 60 agent) pour exercer le filtre.
    side = tmp / "dev.labels.csv"
    side.write_text(
        "id,label,source,archetype,is_new_beneficiary,sim_event_case\n"
        + "".join(
            f"t{i},LEGIT,generator,{'ordinaire' if i < 340 else 'agent'},false,\n"
            for i in range(400)
        ),
        encoding="utf-8",
    )
    hlr = tmp / "dev.hlr_events.csv"
    hlr.write_text("msisdn,old_imsi,new_imsi,timestamp,agent_id\n", encoding="utf-8")
    return det, side, hlr


def test_plan_is_deterministic(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    c1 = ac02_plan.build_plan(det, side, hlr)
    c2 = ac02_plan.build_plan(det, side, hlr)
    assert c1 == c2
    h1 = ac02_plan.write_plan(c1, tmp_path / "p1.csv")
    h2 = ac02_plan.write_plan(c2, tmp_path / "p2.csv")
    assert h1 == h2, "le plan doit être identique à l'octet pour la même graine"


def test_plan_has_no_ground_truth_column(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    ac02_plan.write_plan(ac02_plan.build_plan(det, side, hlr), tmp_path / "p.csv")
    header = (tmp_path / "p.csv").read_text(encoding="utf-8").splitlines()[0]
    for col in header.split(","):
        assert not FORBIDDEN.search(col), f"colonne interdite dans le plan : {col}"


def test_plan_size_and_fictitious_range(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    cases = ac02_plan.build_plan(det, side, hlr)
    assert len(cases) == ac02_plan.N_CASES
    for c in cases:
        assert c.victim_msisdn.startswith("677"), "MSISDN hors plage fictive"
        assert c.attacker_imsi.startswith("624019999999"), "IMSI attaquant non fictif"
        assert len(c.attacker_imsi) == 15, "IMSI attaquant hors format 15 chiffres"
        assert c.beneficiary_msisdn.startswith("677"), "bénéficiaire hors plage"


def test_plan_selects_only_ordinary(tmp_path: Path) -> None:
    # les usagers i>=340 sont 'agent' via la jointure id du sidecar : jamais tirés.
    det, side, hlr = _make_dev(tmp_path)
    cases = ac02_plan.build_plan(det, side, hlr)
    non_ordinary = {f"67700{i:04d}" for i in range(340, 400)}
    for c in cases:
        assert c.victim_msisdn not in non_ordinary, "un non-ordinaire a été tiré"


def test_consign_dry_run_separates_views(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    ac02_plan.write_plan(ac02_plan.build_plan(det, side, hlr), tmp_path / "plan.csv")
    _hv, _hl, _ok, _fail = ac02_consign.consign(
        tmp_path / "plan.csv",
        tmp_path / "runs.csv",
        tmp_path / "runs.labels.csv",
        dry_run=True,
    )
    view_cols = set((tmp_path / "runs.csv").read_text().splitlines()[0].split(","))
    label_cols = set((tmp_path / "runs.labels.csv").read_text().splitlines()[0].split(","))
    # anti-fuite : la vue d'évaluation ne contient aucune colonne de vérité-terrain
    for col in view_cols:
        assert not FORBIDDEN.search(col) or col in {
            "balance_emptied",
            "out_to_attacker",
            "otp_to_attacker_imsi",
            "no_old_device_notif",
            "all_oracles_ok",
        }, f"fuite possible : {col}"
    assert "label" not in view_cols
    assert "label" in label_cols
    # jointure 1-pour-1 sur run_id
    assert view_cols & label_cols == {"run_id"}


def test_consign_dry_run_is_deterministic(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    ac02_plan.write_plan(ac02_plan.build_plan(det, side, hlr), tmp_path / "plan.csv")
    a = ac02_consign.consign(
        tmp_path / "plan.csv", tmp_path / "v1.csv", tmp_path / "l1.csv", dry_run=True
    )
    b = ac02_consign.consign(
        tmp_path / "plan.csv", tmp_path / "v2.csv", tmp_path / "l2.csv", dry_run=True
    )
    assert a[:2] == b[:2], "vue et sidecar doivent être identiques à l'octet"


def test_every_view_row_has_one_label(tmp_path: Path) -> None:
    det, side, hlr = _make_dev(tmp_path)
    ac02_plan.write_plan(ac02_plan.build_plan(det, side, hlr), tmp_path / "plan.csv")
    ac02_consign.consign(
        tmp_path / "plan.csv",
        tmp_path / "runs.csv",
        tmp_path / "runs.labels.csv",
        dry_run=True,
    )
    with (tmp_path / "runs.csv").open() as f:
        view_ids = {r["run_id"] for r in csv.DictReader(f)}
    with (tmp_path / "runs.labels.csv").open() as f:
        label_ids = {r["run_id"] for r in csv.DictReader(f)}
    assert view_ids == label_ids and len(view_ids) == ac02_plan.N_CASES
