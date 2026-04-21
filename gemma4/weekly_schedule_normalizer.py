from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from gemma_cli import create_pipeline, load_dotenv, normalize_output, split_gemma4_thought_and_answer

ROOT = Path(__file__).resolve().parent
DAY_VALUES = {
    "MONDAY",
    "TUESDAY",
    "WEDNESDAY",
    "THURSDAY",
    "FRIDAY",
    "SATURDAY",
    "SUNDAY",
}
DAY_ALIASES = {
    "MON": "MONDAY",
    "MONDAY": "MONDAY",
    "월": "MONDAY",
    "TUE": "TUESDAY",
    "TUESDAY": "TUESDAY",
    "화": "TUESDAY",
    "WED": "WEDNESDAY",
    "WEDNESDAY": "WEDNESDAY",
    "수": "WEDNESDAY",
    "THU": "THURSDAY",
    "THURSDAY": "THURSDAY",
    "목": "THURSDAY",
    "FRI": "FRIDAY",
    "FRIDAY": "FRIDAY",
    "금": "FRIDAY",
    "SAT": "SATURDAY",
    "SATURDAY": "SATURDAY",
    "토": "SATURDAY",
    "SUN": "SUNDAY",
    "SUNDAY": "SUNDAY",
    "일": "SUNDAY",
}
TIME_RE = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")
CATEGORY_VALUES = {"WORK", "LIFE", "TRANSIT", "GROWTH", "HOBBY", "SLEEP", "ADMIN"}
CATEGORY_ALIASES = {
    "WORK": "WORK",
    "JOB": "WORK",
    "LIFE": "LIFE",
    "PERSONAL": "LIFE",
    "TRANSIT": "TRANSIT",
    "COMMUTE": "TRANSIT",
    "GROWTH": "GROWTH",
    "STUDY": "GROWTH",
    "LEARNING": "GROWTH",
    "HOBBY": "HOBBY",
    "FUN": "HOBBY",
    "SLEEP": "SLEEP",
    "REST": "SLEEP",
    "ADMIN": "ADMIN",
}
SYSTEM_PROMPT = """You convert messy weekly schedule text into strict JSON.

Return exactly one JSON object with this shape:
{
  "blocks": [
    {
      "dayOfWeek": "MONDAY",
      "startTime": "09:00",
      "endTime": "10:00",
      "activity": "string",
      "category": "WORK",
      "note": "string or null"
    }
  ],
  "warnings": ["string"]
}

Rules:
- dayOfWeek must be one of MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.
- category must be one of WORK, LIFE, TRANSIT, GROWTH, HOBBY, SLEEP, ADMIN.
- startTime and endTime must be HH:MM 24-hour format.
- Split blocks only when the source clearly implies separate schedule blocks.
- If day or time is missing or ambiguous, do not guess. Put a warning and omit that block.
- Prefer faithful normalization over creativity.
- Output JSON only. No markdown. No explanation.
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Normalize weekly schedule text with local Gemma.")
    parser.add_argument("--input-file", required=True, help="UTF-8 text file containing raw schedule text.")
    parser.add_argument("--model", default=None, help="Optional override for the Gemma model id.")
    parser.add_argument("--device-map", default="auto")
    parser.add_argument("--dtype", default="auto")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--top-p", type=float, default=0.95)
    return parser.parse_args()


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
    return DAY_ALIASES.get(text, text if text in DAY_VALUES else None)


def normalize_time(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if TIME_RE.match(text):
        return text

    match = re.match(r"^(\d{1,2}):(\d{2})$", text)
    if not match:
        return None

    hour = int(match.group(1))
    minute = int(match.group(2))
    if hour < 0 or hour > 23:
        return None
    return f"{hour:02d}:{minute:02d}"


def normalize_category(value: Any) -> str:
    if value is None:
        return "LIFE"
    text = str(value).strip().upper()
    return CATEGORY_ALIASES.get(text, "LIFE")


def stable_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def canonicalize_payload(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("Gemma output must be a JSON object.")

    raw_blocks = payload.get("blocks") or []
    raw_warnings = payload.get("warnings") or []

    blocks: list[dict[str, Any]] = []
    warnings: list[str] = []

    if isinstance(raw_warnings, list):
        warnings = [str(item).strip() for item in raw_warnings if str(item).strip()]

    for raw_block in raw_blocks:
        if not isinstance(raw_block, dict):
            continue

        day = normalize_day(raw_block.get("dayOfWeek"))
        start_time = normalize_time(raw_block.get("startTime"))
        end_time = normalize_time(raw_block.get("endTime"))
        activity = stable_text(raw_block.get("activity"))
        note = stable_text(raw_block.get("note"))

        if day is None or start_time is None or end_time is None or activity is None:
            snippet = stable_text(raw_block.get("activity")) or json.dumps(raw_block, ensure_ascii=False)
            warnings.append(f"Skipped ambiguous block: {snippet}")
            continue

        blocks.append(
            {
                "dayOfWeek": day,
                "startTime": start_time,
                "endTime": end_time,
                "activity": activity,
                "category": normalize_category(raw_block.get("category")),
                "note": note,
            }
        )

    return {
        "blocks": blocks,
        "warnings": warnings,
    }


def build_generate_kwargs(args: argparse.Namespace) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "max_new_tokens": args.max_new_tokens,
        "do_sample": args.temperature > 0,
    }
    if args.temperature > 0:
        kwargs["temperature"] = args.temperature
        kwargs["top_p"] = args.top_p
    return kwargs


def main() -> int:
    load_dotenv(ROOT / ".env")
    args = parse_args()
    raw_text = Path(args.input_file).read_text(encoding="utf-8").strip()
    if not raw_text:
        raise SystemExit("Input schedule text is empty.")

    loader_args = SimpleNamespace(
        model=args.model,
        task="any-to-any",
        device_map=args.device_map,
        dtype=args.dtype,
    )
    if loader_args.model is None:
        loader_args.model = "google/gemma-4-E2B-it"

    pipe = create_pipeline(loader_args)
    messages = [
        {
            "role": "system",
            "content": [
                {"type": "text", "text": SYSTEM_PROMPT},
            ],
        },
        {
            "role": "user",
            "content": [
                {"type": "text", "text": raw_text},
            ],
        },
    ]

    raw_output = pipe(
        messages,
        return_full_text=False,
        generate_kwargs=build_generate_kwargs(args),
    )
    raw_response = normalize_output(raw_output)
    _, answer_text = split_gemma4_thought_and_answer(raw_response)
    parsed = extract_json_object(answer_text)
    canonicalized = canonicalize_payload(parsed)
    print(json.dumps(canonicalized, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
