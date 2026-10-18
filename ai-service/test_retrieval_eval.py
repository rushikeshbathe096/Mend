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


def write(tmp_path, name, dataset):
    path = tmp_path / name
    path.write_text("\n".join(json.dumps({"id": f"b{i}", "text": t, "intent": it, "promised_date": None,
                                          "label_source": "SYNTHETIC", "dataset": dataset})
                              for i, (t, it) in enumerate([("please stop", "OPT_OUT"), ("already paid", "ALREADY_PAID")])) + "\n")
    return path


def test_without_a_key_only_the_baseline_is_measured(tmp_path):
    report = retrieval_eval.run(write(tmp_path, "v2.jsonl", "replies-v2"))
    assert isinstance(report["baseline"], dict) and "macro_f1" in report["baseline"]
    assert len(report["dataset_sha256"]) == 64
    assert report["model"] == "TBD"
    assert report["adoption"] == {"decision": "KEEP_DISABLED", "reason": "model metrics not measured (TBD)"}


def test_a_dataset_that_is_not_the_blind_one_never_adopts(tmp_path, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    install_fake_model(monkeypatch, retrieval_better=True)
    dataset = write(tmp_path, "v1test.jsonl", "replies-v1-test")
    examples = write_examples(tmp_path)
    report = retrieval_eval.run(dataset, examples)
    assert report["adoption"]["decision"] == "KEEP_DISABLED"
    assert "blind" in report["adoption"]["reason"]


def test_measured_improvement_on_the_blind_dataset_adopts(tmp_path, monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "test-key-not-real")
    install_fake_model(monkeypatch, retrieval_better=True)
    report = retrieval_eval.run(write(tmp_path, "v2.jsonl", "replies-v2"), write_examples(tmp_path))
    assert report["adoption"]["decision"] == "ADOPT"
    install_fake_model(monkeypatch, retrieval_better=False)
    assert retrieval_eval.run(write(tmp_path, "v2b.jsonl", "replies-v2"), write_examples(tmp_path))["adoption"]["decision"] == "KEEP_DISABLED"


def test_provider_errors_make_the_systems_incomparable():
    model = {"macro_f1": 0.0, "invalid_output_rate": 0.0, "provider_errors": 2, "scored_rows": 0}
    rag = {"macro_f1": 0.9, "invalid_output_rate": 0.0, "provider_errors": 0, "scored_rows": 2}
    assert retrieval_eval.adoption_decision(model, rag, True)["decision"] == "KEEP_DISABLED"


def test_examples_must_cover_every_row_and_never_contain_an_evaluation_text():
    rows = [{"id": "a", "text": "please stop"}, {"id": "b", "text": "already paid"}]
    ok = {"a": [{"text": "stop it", "intent": "OPT_OUT"}], "b": [{"text": "paid", "intent": "ALREADY_PAID"}]}
    assert retrieval_eval.examples_problem(rows, ok) is None
    assert "no retrieved examples" in retrieval_eval.examples_problem(rows, {"a": ok["a"]})
    leaked = {"a": [{"text": "already paid", "intent": "ALREADY_PAID"}], "b": ok["b"]}
    assert "contamination" in retrieval_eval.examples_problem(rows, leaked)
    assert "duplicate" in retrieval_eval.examples_problem(rows + [{"id": "c", "text": "please stop"}], ok)


def write_examples(tmp_path):
    path = tmp_path / "examples.jsonl"
    path.write_text("\n".join(json.dumps({"id": i, "examples": [{"text": f"example for {i}", "intent": "OTHER", "promised_date": None}]})
                              for i in ("b0", "b1")) + "\n")
    return path


def install_fake_model(monkeypatch, retrieval_better):
    """A stubbed provider: the plain model labels everything OTHER; with examples it is right (or not)."""
    gold = {"please stop": "OPT_OUT", "already paid": "ALREADY_PAID"}

    def fake(text, http=None, examples=None):
        intent = gold[text] if examples and retrieval_better else "OTHER"
        return retrieval_eval.reply_parser.ParseResult({"intent": intent, "promised_date": None, "abstain": False}, None, "fake-model")

    monkeypatch.setattr(retrieval_eval.reply_parser, "parse_reply", fake)
