"""
Backfill daily logs from historical Claude Code conversation transcripts.

Reads all .jsonl session files for this project, extracts user/assistant
exchanges, and runs each through the flush agent to produce daily log entries.

Usage:
    uv run python scripts/backfill.py [--dry-run] [--skip-existing]
"""

from __future__ import annotations

import os
os.environ["CLAUDE_INVOKED_BY"] = "memory_flush"

import asyncio
import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DAILY_DIR = ROOT / "daily"
SCRIPTS_DIR = ROOT / "scripts"
LOG_FILE = SCRIPTS_DIR / "backfill.log"

# Claude Code stores transcripts here
TRANSCRIPTS_DIR = Path.home() / ".claude" / "projects" / "D--Vuduydu-PersonalProjects-AudioCasting"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[
        logging.FileHandler(str(LOG_FILE), encoding="utf-8"),
        logging.StreamHandler(sys.stdout),
    ],
)


def extract_conversation(jsonl_path: Path) -> tuple[str, str, int]:
    """Extract user/assistant text from a JSONL transcript.

    Returns (date_str, conversation_text, message_count).
    date_str is YYYY-MM-DD from the first timestamp in the file.
    """
    messages: list[str] = []
    first_timestamp: str | None = None
    msg_count = 0

    with open(jsonl_path, encoding="utf-8") as f:
        for line in f:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue

            # Track earliest timestamp for dating
            ts = obj.get("timestamp")
            if ts and not first_timestamp:
                first_timestamp = ts

            msg_type = obj.get("type")

            if msg_type == "user":
                content = obj.get("message", {}).get("content", "")
                if isinstance(content, str) and content.strip():
                    # Skip system-reminder-only messages
                    stripped = content.strip()
                    if stripped.startswith("<system-reminder>") and stripped.endswith("</system-reminder>"):
                        continue
                    messages.append(f"**User:** {content.strip()}")
                    msg_count += 1
                elif isinstance(content, list):
                    # Array content - extract text parts
                    text_parts = []
                    for block in content:
                        if isinstance(block, dict) and block.get("type") == "text":
                            text = block.get("text", "").strip()
                            if text and not (text.startswith("<system-reminder>") and text.endswith("</system-reminder>")):
                                text_parts.append(text)
                    if text_parts:
                        messages.append(f"**User:** {' '.join(text_parts)}")
                        msg_count += 1

            elif msg_type == "assistant":
                content_blocks = obj.get("message", {}).get("content", [])
                text_parts = []
                tool_names = []
                for block in content_blocks:
                    if isinstance(block, dict):
                        if block.get("type") == "text":
                            text = block.get("text", "").strip()
                            if text:
                                text_parts.append(text)
                        elif block.get("type") == "tool_use":
                            tool_names.append(block.get("name", "unknown"))

                parts = []
                if text_parts:
                    parts.append(" ".join(text_parts))
                if tool_names:
                    parts.append(f"[Used tools: {', '.join(tool_names)}]")
                if parts:
                    messages.append(f"**Assistant:** {' '.join(parts)}")
                    msg_count += 1

    # Determine date
    if first_timestamp:
        try:
            dt = datetime.fromisoformat(first_timestamp.replace("Z", "+00:00"))
            date_str = dt.astimezone().strftime("%Y-%m-%d")
        except (ValueError, OSError):
            date_str = datetime.fromtimestamp(jsonl_path.stat().st_mtime).strftime("%Y-%m-%d")
    else:
        date_str = datetime.fromtimestamp(jsonl_path.stat().st_mtime).strftime("%Y-%m-%d")

    # Truncate very long conversations to ~30k chars to stay within context limits
    conversation = "\n\n".join(messages)
    if len(conversation) > 30000:
        conversation = conversation[:30000] + "\n\n[... truncated ...]"

    return date_str, conversation, msg_count


