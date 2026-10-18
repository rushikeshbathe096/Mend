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


def rows(*pairs):
    return [{"id": str(i), "text": text, "intent": gold, "promised_date": None} for i, (text, gold) in enumerate(pairs)]


def test_metrics_on_a_hand_checked_example():
    predictions = {"a": "OPT_OUT", "b": "OPT_OUT", "c": "DISPUTE"}
    data = rows(("a", "OPT_OUT"), ("b", "DISPUTE"), ("c", "DISPUTE"))
    report = run_eval.evaluate(data, lambda t: {"intent": predictions[t], "promised_date": None, "abstain": False})
    assert report["per_class"]["OPT_OUT"] == {"precision": 0.5, "recall": 1.0, "f1": 0.6667, "support": 1}
    assert report["per_class"]["DISPUTE"] == {"precision": 1.0, "recall": 0.5, "f1": 0.6667, "support": 2}
    assert report["confusion_matrix"]["DISPUTE"]["OPT_OUT"] == 1
    # classes with no support and no predictions score 0 and still count in the macro average
    assert report["macro_f1"] == round((0.6667 + 0.6667) / 5, 4)


def test_abstained_and_invalid_predictions_count_as_misses():
    data = rows(("a", "OPT_OUT"), ("b", "OPT_OUT"))
    answers = {"a": {"intent": "OPT_OUT", "promised_date": None, "abstain": True}, "b": {"intent": "NOPE", "abstain": False}}
    report = run_eval.evaluate(data, lambda t: answers[t])
    assert report["per_class"]["OPT_OUT"]["recall"] == 0.0
    assert report["abstention_rate"] == 0.5
    assert report["invalid_output_rate"] == 0.5
    assert report["confusion_matrix"]["OPT_OUT"]["ABSTAIN"] == 1
    assert report["confusion_matrix"]["OPT_OUT"]["INVALID"] == 1


def test_model_ids_are_collected_as_reported():
    report = run_eval.evaluate(rows(("a", "OTHER")), lambda t: {
        "suggestion": {"intent": "OTHER", "promised_date": None, "abstain": False}, "model_id": "gemini-x-001"})
    assert report["model_ids_reported"] == ["gemini-x-001"]


@pytest.mark.parametrize("text,intent", [
    ("This charge is wrong, stop messaging me", "DISPUTE"),   # dispute wins over opt-out (label guide)
    ("Please stop", "OPT_OUT"),
    ("I already paid", "ALREADY_PAID"),
    ("I will pay on 2026-11-02", "PROMISE_TO_PAY"),
    ("Hello?", "OTHER"),
])
def test_keyword_baseline_rules(text, intent):
    assert run_eval.keyword_baseline(text)["intent"] == intent


def test_keyword_baseline_takes_only_an_exact_iso_date():
    assert run_eval.keyword_baseline("I will pay on 2026-11-02")["promised_date"] == "2026-11-02"
    assert run_eval.keyword_baseline("I will pay tomorrow")["promised_date"] is None

