"""Tests of the survey analysis. Synthetic fixtures only: no real answer is ever versioned."""

import csv
import json
import re
from pathlib import Path

import pytest

from isc_survey import analysis
from isc_survey.analysis import (
    ANON_COLUMNS,
    ANON_FILE,
    UnknownLabel,
    analyse,
    load,
    load_anonymised,
    share,
    wilson,
    write_anonymised,
)

ROOT = Path(__file__).resolve().parents[3]
RAW = ROOT / "data" / "survey" / "raw"
PUBLISHED = ROOT / "docs" / "03-donnees" / "sondage" / ANON_FILE

# One full FR answer and one full EN answer (labels copied from the harmonisation tables).
FR_ROW = [
    "2026/09/23 1:00:00 PM GMT+1",
    "Étudiant",
    "Les Deux",
    "Code USSD (*126#, #150#)",
    "Tous les jours",
    "Envoyer / recevoir de l'argent (famille, proches);Épargner de l'argent",
    "Plus de 500 000 FCFA",
    "Non, jamais entendu parler",
    "No",
    "No",
    "Oui",
    "Moi-même",
    "Non",
    "Important – Je perdrais beaucoup",
    "10",
    "Non",
    "Très utile",
    "Oui, à chaque fois si nécessaire.",
    "Oui",
    "Se rendre en agence muni d'une pièce d'identité",
    "Oui",
    "Oui, immédiatement",
    "",
    "Crainte d'être bloqué injustement",
]
EN_ROW = [
    "2026/09/25 1:00:00 PM GMT+1",
    "Employee",
    "Yes, MTN MoMo",
    "App (MyMTN, Orange Money)",
    "Rarely",
    "Saving money",
    "Less than 10,000 FCFA",
    "Yes , I know well",
    "Yes",
    "Yes ,often",
    "No",
    "Someone close to me",
    "Partially",
    "Not serious – I have little money inside",
    "3",
    "Yes",
    "Useless",
    "No",
    "No",
    "A recovery email address registered in advance",
    "Yes",
    "No it is too complicated",
    "Yes, but I would be afraid of losing it",
    "Cost",
]


def _header(lang: str) -> list[str]:
    second = {"fr": "Activité principale", "en": "Main occupation"}[lang]
    return ["Timestamp", second] + [f"q{i}" for i in range(2, 24)]


def _write(tmp_path: Path, lang: str, rows: list[list[str]]) -> Path:
    p = tmp_path / f"{lang}.csv"
    with p.open("w", encoding="utf-8", newline="") as f:
        csv.writer(f).writerows([_header(lang), *rows])
    return p


def test_wilson_matches_known_values():
    # Intervals published in RAPPORT_PLAUSIBILITE_v1 §5 (1 decimal): 14/52 and 5/49.
    assert wilson(14, 52) == pytest.approx((16.8, 40.3), abs=0.05)
    assert wilson(5, 49) == pytest.approx((4.4, 21.8), abs=0.05)
    assert wilson(0, 0) == (0.0, 0.0)


def test_entirely_empty_submission_is_excluded(tmp_path):
    empty = ["2026/09/23 2:00:00 PM GMT+1"] + [""] * 23
    rs, n_empty = load(_write(tmp_path, "fr", [FR_ROW, empty]), "fr")
    assert (len(rs), n_empty) == (1, 1)


def test_unknown_label_fails_loudly(tmp_path):
    row = list(FR_ROW)
    row[13] = "Assez grave"  # not in the harmonisation table
    with pytest.raises(UnknownLabel, match="Q13"):
        load(_write(tmp_path, "fr", [row]), "fr")


def test_unknown_option_inside_multi_choice_fails(tmp_path):
    row = list(FR_ROW)
    row[23] = "Crainte d'être bloqué injustement;Autre chose"
    with pytest.raises(UnknownLabel, match="Q23"):
        load(_write(tmp_path, "fr", [row]), "fr")


def test_out_of_scale_fear_fails(tmp_path):
    row = list(FR_ROW)
    row[14] = "11"
    with pytest.raises(UnknownLabel, match="Q14"):
        load(_write(tmp_path, "fr", [row]), "fr")


def test_wrong_header_is_rejected(tmp_path):
    with pytest.raises(ValueError, match="en-tête"):
        load(_write(tmp_path, "en", [FR_ROW]), "fr")  # EN header read as FR


def test_q6_is_never_merged(tmp_path):
    fr, _ = load(_write(tmp_path, "fr", [FR_ROW]), "fr")
    en, _ = load(_write(tmp_path, "en", [EN_ROW]), "en")
    q6 = analyse(fr, en)["Q6_not_merged"]
    assert set(q6) == {"fr_max_balance_ever", "en_max_balance_per_month"}
    assert q6["fr_max_balance_ever"]["n"] == 1 and q6["en_max_balance_per_month"]["n"] == 1


def test_q12_restricted_to_declared_victims_and_q20_to_ussd_users(tmp_path):
    bystander = list(FR_ROW)
    bystander[11] = "Non, jamais"  # not a victim: its Q12 must not count
    bystander[3] = "App (My MTN , Orange Money)"  # not a USSD user: its Q20 must not count
    fr, _ = load(_write(tmp_path, "fr", [FR_ROW, bystander]), "fr")
    en, _ = load(_write(tmp_path, "en", [EN_ROW]), "en")  # victim via "close", app user
    a = analyse(fr, en)
    q12 = a["severity"]["Q12_not_recovered_victims"]["total"]
    assert (q12["k"], q12["n"]) == (1, 2)  # FR self (no) + EN close (partial)
    q20 = a["adoption"]["Q20_yes_ussd_users"]["total"]
    assert (q20["k"], q20["n"]) == (1, 1)  # only the FR USSD user


