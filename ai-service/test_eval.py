"""M3 U2.5: the evaluation is reproducible and honest (same dataset version -> same baseline metrics; no model numbers
without a model run)."""
import json

import pytest

from eval import run_eval


@pytest.fixture(autouse=True)
def no_key(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)


def test_baseline_is_deterministic_for_a_dataset_version():
    first, second = run_eval.run(), run_eval.run()
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)


def test_every_row_is_labelled_synthetic_and_splits_are_versioned():
    report = run_eval.run()
    assert report["dataset_version"] == "replies-v1"
    assert report["headline_split"] == "test"
    for split in report["splits"].values():
        assert split["label_source"] == "SYNTHETIC"
        assert len(split["dataset_sha256"]) == 64


def test_model_is_tbd_without_a_key_and_carries_no_numbers():
    model = run_eval.run()["splits"]["test"]["model"]
    assert model["status"] == "TBD"
    assert not any(key in model for key in ("macro_f1", "per_class", "confusion_matrix"))


def test_recorded_results_match_a_fresh_run():
    recorded = json.loads(run_eval.DEFAULT_OUT.read_text())
    assert recorded == json.loads(json.dumps(run_eval.run()))


def test_non_synthetic_rows_are_refused(tmp_path):
    path = tmp_path / "x.jsonl"
    path.write_text(json.dumps({"id": "x", "text": "hi", "intent": "OTHER", "promised_date": None,
                                "label_source": "CUSTOMER", "dataset": "x"}) + "\n")
    with pytest.raises(ValueError):
        run_eval.load(path)


@pytest.mark.parametrize("suggestion,ok", [
    ({"intent": "OPT_OUT", "promised_date": None, "abstain": False}, True),
    ({"intent": "PROMISE_TO_PAY", "promised_date": "2026-11-02", "abstain": False}, True),
    ({"intent": "OPT_OUT", "abstain": False, "confidence": 0.9}, False),
    ({"intent": "REFUND", "abstain": False}, False),
    ({"intent": "OPT_OUT", "abstain": "no"}, False),
    ({"intent": "OPT_OUT", "promised_date": "2026-11-02", "abstain": False}, False),
    (None, False),
])
def test_validity_matches_the_backend_schema(suggestion, ok):
    assert run_eval.valid(suggestion) is ok
