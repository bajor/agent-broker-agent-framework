#!/usr/bin/env python3
"""
Read the latest conversation log and display LLM queries with prompts/responses.
"""
import os
import json
import re
from datetime import datetime

# --- Configuration & Terminal Colors (Ghostty Optimized) ---
LOG_DIR = "conversation_logs"
BOLD = "\033[1m"
GREEN = "\033[32m"
PINK = "\033[38;5;211m"
YELLOW = "\033[33m"
CYAN = "\033[36m"
RED = "\033[31m"
DARK_GRAY = "\033[38;5;238m"
RESET = "\033[0m"

# UUID pattern for conversation log files
UUID_PATTERN = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jsonl$')

def get_latest_uuid_log():
    """Get the most recently modified UUID-based log file."""
    if not os.path.exists(LOG_DIR):
        return None

    files = []
    for f in os.listdir(LOG_DIR):
        if UUID_PATTERN.match(f):
            full_path = os.path.join(LOG_DIR, f)
            files.append(full_path)

    if not files:
        return None

    return max(files, key=os.path.getmtime)

def format_timestamp(ts_str):
    try:
        dt = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        return dt.strftime("%H:%M:%S")
    except:
        return ts_str[:19] if ts_str else "??:??:??"

def truncate(text, max_len=500):
    if not text:
        return ""
    if len(text) <= max_len:
        return text
    return text[:max_len - 3] + "..."

def is_llm_entry(entry):
    """Check if entry is an LLM query (has input_messages and output_response)."""
    return "input_messages" in entry and "output_response" in entry

def main():
    latest_file = get_latest_uuid_log()

    if not latest_file:
        print(f"{RED}Error: No UUID log files found in ./{LOG_DIR}{RESET}")
        print(f"{DARK_GRAY}Looking for files matching pattern: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.jsonl{RESET}")
        return

    conv_id = os.path.basename(latest_file).replace('.jsonl', '')

    print(f"\n{BOLD}{DARK_GRAY}󱩼 CONVERSATION LOG{RESET}")
    print(f"{DARK_GRAY}ID: {conv_id}{RESET}")
    print(f"{DARK_GRAY}{'━' * 80}{RESET}")

    try:
        entries = []
        llm_queries = []

        with open(latest_file, 'r') as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    entry = json.loads(line)
                    entries.append(entry)
                    if is_llm_entry(entry):
                        llm_queries.append(entry)
                except json.JSONDecodeError:
                    continue

        if not entries:
            print(f"{YELLOW}No log entries found.{RESET}")
            return

        print(f"{DARK_GRAY}Total entries: {len(entries)} | LLM queries: {len(llm_queries)}{RESET}")
        print(f"{DARK_GRAY}{'━' * 80}{RESET}\n")

        if not llm_queries:
            print(f"{YELLOW}No LLM queries found in this conversation.{RESET}")
            print(f"{DARK_GRAY}Showing recent log messages instead:{RESET}\n")
            for entry in entries[-10:]:
                ts = format_timestamp(entry.get("timestamp", ""))
                source = entry.get("source", "?")
                message = entry.get("message", "")
                print(f"{DARK_GRAY}{ts}{RESET} [{source}] {message}")
            return

        # Display LLM queries
        for i, entry in enumerate(llm_queries, 1):
            ts = format_timestamp(entry.get("timestamp", ""))
            model = entry.get("model_name", "unknown")
            latency = entry.get("latency_ms", "?")
            tokens_in = entry.get("input_tokens", "?")
            tokens_out = entry.get("output_tokens", "?")
            prompt = entry.get("input_messages", "")
            response = entry.get("output_response", "")

            print(f"{BOLD}{CYAN}╭{'─' * 78}╮{RESET}")
            print(f"{BOLD}{CYAN}│ LLM Query {i}/{len(llm_queries)}{RESET}")
            print(f"{DARK_GRAY}│ Model: {model}{RESET}")
            print(f"{DARK_GRAY}│ Time: {ts} | Latency: {latency}ms | Tokens: {tokens_in} in / {tokens_out} out{RESET}")
            print(f"{CYAN}╰{'─' * 78}╯{RESET}")

            print(f"\n{BOLD}{GREEN}➜ PROMPT{RESET}")
            print(f"{GREEN}{truncate(prompt, 1000)}{RESET}")

            print(f"\n{BOLD}{PINK}➜ RESPONSE{RESET}")
            print(f"{PINK}{truncate(response, 2000)}{RESET}")

            print(f"\n{DARK_GRAY}{'─' * 80}{RESET}\n")

        print(f"{DARK_GRAY}End of conversation ({len(llm_queries)} LLM queries){RESET}\n")

    except Exception as e:
        print(f"{RED}Error: {str(e)}{RESET}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main()