def test_fear_statistics_and_multi_choice_share(tmp_path):
    fr, _ = load(_write(tmp_path, "fr", [FR_ROW]), "fr")
    en, _ = load(_write(tmp_path, "en", [EN_ROW]), "en")
    fear = analyse(fr, en)["severity"]["Q14_fear"]["total"]
    assert fear["median"] == 6.5 and fear["share_8_10"]["k"] == 1 and fear["share_1_3"]["k"] == 1
    assert share(en, "Q23", {"cost"})["k"] == 1


def test_outputs_are_deterministic_and_carry_no_timestamp(tmp_path):
    fr_p = _write(tmp_path, "fr", [FR_ROW])
    en_p = _write(tmp_path, "en", [EN_ROW])
    for out in ("a", "b"):
        analysis.main(["--fr", str(fr_p), "--en", str(en_p), "--out-dir", str(tmp_path / out)])
    files = sorted(p.name for p in (tmp_path / "a").iterdir())
    assert files == sorted(p.name for p in (tmp_path / "b").iterdir())
    for name in files:
        a_txt = (tmp_path / "a" / name).read_text(encoding="utf-8")
        assert a_txt == (tmp_path / "b" / name).read_text(encoding="utf-8")
        assert "2026/09" not in a_txt  # no submission timestamp leaks into the outputs


def assert_supervisor_oracle(a: dict) -> None:
    """Independent supervisor computation (2026-10-08): must be reproduced EXACTLY."""

    def kn(p):
        return (p["total"]["k"], p["total"]["n"])

    assert (a["n_valid"]["total"], a["n_valid"]["fr"], a["n_valid"]["en"]) == (79, 44, 35)
    assert kn(a["awareness"]["Q8_unaware_of_flaw"]) == (40, 79)
    assert kn(a["awareness"]["Q10_relies_on_sms_code"]) == (47, 79)
    assert kn(a["severity"]["Q12_not_recovered_victims"]) == (21, 27)
    assert kn(a["severity"]["Q13_serious_or_catastrophic"]) == (62, 79)
    assert a["severity"]["Q14_fear"]["total"]["median"] == 7
    assert kn(a["adoption"]["Q17_accepts_extra_step"]) == (71, 78)
    assert kn(a["adoption"]["Q20_yes_ussd_users"]) == (33, 50)
    assert kn(a["adoption"]["Q21_would_install"]) == (70, 77)
    assert kn(a["adoption"]["Q22_would_accept_code"]) == (16, 19)
    assert kn(a["expectations"]["Q23_fear_wrongly_blocked"]) == (37, 75)


@pytest.mark.skipif(
    not (RAW / "reponses_fr.csv").exists(), reason="exports bruts absents (non versionnés)"
)
def test_supervisor_oracle_on_real_exports(tmp_path):
    analysis.main(
        [
            "--fr",
            str(RAW / "reponses_fr.csv"),
            "--en",
            str(RAW / "reponses_en.csv"),
            "--out-dir",
            str(tmp_path),
        ]
    )
    assert_supervisor_oracle(
        json.loads((tmp_path / "agregats_sondage_v1.json").read_text(encoding="utf-8"))
    )


def test_published_anonymised_dataset_reproduces_the_oracle():
    """ALWAYS runs: the versioned de-identified dataset alone yields the 11 oracle values."""
    text = PUBLISHED.read_text(encoding="utf-8")
    rows = list(csv.reader(text.splitlines()))
    assert rows[0] == ANON_COLUMNS and "Timestamp" not in rows[0]
    assert len(rows) - 1 == 79
    assert not re.search(r"\d{4}/\d{2}/\d{2}|GMT|\d{1,2}:\d{2}:\d{2}", text)  # no timestamp
    rs = load_anonymised(PUBLISHED)
    fr = [r for r in rs if r["lang"] == "fr"]
    en = [r for r in rs if r["lang"] == "en"]
    assert_supervisor_oracle(analyse(fr, en))


def test_anonymised_round_trip_is_lossless_and_shuffled_deterministically(tmp_path):
    fr, _ = load(_write(tmp_path, "fr", [FR_ROW, FR_ROW]), "fr")
    en, _ = load(_write(tmp_path, "en", [EN_ROW]), "en")
    for name in ("a.csv", "b.csv"):
        write_anonymised(fr + en, tmp_path / name)
    assert (tmp_path / "a.csv").read_bytes() == (tmp_path / "b.csv").read_bytes()
    back = load_anonymised(tmp_path / "a.csv")
    assert analyse(
        [r for r in back if r["lang"] == "fr"], [r for r in back if r["lang"] == "en"]
    ) == (analyse(fr, en))


def test_anonymised_unknown_code_fails(tmp_path):
    p = tmp_path / "anon.csv"
    header = ",".join(ANON_COLUMNS)
    body = "fr," + ",".join(["inconnu"] * 23)
    p.write_text(f"{header}\n{body}\n", encoding="utf-8")
    with pytest.raises(UnknownLabel):
        load_anonymised(p)
