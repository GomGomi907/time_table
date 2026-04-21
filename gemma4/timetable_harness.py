from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from gemma_cli import (
    DEFAULT_MODEL_ID,
    create_gemma4_model_and_processor,
    load_dotenv,
    split_gemma4_thought_and_answer,
)

ROOT = Path(__file__).resolve().parent
PROMPTS_DIR = ROOT / "prompts" / "timetable"
FIXTURES_PATH = ROOT / "fixtures" / "timetable_cases.json"
SCHEMA_PATH = ROOT / "schemas" / "timetable_normalized_schema.json"
RUNS_DIR = ROOT / "runs"

DAY_ORDER = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
DAY_SET = set(DAY_ORDER)
DAY_ALIASES = {
    "MONDAY": "MON",
    "MON": "MON",
    "월": "MON",
    "TUESDAY": "TUE",
    "TUE": "TUE",
    "TUES": "TUE",
    "화": "TUE",
    "WEDNESDAY": "WED",
    "WED": "WED",
    "수": "WED",
    "THURSDAY": "THU",
    "THU": "THU",
    "THUR": "THU",
    "목": "THU",
    "FRIDAY": "FRI",
    "FRI": "FRI",
    "금": "FRI",
    "SATURDAY": "SAT",
    "SAT": "SAT",
    "토": "SAT",
    "SUNDAY": "SUN",
    "SUN": "SUN",
    "일": "SUN",
}
TIME_RE = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")
DELIVERY_MODES = {"offline", "online", "hybrid", "unknown"}
WEEKS_VALUES = {"ALL", "ODD", "EVEN", "CUSTOM", "UNKNOWN"}
GENERIC_TITLES = {"lab", "tutorial", "discussion", "practice", "seminar"}


@dataclass
class StageResult:
    stage: str
    raw_response: str
    thought: str | None
    answer_text: str
    parsed_json: Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run multi-stage Gemma timetable normalization tests."
    )
    parser.add_argument(
        "--model",
        default=DEFAULT_MODEL_ID,
        help=f"Hugging Face model id. Default: {DEFAULT_MODEL_ID}",
    )
    parser.add_argument(
        "--device-map",
        default="auto",
        help='Transformers device_map value. Default: "auto".',
    )
    parser.add_argument(
        "--dtype",
        default="auto",
        help='Transformers dtype value. Default: "auto".',
    )
    parser.add_argument(
        "--analysis-max-new-tokens",
        type=int,
        default=768,
        help="Max tokens for the analysis stage.",
    )
    parser.add_argument(
        "--normalize-max-new-tokens",
        type=int,
        default=768,
        help="Max tokens for the normalization stage.",
    )
    parser.add_argument(
        "--validate-max-new-tokens",
        type=int,
        default=768,
        help="Max tokens for the validation stage.",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=0.0,
        help="Sampling temperature. Default 0 for deterministic normalization.",
    )
    parser.add_argument(
        "--top-p",
        type=float,
        default=0.95,
        help="Top-p sampling threshold used when temperature > 0.",
    )
    parser.add_argument(
        "--case",
        default="all",
        help='Case id to run. Default: "all".',
    )
    parser.add_argument(
        "--show-thoughts",
        action="store_true",
        help="Print analysis/validation thought traces for inspection.",
    )
    parser.add_argument(
        "--native-thinking",
        action="store_true",
        help="Enable Gemma's native thinking channel on analysis/validation stages.",
    )
    parser.add_argument(
        "--save-dir",
        default=str(RUNS_DIR),
        help="Directory where reports will be written.",
    )
    return parser.parse_args()


def build_generate_kwargs(max_new_tokens: int, temperature: float, top_p: float) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "max_new_tokens": max_new_tokens,
        "do_sample": temperature > 0,
    }
    if temperature > 0:
        kwargs["temperature"] = temperature
        kwargs["top_p"] = top_p
    return kwargs


def extract_json_object(text: str) -> Any:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)

    decoder = json.JSONDecoder()
    for index, char in enumerate(cleaned):
        if char not in "{[":
            continue
        try:
            value, _ = decoder.raw_decode(cleaned[index:])
            return value
        except json.JSONDecodeError:
            continue
    raise ValueError(f"Could not parse JSON from model output:\n{cleaned}")


