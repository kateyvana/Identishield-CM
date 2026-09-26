"""Le lecteur YAML restreint lit ruleset et contrat, et refuse ce qu'il ne connaît pas."""

import pytest
from conftest import RULESET

from isc_mobile_mock import miniyaml, server


def test_reads_named_parameters_from_the_versioned_ruleset():
    p = miniyaml.load(RULESET)["ruleset"]["parameters"]
    params = server.Params(RULESET)
    assert params.reenrol_attempts == p["REENROL_ATTEMPTS"]
    assert params.lock_base == server.duration(p["LOCK_BASE"])
    assert params.cap_prob_daily == p["CAP_PROB"]["dailyXaf"]
    assert params.code_length == p["CODE_LENGTH"]


def test_subset_constructs():
    doc = miniyaml.loads(
        "a:\n  - x\n  - k: 1\n    j: \"z # q\"\nb: |\n  l1\n  l2\nc:\n- 1\nd: []\ne: 'o''k'\n"
    )
    assert doc == {
        "a": ["x", {"k": 1, "j": "z # q"}],
        "b": "l1\nl2\n",
        "c": [1],
        "d": [],
        "e": "o'k",
    }


@pytest.mark.parametrize("text", ["a: [1, 2]", "a: {b: 1}", "a: &x 1", "a: *x", "a: 1\na: 2"])
def test_refuses_what_it_does_not_support(text):
    with pytest.raises(miniyaml.YamlSubsetError):
        miniyaml.loads(text)


def test_iso_durations():
    assert server.duration("PT30S").total_seconds() == 30
    assert server.duration("PT1H").total_seconds() == 3600
    with pytest.raises(ValueError):
        server.duration("P1D")
