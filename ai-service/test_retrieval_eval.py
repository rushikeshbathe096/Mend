"""M4 U4.7: the retrieval experiment's adoption rule and its honest TBD paths (no key / no blind dataset here)."""
import json

import pytest

from eval import retrieval_eval


@pytest.fixture(autouse=True)
def no_key(monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)


@pytest.mark.parametrize("model, rag, blind, decision", [
    ({"macro_f1": 0.70, "invalid_output_rate": 0.02}, {"macro_f1": 0.75, "invalid_output_rate": 0.02}, True, "ADOPT"),
    ({"macro_f1": 0.70, "invalid_output_rate": 0.02}, {"macro_f1": 0.70, "invalid_output_rate": 0.01}, True, "KEEP_DISABLED"),
    ({"macro_f1": 0.70, "invalid_output_rate": 0.02}, {"macro_f1": 0.80, "invalid_output_rate": 0.03}, True, "KEEP_DISABLED"),
    ({"macro_f1": 0.70, "invalid_output_rate": 0.02}, {"macro_f1": 0.80, "invalid_output_rate": 0.01}, False, "KEEP_DISABLED"),
    (None, None, True, "KEEP_DISABLED"),
])
def test_adoption_rule(model, rag, blind, decision):
    assert retrieval_eval.adoption_decision(model, rag, blind)["decision"] == decision


def test_missing_blind_dataset_is_tbd_and_keeps_the_flag_off(tmp_path):
    report = retrieval_eval.run(tmp_path / "replies-v2.jsonl")
    assert report["model"] == report["model_retrieval"] == report["baseline"] == "TBD"
    assert report["adoption"]["decision"] == "KEEP_DISABLED"


def test_without_a_key_only_the_baseline_is_measured(tmp_path):
    dataset = tmp_path / "blind.jsonl"
    dataset.write_text("\n".join(json.dumps({"id": f"b{i}", "text": t, "intent": it, "promised_date": None,
                                             "label_source": "SYNTHETIC", "dataset": "blind-test"})
                                 for i, (t, it) in enumerate([("please stop", "OPT_OUT"), ("already paid", "ALREADY_PAID")])) + "\n")
    report = retrieval_eval.run(dataset)
    assert isinstance(report["baseline"], dict) and "macro_f1" in report["baseline"]
    assert report["model"] == "TBD"
    assert report["adoption"] == {"decision": "KEEP_DISABLED", "reason": "model metrics not measured (TBD)"}