def normalize_day(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip().upper()
    if not text:
        return None
    return DAY_ALIASES.get(text, text if text in DAY_SET else None)


def normalize_time(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    if TIME_RE.match(text):
        return text

    match = re.match(r"^(\d{1,2}):(\d{2})$", text)
    if match:
        hour = int(match.group(1))
        minute = int(match.group(2))
        if 0 <= hour <= 23:
            return f"{hour:02d}:{minute:02d}"
    return None


def normalize_delivery_mode(value: Any) -> str:
    if value is None:
        return "unknown"
    text = str(value).strip().lower()
    if text in DELIVERY_MODES:
        return text
    if text in {"remote", "zoom", "virtual"}:
        return "online"
    if text in {"in-person", "onsite"}:
        return "offline"
    return "unknown"


def normalize_weeks(value: Any) -> str:
    if value is None:
        return "UNKNOWN"
    text = str(value).strip().upper()
    if text in WEEKS_VALUES:
        return text
    if text in {"ODD_WEEKS", "ODD WEEK", "홀수주"}:
        return "ODD"
    if text in {"EVEN_WEEKS", "EVEN WEEK", "짝수주"}:
        return "EVEN"
    return "UNKNOWN"


def stable_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def looks_like_location(text: str | None) -> bool:
    if not text:
        return False
    return any(token in text for token in ("관", "호", "동", "실", "룸", "Room", "-"))


def dedupe_preserve_order(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def canonicalize_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("Final payload must be a JSON object.")

    entries_out: list[dict[str, Any]] = []
    for raw_entry in payload.get("entries", []):
        if not isinstance(raw_entry, dict):
            continue
        days_raw = raw_entry.get("days") or []
        if not isinstance(days_raw, list):
            days_raw = [days_raw]
        days = [normalize_day(day) for day in days_raw]
        days = [day for day in days if day is not None]
        days = dedupe_preserve_order(days)
        days.sort(key=DAY_ORDER.index)

        notes_raw = raw_entry.get("notes") or []
        if not isinstance(notes_raw, list):
            notes_raw = [notes_raw]
        notes = [str(note).strip() for note in notes_raw if str(note).strip()]

        entry = {
            "title": stable_text(raw_entry.get("title")) or "",
            "course_code": stable_text(raw_entry.get("course_code")),
            "section": stable_text(raw_entry.get("section")),
            "days": days,
            "start_time": normalize_time(raw_entry.get("start_time")),
            "end_time": normalize_time(raw_entry.get("end_time")),
            "location": stable_text(raw_entry.get("location")),
            "instructor": stable_text(raw_entry.get("instructor")),
            "delivery_mode": normalize_delivery_mode(raw_entry.get("delivery_mode")),
            "weeks": normalize_weeks(raw_entry.get("weeks")),
            "notes": notes,
            "source_text": stable_text(raw_entry.get("source_text")) or "",
        }

        if entry["section"] and looks_like_location(entry["section"]):
            if entry["location"] is None or entry["location"] in entry["section"]:
                entry["location"] = entry["section"]
                entry["section"] = None
        entries_out.append(entry)

    warnings_out: list[dict[str, str]] = []
    for raw_warning in payload.get("warnings", []):
        if not isinstance(raw_warning, dict):
            continue
        source_text = stable_text(raw_warning.get("source_text")) or ""
        reason = stable_text(raw_warning.get("reason")) or ""
        if not source_text and not reason:
            continue
        lowered_reason = reason.lower()
        if "missing" in lowered_reason and (
            "course_code" in lowered_reason or "section" in lowered_reason
        ):
            continue
        if "semester label is unknown" in lowered_reason:
            continue
        if "delivery mode" in lowered_reason and "zoom" in source_text.lower():
            continue
        warnings_out.append({"source_text": source_text, "reason": reason})

    entries_out.sort(
        key=lambda item: (
            DAY_ORDER.index(item["days"][0]) if item["days"] else 999,
            item["start_time"] or "99:99",
            item["title"],
        )
    )

    anchor_titles = [
        entry["title"]
        for entry in entries_out
        if entry["title"] and entry["title"].strip().lower() not in GENERIC_TITLES
    ]
    if len(anchor_titles) == 1:
        anchor = anchor_titles[0]
        for entry in entries_out:
            if entry["title"].strip().lower() in GENERIC_TITLES:
                entry["title"] = f"{anchor} {entry['title']}"

    return {
        "version": "1.0",
        "timezone": stable_text(payload.get("timezone")) or "Asia/Seoul",
        "semester_label": stable_text(payload.get("semester_label")),
        "entries": entries_out,
        "warnings": warnings_out,
    }


def validate_payload(payload: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    required_top_level = {"version", "timezone", "semester_label", "entries", "warnings"}
    actual_top_level = set(payload.keys())
    if actual_top_level != required_top_level:
        errors.append(
            f"Top-level keys mismatch. Expected {sorted(required_top_level)}, got {sorted(actual_top_level)}."
        )

    if payload.get("version") != "1.0":
        errors.append("version must be exactly '1.0'.")

    if not isinstance(payload.get("entries"), list):
        errors.append("entries must be a list.")
    else:
        for index, entry in enumerate(payload["entries"]):
            if not isinstance(entry, dict):
                errors.append(f"entries[{index}] must be an object.")
                continue
            required_entry_keys = {
                "title",
                "course_code",
                "section",
                "days",
                "start_time",
                "end_time",
                "location",
                "instructor",
                "delivery_mode",
                "weeks",
                "notes",
                "source_text",
            }
            if set(entry.keys()) != required_entry_keys:
                errors.append(
                    f"entries[{index}] keys mismatch. Expected {sorted(required_entry_keys)}, got {sorted(entry.keys())}."
                )

            if not entry["title"]:
                errors.append(f"entries[{index}] title must be non-empty.")

            if not isinstance(entry["days"], list) or not entry["days"]:
                errors.append(f"entries[{index}] days must be a non-empty list.")
            else:
                invalid_days = [day for day in entry["days"] if day not in DAY_SET]
                if invalid_days:
                    errors.append(f"entries[{index}] has invalid days: {invalid_days}.")

            for field in ("start_time", "end_time"):
                value = entry[field]
                if value is not None and not TIME_RE.match(value):
                    errors.append(f"entries[{index}] {field} must be HH:MM or null.")

            if (
                entry["start_time"] is not None
                and entry["end_time"] is not None
                and entry["start_time"] >= entry["end_time"]
            ):
                errors.append(f"entries[{index}] start_time must be before end_time.")

            if entry["delivery_mode"] not in DELIVERY_MODES:
                errors.append(f"entries[{index}] delivery_mode is invalid.")

            if entry["weeks"] not in WEEKS_VALUES:
                errors.append(f"entries[{index}] weeks is invalid.")

            if not isinstance(entry["notes"], list):
                errors.append(f"entries[{index}] notes must be a list.")

            if not entry["source_text"]:
                errors.append(f"entries[{index}] source_text must be non-empty.")

    if not isinstance(payload.get("warnings"), list):
        errors.append("warnings must be a list.")
    else:
        for index, warning in enumerate(payload["warnings"]):
            if not isinstance(warning, dict):
                errors.append(f"warnings[{index}] must be an object.")
                continue
            if set(warning.keys()) != {"source_text", "reason"}:
                errors.append(f"warnings[{index}] must only contain source_text and reason.")
            if not warning.get("reason"):
                errors.append(f"warnings[{index}] reason must be non-empty.")

    return errors


def partial_match(entry: dict[str, Any], expected: dict[str, Any]) -> bool:
    for key, expected_value in expected.items():
        if entry.get(key) != expected_value:
            return False
    return True


def evaluate_expectations(payload: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    entries = payload["entries"]
    warnings = payload["warnings"]

    if "entry_count" in expected and len(entries) != expected["entry_count"]:
        errors.append(
            f"Expected {expected['entry_count']} entries, got {len(entries)}."
        )

    if "warnings_min" in expected and len(warnings) < expected["warnings_min"]:
        errors.append(
            f"Expected at least {expected['warnings_min']} warnings, got {len(warnings)}."
        )

    if "warnings_max" in expected and len(warnings) > expected["warnings_max"]:
        errors.append(
            f"Expected at most {expected['warnings_max']} warnings, got {len(warnings)}."
        )

    for required_entry in expected.get("required_entries", []):
        if not any(partial_match(entry, required_entry) for entry in entries):
            errors.append(f"Missing expected entry fragment: {required_entry}.")

    return errors


def load_prompt(name: str) -> str:
    return (PROMPTS_DIR / name).read_text(encoding="utf-8").strip()


def build_messages(system_prompt: str, user_prompt: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def generate_stage(
    processor: Any,
    model: Any,
    *,
    stage: str,
    system_prompt: str,
    user_prompt: str,
    max_new_tokens: int,
    temperature: float,
    top_p: float,
    enable_thinking: bool,
) -> StageResult:
    messages = build_messages(system_prompt, user_prompt)
    prompt_text = processor.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=enable_thinking,
    )
    inputs = processor(text=prompt_text, return_tensors="pt").to(model.device)
    input_len = inputs["input_ids"].shape[-1]
    outputs = model.generate(
        **inputs,
        **build_generate_kwargs(max_new_tokens, temperature, top_p),
    )
    raw_response = processor.decode(outputs[0][input_len:], skip_special_tokens=False)
    thought, answer_text = split_gemma4_thought_and_answer(raw_response)
    parsed_json = extract_json_object(answer_text)
    return StageResult(
        stage=stage,
        raw_response=raw_response,
        thought=thought,
        answer_text=answer_text,
        parsed_json=parsed_json,
    )


def run_case(
    processor: Any,
    model: Any,
    case: dict[str, Any],
    schema_text: str,
    prompts: dict[str, str],
    args: argparse.Namespace,
) -> dict[str, Any]:
    raw_input = case["input"]
    stage_results: dict[str, Any] = {}

    try:
        analysis_user = (
            "Raw timetable text:\n"
            f"{raw_input}\n\n"
            "Return JSON only."
        )
        analysis = generate_stage(
            processor,
            model,
            stage="analysis",
            system_prompt=prompts["analysis_system"],
            user_prompt=analysis_user,
            max_new_tokens=args.analysis_max_new_tokens,
            temperature=args.temperature,
            top_p=args.top_p,
            enable_thinking=args.native_thinking,
        )
        stage_results["analysis"] = asdict(analysis)

        normalize_user = (
            "Raw timetable text:\n"
            f"{raw_input}\n\n"
            "Analysis JSON:\n"
            f"{json.dumps(analysis.parsed_json, ensure_ascii=False, indent=2)}\n\n"
            "Target schema:\n"
            f"{schema_text}\n"
        )
        normalized = generate_stage(
            processor,
            model,
            stage="normalize",
            system_prompt=prompts["normalize_system"],
            user_prompt=normalize_user,
            max_new_tokens=args.normalize_max_new_tokens,
            temperature=args.temperature,
            top_p=args.top_p,
            enable_thinking=False,
        )
        stage_results["normalize"] = asdict(normalized)

        validate_user = (
            "Raw timetable text:\n"
            f"{raw_input}\n\n"
            "Analysis JSON:\n"
            f"{json.dumps(analysis.parsed_json, ensure_ascii=False, indent=2)}\n\n"
            "Normalized draft:\n"
            f"{json.dumps(normalized.parsed_json, ensure_ascii=False, indent=2)}\n\n"
            "Target schema:\n"
            f"{schema_text}\n"
        )
        validated = generate_stage(
            processor,
            model,
            stage="validate",
            system_prompt=prompts["validate_system"],
            user_prompt=validate_user,
            max_new_tokens=args.validate_max_new_tokens,
            temperature=args.temperature,
            top_p=args.top_p,
            enable_thinking=args.native_thinking,
        )
        stage_results["validate"] = asdict(validated)

        final_payload = canonicalize_payload(validated.parsed_json)
        schema_errors = validate_payload(final_payload)
        expectation_errors = evaluate_expectations(final_payload, case.get("expected", {}))
        all_errors = schema_errors + expectation_errors

        return {
            "id": case["id"],
            "description": case.get("description"),
            "status": "PASS" if not all_errors else "FAIL",
            "errors": all_errors,
            "final_payload": final_payload,
            "stages": stage_results,
        }
    except Exception as exc:
        return {
            "id": case["id"],
            "description": case.get("description"),
            "status": "FAIL",
            "errors": [str(exc)],
            "final_payload": None,
            "stages": stage_results,
        }


def select_cases(all_cases: list[dict[str, Any]], case_id: str) -> list[dict[str, Any]]:
    if case_id == "all":
        return all_cases
    return [case for case in all_cases if case["id"] == case_id]


def main() -> int:
    load_dotenv(ROOT / ".env")
    args = parse_args()

    all_cases = json.loads(FIXTURES_PATH.read_text(encoding="utf-8"))
    selected_cases = select_cases(all_cases, args.case)
    if not selected_cases:
        raise SystemExit(f"No matching case found for --case={args.case}")

    prompts = {
        "analysis_system": load_prompt("analysis_system.txt"),
        "normalize_system": load_prompt("normalize_system.txt"),
        "validate_system": load_prompt("validate_system.txt"),
    }
    schema_text = SCHEMA_PATH.read_text(encoding="utf-8")

    loader_args = SimpleNamespace(
        model=args.model,
        device_map=args.device_map,
        dtype=args.dtype,
    )
    processor, model = create_gemma4_model_and_processor(loader_args)

    run_timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    save_dir = Path(args.save_dir) / f"timetable-{run_timestamp}"
    save_dir.mkdir(parents=True, exist_ok=True)

    results: list[dict[str, Any]] = []
    for case in selected_cases:
        result = run_case(processor, model, case, schema_text, prompts, args)
        results.append(result)

        print(f"[{result['status']}] {result['id']}")
        if result["final_payload"] is not None:
            print(json.dumps(result["final_payload"], ensure_ascii=False, indent=2))
        if result["errors"]:
            print("Errors:")
            for error in result["errors"]:
                print(f"- {error}")
        if args.show_thoughts:
            for stage_name in ("analysis", "validate"):
                thought = result["stages"][stage_name]["thought"]
                if thought:
                    print(f"\n[{result['id']}] {stage_name} thought:\n{thought}\n")
        print()

    summary = {
        "model": args.model,
        "timestamp": run_timestamp,
        "case_count": len(results),
        "pass_count": sum(result["status"] == "PASS" for result in results),
        "results": results,
    }
    report_path = save_dir / "report.json"
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved report to {report_path}")

    return 0 if summary["pass_count"] == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
