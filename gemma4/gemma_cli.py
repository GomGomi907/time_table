from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

DEFAULT_MODEL_ID = "google/gemma-4-E2B-it"
DEFAULT_TASK = "any-to-any"


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def env_str(name: str, default: str | None = None) -> str | None:
    value = os.getenv(name)
    if value is None or value == "":
        return default
    return value


def env_int(name: str, default: int) -> int:
    value = env_str(name)
    if value is None:
        return default
    return int(value)


def env_float(name: str, default: float) -> float:
    value = env_str(name)
    if value is None:
        return default
    return float(value)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Gemma CLI boilerplate based on Google AI Gemma + Transformers docs."
    )
    parser.add_argument(
        "prompt",
        nargs="?",
        help="Single prompt to send. If omitted, stdin is used or interactive mode starts with --interactive.",
    )
    parser.add_argument(
        "--model",
        default=env_str("GEMMA_MODEL_ID", DEFAULT_MODEL_ID),
        help=f"Hugging Face model id. Default: env GEMMA_MODEL_ID or {DEFAULT_MODEL_ID}",
    )
    parser.add_argument(
        "--task",
        default=env_str("GEMMA_TASK", DEFAULT_TASK),
        help=f"Transformers pipeline task. Default: env GEMMA_TASK or {DEFAULT_TASK}",
    )
    parser.add_argument(
        "--system",
        default=env_str("GEMMA_SYSTEM_PROMPT"),
        help="Optional system prompt.",
    )
    parser.add_argument(
        "--max-new-tokens",
        type=int,
        default=env_int("GEMMA_MAX_NEW_TOKENS", 256),
        help="Maximum number of tokens to generate.",
    )
    parser.add_argument(
        "--temperature",
        type=float,
        default=env_float("GEMMA_TEMPERATURE", 0.7),
        help="Sampling temperature. Set 0 for greedy decoding.",
    )
    parser.add_argument(
        "--top-p",
        type=float,
        default=env_float("GEMMA_TOP_P", 0.95),
        help="Top-p sampling threshold.",
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
        "--interactive",
        action="store_true",
        help="Start a multi-turn chat session.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print raw pipeline output as JSON.",
    )
    return parser


def lazy_import_transformers():
    try:
        from transformers import pipeline
    except ImportError as exc:
        raise SystemExit(
            "transformers is not installed. Run `.\\scripts\\setup.ps1` first."
        ) from exc
    return pipeline


def extract_text(value: Any) -> str:
    if isinstance(value, str):
        return value

    if isinstance(value, list):
        parts = [extract_text(item) for item in value]
        return "".join(part for part in parts if part)

    if isinstance(value, dict):
        if "text" in value and isinstance(value["text"], str):
            return value["text"]
        if "generated_text" in value:
            return extract_text(value["generated_text"])
        if "content" in value:
            return extract_text(value["content"])

    return ""


def normalize_output(raw_output: Any) -> str:
    if isinstance(raw_output, list) and raw_output:
        first_item = raw_output[0]
        text = extract_text(first_item)
        if text:
            return text
    text = extract_text(raw_output)
    return text.strip()


def build_messages(system_prompt: str | None, user_prompt: str) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []

    if system_prompt:
        messages.append(
            {
                "role": "system",
                "content": system_prompt,
            }
        )

    messages.append(
        {
            "role": "user",
            "content": user_prompt,
        }
    )
    return messages


def build_generate_kwargs(args: argparse.Namespace) -> dict[str, Any]:
    do_sample = args.temperature > 0
    kwargs: dict[str, Any] = {
        "max_new_tokens": args.max_new_tokens,
        "do_sample": do_sample,
    }
    if do_sample:
        kwargs["temperature"] = args.temperature
        kwargs["top_p"] = args.top_p
    return kwargs


def get_hf_token() -> str | None:
    hf_token = env_str("HF_TOKEN") or env_str("HUGGING_FACE_HUB_TOKEN")
    if not hf_token:
        try:
            from huggingface_hub import get_token
        except ImportError:
            get_token = None
        if get_token is not None:
            hf_token = get_token()
    return hf_token


def is_gemma4_manual_mode(args: argparse.Namespace) -> bool:
    model_name = args.model.lower()
    return args.task == "any-to-any" and "gemma-4" in model_name


def create_pipeline(args: argparse.Namespace):
    pipeline_factory = lazy_import_transformers()

    hf_token = get_hf_token()
    pipeline_kwargs: dict[str, Any] = {
        "task": args.task,
        "model": args.model,
        "device_map": args.device_map,
        "dtype": args.dtype,
    }
    if hf_token:
        pipeline_kwargs["token"] = hf_token

    return pipeline_factory(**pipeline_kwargs)


