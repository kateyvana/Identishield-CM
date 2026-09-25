"""Tests de l'outil agent HLR : la charge utile, et l'ABSENCE de tout indicateur d'intention."""

import pytest

from isc_hlr_agent.cli import _parser, build_body


def test_build_body_has_exactly_the_four_fields() -> None:
    body = build_body("677000001", "624010000000002", "agent-42", "motif declare")
    assert body == {
        "msisdn": "677000001",
        "newImsi": "624010000000002",
        "agentId": "agent-42",
        "reason": "motif declare",
    }


def test_no_intent_flag_is_accepted() -> None:
    # Le systeme ne distingue pas un agent legitime d'un complice : aucun drapeau d'intention.
    parser = _parser()
    base = ["reassign", "--msisdn", "x", "--new-imsi", "y", "--agent-id", "z", "--motif", "m"]
    for forbidden in ["--fraud", "--legit", "--legitimate", "--mode", "--intent", "--complice"]:
        with pytest.raises(SystemExit):
            parser.parse_args([*base, forbidden])


def test_reassign_requires_all_four_params() -> None:
    parser = _parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["reassign", "--msisdn", "x"])  # manque new-imsi / agent-id / motif
