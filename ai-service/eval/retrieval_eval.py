"""Retrieval-augmented reply parsing: the GATED experiment (M4 U4.7, ADR-004 §8).

    python -m eval.retrieval_eval [--dataset ../docs/m3/eval/replies-v2.jsonl] [--examples FILE] [--out FILE]

Compares three systems on a BLIND dataset (written without sight of the keyword rules):
- `keyword-baseline-v1` (always run),
- the model with prompt `reply-parse-v1` (only with GEMINI_API_KEY),
- the model with prompt `reply-parse-v2-fewshot` and k retrieved examples (only with GEMINI_API_KEY and an examples file).

Retrieval itself happens in the backend (PostgreSQL full-text search over the SYNTHETIC / consented example store,
`ReplyExampleStore.similar`); `--examples` is a JSONL file `{"id": <row id>, "examples": [...]}` exported from it.

Adoption rule (ADR-004 §8): enable retrieval only if, on the blind dataset, model+retrieval macro F1 is higher than the
model's AND its invalid-output rate is not higher. Anything not measured is TBD and the flag stays off. Never claim an
improvement without the measurement.
"""
import argparse
import json
import os
from pathlib import Path
from typing import Optional

from eval import run_eval
import reply_parser

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "docs/m3/eval/replies-v2.jsonl"
DEFAULT_OUT = ROOT / "docs/m4/eval/retrieval-results.json"


def adoption_decision(model: Optional[dict], model_retrieval: Optional[dict], blind: bool) -> dict:
    """ADOPT only with a measured improvement on a blind dataset; otherwise KEEP_DISABLED with the reason."""
    if not blind:
        return {"decision": "KEEP_DISABLED", "reason": "no blind dataset (replies-v2): not measured (TBD)"}
    if not model or not model_retrieval or "macro_f1" not in model or "macro_f1" not in model_retrieval:
        return {"decision": "KEEP_DISABLED", "reason": "model metrics not measured (TBD)"}
    if model_retrieval["macro_f1"] <= model["macro_f1"]:
        return {"decision": "KEEP_DISABLED", "reason": "macro F1 did not improve"}
    if model_retrieval["invalid_output_rate"] > model["invalid_output_rate"]:
        return {"decision": "KEEP_DISABLED", "reason": "invalid-output rate increased"}
    return {"decision": "ADOPT", "reason": "macro F1 improved on the blind dataset without more invalid output"}


def load_examples(path: Optional[Path]) -> Optional[dict]:
    if path is None or not path.exists():
        return None
    out = {}
    for line in path.read_text().splitlines():
        row = json.loads(line)
        out[row["id"]] = row["examples"]
    return out


def run(dataset: Path = DEFAULT_DATASET, examples_path: Optional[Path] = None) -> dict:
    report: dict = {"experiment": "retrieval-augmented reply parsing (gated)", "adoption_rule": "ADR-004 §8",
                    "prompt_versions": {"model": reply_parser.PROMPT_VERSION, "model_retrieval": reply_parser.PROMPT_VERSION_FEWSHOT}}
    if not dataset.exists():
        report.update({"dataset": "TBD", "baseline": "TBD", "model": "TBD", "model_retrieval": "TBD",
                       "reason": f"blind dataset {dataset.name} does not exist yet"})
        report["adoption"] = adoption_decision(None, None, blind=False)
        return report
    rows = run_eval.load(dataset)
    report["dataset"] = rows[0]["dataset"]
    report["baseline"] = {"system": run_eval.BASELINE_VERSION, **run_eval.evaluate(rows, run_eval.keyword_baseline)}
    examples = load_examples(examples_path)
    if not reply_parser.model_configured():
        report["model"] = report["model_retrieval"] = "TBD"
        report["reason"] = "no GEMINI_API_KEY in the environment; no model was run"
        report["adoption"] = adoption_decision(None, None, blind=True)
        return report
    report["model"] = run_eval.evaluate(rows, run_eval.model_predict)
    if examples is None:
        report["model_retrieval"] = "TBD"
        report["reason"] = "no examples file exported from the backend store"
    else:
        by_text = {r["text"]: examples.get(r["id"], []) for r in rows}

        def predict(text: str) -> dict:
            try:
                result = reply_parser.parse_reply(text, examples=by_text.get(text) or None)
            except reply_parser.ModelError as e:
                return {"provider_error": str(e)}
            return {"suggestion": result.suggestion, "model_id": result.model_id}

        report["model_retrieval"] = run_eval.evaluate(rows, predict)
    model = report["model"] if isinstance(report["model"], dict) else None
    model_retrieval = report["model_retrieval"] if isinstance(report["model_retrieval"], dict) else None
    report["adoption"] = adoption_decision(model, model_retrieval, blind=True)
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--examples", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    report = run(args.dataset, args.examples)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(f"adoption: {report['adoption']['decision']} ({report['adoption']['reason']})")


if __name__ == "__main__":
    main()