def create_gemma4_model_and_processor(args: argparse.Namespace):
    try:
        from transformers import AutoModelForCausalLM, AutoProcessor
    except ImportError as exc:
        raise SystemExit(
            "transformers is not installed. Run `.\\scripts\\setup.ps1` first."
        ) from exc

    hf_token = get_hf_token()
    kwargs: dict[str, Any] = {
        "trust_remote_code": True,
    }
    if hf_token:
        kwargs["token"] = hf_token

    processor = AutoProcessor.from_pretrained(args.model, **kwargs)
    model = AutoModelForCausalLM.from_pretrained(
        args.model,
        device_map=args.device_map,
        dtype=args.dtype,
        **kwargs,
    )
    return processor, model


def normalize_parsed_response(parsed: Any) -> str:
    if isinstance(parsed, str):
        return parsed.strip()

    text = extract_text(parsed)
    if text:
        return text.strip()

    return json.dumps(parsed, ensure_ascii=False, indent=2)


def generate_with_gemma4(
    args: argparse.Namespace,
    processor: Any,
    model: Any,
    messages: list[dict[str, Any]],
    *,
    enable_thinking: bool = False,
) -> tuple[str, Any]:
    text = processor.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=enable_thinking,
    )
    inputs = processor(text=text, return_tensors="pt").to(model.device)
    input_len = inputs["input_ids"].shape[-1]
    outputs = model.generate(**inputs, **build_generate_kwargs(args))
    response = processor.decode(outputs[0][input_len:], skip_special_tokens=False)

    if hasattr(processor, "parse_response"):
        parsed = processor.parse_response(response)
        return normalize_parsed_response(parsed), parsed

    return response.strip(), response


def split_gemma4_thought_and_answer(response: str) -> tuple[str | None, str]:
    thought: str | None = None
    answer = response.strip()

    if "<channel|>" in answer:
        thought_block, answer = answer.split("<channel|>", 1)
        if thought_block.startswith("<|channel>thought"):
            thought = thought_block.removeprefix("<|channel>thought").strip()

    answer = answer.replace("<turn|>", "").strip()
    return thought, answer


def explain_load_error(exc: Exception, model_id: str) -> str:
    message = str(exc)
    lowered = message.lower()

    if "gated repo" in lowered or "401 client error" in lowered:
        return (
            f"Failed to load gated model `{model_id}`.\n"
            "Make sure you have accepted access on Hugging Face and are logged in with a token that can access the model.\n"
            f"Model page: https://huggingface.co/{model_id}\n"
            "You can set `HF_TOKEN` in `.env`, export `HUGGING_FACE_HUB_TOKEN`, or use an existing Hugging Face login."
        )

    return message


def run_single_prompt(args: argparse.Namespace) -> int:
    prompt = args.prompt
    if prompt is None and not sys.stdin.isatty():
        prompt = sys.stdin.read().strip()

    if not prompt:
        raise SystemExit("No prompt provided. Pass a prompt or use --interactive.")

    messages = build_messages(args.system, prompt)
    if is_gemma4_manual_mode(args):
        processor, model = create_gemma4_model_and_processor(args)
        text_output, raw_output = generate_with_gemma4(args, processor, model, messages)
        if args.json:
            print(json.dumps(raw_output, ensure_ascii=False, indent=2))
            return 0
        print(text_output)
        return 0

    pipe = create_pipeline(args)
    raw_output = pipe(
        messages,
        return_full_text=False,
        **build_generate_kwargs(args),
    )

    if args.json:
        print(json.dumps(raw_output, ensure_ascii=False, indent=2))
        return 0

    print(normalize_output(raw_output))
    return 0


def run_interactive(args: argparse.Namespace) -> int:
    messages: list[dict[str, Any]] = []
    pipe = None
    processor = None
    model = None

    if args.system:
        messages.append(
            {
                "role": "system",
                "content": args.system,
            }
        )

    print("Interactive Gemma chat. Type /exit to quit, /clear to reset history.")

    if is_gemma4_manual_mode(args):
        processor, model = create_gemma4_model_and_processor(args)
    else:
        pipe = create_pipeline(args)

    while True:
        try:
            user_text = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0

        if not user_text:
            continue
        if user_text in {"/exit", "/quit"}:
            return 0
        if user_text == "/clear":
            messages = messages[:1] if args.system else []
            print("history cleared")
            continue

        messages.append(
            {
                "role": "user",
                "content": user_text,
            }
        )

        if processor is not None and model is not None:
            answer, _ = generate_with_gemma4(args, processor, model, messages)
        else:
            raw_output = pipe(
                messages,
                return_full_text=False,
                **build_generate_kwargs(args),
            )
            answer = normalize_output(raw_output)
        print(f"gemma> {answer}")

        messages.append(
            {
                "role": "assistant",
                "content": answer,
            }
        )


def main() -> int:
    load_dotenv(Path(".env"))
    parser = build_parser()
    args = parser.parse_args()

    try:
        if args.interactive:
            return run_interactive(args)
        return run_single_prompt(args)
    except OSError as exc:
        raise SystemExit(explain_load_error(exc, args.model)) from exc


if __name__ == "__main__":
    raise SystemExit(main())
