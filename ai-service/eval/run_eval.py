"""Reply-parsing evaluation (M3 U2.5, ADR-003 §6).

    python -m eval.run_eval [--out ../docs/m3/eval/results-replies-v1.json]

Runs both splits of replies-v1: `dev` (the baseline's rules were written against it: an upper bound) and `test` (held
out: the headline).

Systems:
- `keyword-baseline-v1`: deterministic keyword rules; always run.
- the model (reply_parser, prompt version reply_parser.PROMPT_VERSION): only when GEMINI_API_KEY is set. Without a key the
  model section says "TBD" with the reason; no number is ever written for a model that did not run.

Metrics: per-class precision/recall/F1, macro F1, confusion matrix, invalid-output rate, abstention rate, and exact match
of promised_date on PROMISE_TO_PAY rows. An abstained or invalid prediction counts as a miss for its gold class.
The dataset is SYNTHETIC (see docs/m3/eval/LABEL-GUIDE.md): results describe this dataset, not production.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Callable, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import reply_parser  # noqa: E402

INTENTS = list(reply_parser.INTENTS)
ROOT = Path(__file__).resolve().parents[2]
SPLITS = {"dev": ROOT / "docs/m3/eval/replies-v1-dev.jsonl", "test": ROOT / "docs/m3/eval/replies-v1-test.jsonl"}
DEFAULT_OUT = ROOT / "docs/m3/eval/results-replies-v1.json"
BASELINE_VERSION = "keyword-baseline-v1"
ISO_DATE = re.compile(r"\b(\d{4}-\d{2}-\d{2})\b")

# Order matters: the first matching rule wins (a dispute that also says "stop" is a dispute, as in the label guide).
KEYWORD_RULES = [
    ("DISPUTE", ["never signed", "not authorize", "unauthorized", "fraud", "wrong", "incorrect", "disput", "chargeback",
                 "twice", "not my order", "never delivered", "never used", "shouldn't pay", "returned the product"]),
    ("OPT_OUT", ["stop", "unsubscribe", "don't contact", "do not contact", "remove my number", "do not want any more",
                 "no more", "leave me alone", "opt me out", "do not call", "don't call"]),
    ("ALREADY_PAID", ["already paid", "payment done", "i paid", "paid via", "paid through", "settled", "debited",
                      "made the payment", "paid.", "transferred", "cleared the dues", "paid in full", "paid it"]),
    ("PROMISE_TO_PAY", ["i'll pay", "will pay", "can pay", "i'll clear", "paying on", "will retry the payment",
                        "let me pay", "will settle", "will make the payment", "and pay on"]),
]


def load(path: Path) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    for row in rows:
        if row.get("label_source") != "SYNTHETIC":
            raise ValueError(f"row {row.get('id')} is not labelled SYNTHETIC")
        if row["intent"] not in INTENTS:
            raise ValueError(f"row {row['id']} has unknown intent {row['intent']}")
    return rows


def keyword_baseline(text: str) -> dict:
    lower = text.lower()
    for intent, keywords in KEYWORD_RULES:
        if any(k in lower for k in keywords):
            date = ISO_DATE.search(text) if intent == "PROMISE_TO_PAY" else None
            return {"intent": intent, "promised_date": date.group(1) if date else None, "abstain": False}
    return {"intent": "OTHER", "promised_date": None, "abstain": False}


def valid(suggestion) -> bool:
    """The backend's schema (ReplySuggestionValidator), restated: exact fields, enum intent, boolean abstain, ISO date."""
    if not isinstance(suggestion, dict) or set(suggestion) - {"intent", "promised_date", "abstain"}:
        return False
    if suggestion.get("intent") not in INTENTS or not isinstance(suggestion.get("abstain"), bool):
        return False
    date = suggestion.get("promised_date")
    if date is not None and (not isinstance(date, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date)
                             or suggestion["intent"] != "PROMISE_TO_PAY"):
        return False
    return True


def evaluate(rows: list[dict], predict: Callable[[str], Optional[dict]]) -> dict:
    labels = INTENTS + ["ABSTAIN", "INVALID"]
    confusion = {gold: {pred: 0 for pred in labels} for gold in INTENTS}
    invalid = abstained = date_total = date_hits = 0
    model_ids = set()
    for row in rows:
        result = predict(row["text"])
        suggestion = result.get("suggestion") if isinstance(result, dict) and "suggestion" in result else result
        if isinstance(result, dict) and result.get("model_id"):
            model_ids.add(result["model_id"])
        if not valid(suggestion):
            invalid += 1
            predicted = "INVALID"
        elif suggestion["abstain"]:
            abstained += 1
            predicted = "ABSTAIN"
        else:
            predicted = suggestion["intent"]
        confusion[row["intent"]][predicted] += 1
        if row["intent"] == "PROMISE_TO_PAY":
            date_total += 1
            date_hits += int(predicted == "PROMISE_TO_PAY" and suggestion.get("promised_date") == row["promised_date"])
    per_class = {}
    for intent in INTENTS:
        tp = confusion[intent][intent]
        fp = sum(confusion[g][intent] for g in INTENTS if g != intent)
        fn = sum(confusion[intent][p] for p in labels if p != intent)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_class[intent] = {"precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4),
                             "support": sum(confusion[intent].values())}
    n = len(rows)
    return {
        "per_class": per_class,
        "macro_f1": round(sum(c["f1"] for c in per_class.values()) / len(INTENTS), 4),
        "confusion_matrix": confusion,
        "invalid_output_rate": round(invalid / n, 4),
        "abstention_rate": round(abstained / n, 4),
        "promised_date_exact_match": {"hits": date_hits, "total": date_total},
        "model_ids_reported": sorted(model_ids),
    }


def model_predict(text: str) -> dict:
    try:
        result = reply_parser.parse_reply(text)
    except reply_parser.ModelError as e:
        return {"suggestion": None, "model_id": None, "error": str(e)}
    return {"suggestion": result.suggestion, "model_id": result.model_id}


def run_split(dataset: Path, run_model: bool) -> dict:
    rows = load(dataset)
    report = {
        "dataset": rows[0]["dataset"],
        "dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest(),
        "rows": len(rows),
        "label_source": "SYNTHETIC",
        "baseline": {"system": BASELINE_VERSION, **evaluate(rows, keyword_baseline)},
    }
    if run_model:
        report["model"] = {"system": os.getenv("MEND_REPLY_MODEL", reply_parser.DEFAULT_MODEL),
                           "prompt_version": reply_parser.PROMPT_VERSION, **evaluate(rows, model_predict)}
    else:
        report["model"] = {"status": "TBD", "reason": "no GEMINI_API_KEY in the environment; the model was not run",
                           "prompt_version": reply_parser.PROMPT_VERSION}
    return report


def run(splits: dict[str, Path] = SPLITS) -> dict:
    run_model = reply_parser.model_configured()
    return {"dataset_version": "replies-v1", "headline_split": "test",
            "splits": {name: run_split(path, run_model) for name, path in splits.items()}}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    report = run()
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    for name, split in report["splits"].items():
        print(f"{split['dataset']}: baseline macro F1 {split['baseline']['macro_f1']}; model {split['model'].get('status', 'run')}")


if __name__ == "__main__":
    main()