async def flush_conversation(conversation: str, session_id: str) -> str:
    """Run the flush agent on a conversation transcript."""
    from claude_agent_sdk import (
        AssistantMessage,
        ClaudeAgentOptions,
        ResultMessage,
        TextBlock,
        query,
    )

    prompt = f"""Review the conversation context below and respond with a concise summary
of important items that should be preserved in the daily log.
Do NOT use any tools — just return plain text.

Format your response as a structured daily log entry with these sections:

**Context:** [One line about what the user was working on]

**Key Exchanges:**
- [Important Q&A or discussions]

**Decisions Made:**
- [Any decisions with rationale]

**Lessons Learned:**
- [Gotchas, patterns, or insights discovered]

**Action Items:**
- [Follow-ups or TODOs mentioned]

Skip anything that is:
- Routine tool calls or file reads
- Content that's trivial or obvious
- Trivial back-and-forth or clarification exchanges

Only include sections that have actual content. If nothing is worth saving,
respond with exactly: FLUSH_OK

## Conversation Context

{conversation}"""

    response = ""
    try:
        async for message in query(
            prompt=prompt,
            options=ClaudeAgentOptions(
                cwd=str(ROOT),
                allowed_tools=[],
                max_turns=2,
            ),
        ):
            if isinstance(message, AssistantMessage):
                for block in message.content:
                    if isinstance(block, TextBlock):
                        response += block.text
            elif isinstance(message, ResultMessage):
                pass
    except Exception as e:
        import traceback
        logging.error("Agent SDK error for %s: %s\n%s", session_id, e, traceback.format_exc())
        response = f"FLUSH_ERROR: {type(e).__name__}: {e}"

    return response


def write_daily_log(date_str: str, content: str, session_id: str) -> None:
    """Write or append to a daily log file."""
    log_path = DAILY_DIR / f"{date_str}.md"
    DAILY_DIR.mkdir(parents=True, exist_ok=True)

    if not log_path.exists():
        log_path.write_text(
            f"# Daily Log: {date_str}\n\n## Sessions\n\n## Memory Maintenance\n\n",
            encoding="utf-8",
        )

    entry = f"### Session ({session_id[:8]})\n\n{content}\n\n"
    with open(log_path, "a", encoding="utf-8") as f:
        f.write(entry)


async def main():
    dry_run = "--dry-run" in sys.argv
    skip_existing = "--skip-existing" in sys.argv

    if not TRANSCRIPTS_DIR.exists():
        logging.error("Transcripts directory not found: %s", TRANSCRIPTS_DIR)
        sys.exit(1)

    # Find all session JSONL files (exclude subagent files)
    jsonl_files = sorted(TRANSCRIPTS_DIR.glob("*.jsonl"), key=lambda p: p.stat().st_mtime)
    logging.info("Found %d session transcripts", len(jsonl_files))

    # Group sessions by date
    sessions_by_date: dict[str, list[tuple[Path, str, int]]] = {}
    for jsonl_path in jsonl_files:
        date_str, conversation, msg_count = extract_conversation(jsonl_path)
        if msg_count < 2:
            logging.info("Skipping %s (%s): too few messages (%d)", jsonl_path.name, date_str, msg_count)
            continue
        sessions_by_date.setdefault(date_str, []).append((jsonl_path, conversation, msg_count))
        logging.info("  %s: %s (%d messages, %d chars)", date_str, jsonl_path.stem[:8], msg_count, len(conversation))

    if skip_existing:
        existing = {p.stem for p in DAILY_DIR.glob("*.md")}
        sessions_by_date = {d: s for d, s in sessions_by_date.items() if d not in existing}
        logging.info("After filtering existing: %d dates to process", len(sessions_by_date))

    if dry_run:
        logging.info("=== DRY RUN - no files will be written ===")
        for date_str, sessions in sorted(sessions_by_date.items()):
            for path, conv, count in sessions:
                logging.info("  Would process: %s %s (%d msgs)", date_str, path.stem[:8], count)
        return

    # Process each date's sessions
    total = sum(len(s) for s in sessions_by_date.values())
    processed = 0

    for date_str in sorted(sessions_by_date.keys()):
        sessions = sessions_by_date[date_str]
        for jsonl_path, conversation, msg_count in sessions:
            processed += 1
            session_id = jsonl_path.stem
            logging.info("[%d/%d] Processing %s session %s (%d msgs)...",
                         processed, total, date_str, session_id[:8], msg_count)

            result = await flush_conversation(conversation, session_id)

            if "FLUSH_OK" in result:
                logging.info("  -> FLUSH_OK (nothing worth saving)")
                write_daily_log(date_str, "FLUSH_OK - Nothing worth saving from this session", session_id)
            elif "FLUSH_ERROR" in result:
                logging.error("  -> %s", result)
                write_daily_log(date_str, result, session_id)
            else:
                logging.info("  -> Saved (%d chars)", len(result))
                write_daily_log(date_str, result, session_id)

    logging.info("Backfill complete! Processed %d sessions across %d dates.",
                 processed, len(sessions_by_date))
    logging.info("Daily logs written to: %s", DAILY_DIR)
    logging.info("Run 'uv run python scripts/compile.py' to compile into knowledge articles.")


if __name__ == "__main__":
    asyncio.run(main())
