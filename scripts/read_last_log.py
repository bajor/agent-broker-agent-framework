#!/usr/bin/env python3
"""
Read conversation logs for a given UUID from all agents and display them
combined by timestamp order.
"""
import os
import sys
import json
import re
from datetime import datetime
from collections import defaultdict

# --- Configuration & Terminal Colors (Ghostty Optimized) ---
AGENT_LOGS_DIR = "agent_logs"
CONVERSATION_LOGS_DIR = "conversation_logs"

BOLD = "\033[1m"
GREEN = "\033[32m"
PINK = "\033[38;5;211m"
YELLOW = "\033[33m"
CYAN = "\033[36m"
RED = "\033[31m"
BLUE = "\033[34m"
MAGENTA = "\033[35m"
DARK_GRAY = "\033[38;5;238m"
WHITE = "\033[97m"
RESET = "\033[0m"

# Agent-specific colors for visual distinction
AGENT_COLORS = {
    "preprocessor": "\033[38;5;39m",   # Light blue
    "codegen": "\033[38;5;208m",       # Orange
    "explainer": "\033[38;5;141m",     # Purple
    "refiner": "\033[38;5;84m",        # Green
}

AGENT_ICONS = {
    "preprocessor": "",
    "codegen": "",
    "explainer": "",
    "refiner": "",
}

# UUID pattern for log files
UUID_PATTERN = re.compile(r'^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})')


def get_agent_color(agent_name: str) -> str:
    """Get the color for an agent, with fallback."""
    return AGENT_COLORS.get(agent_name, WHITE)


def get_agent_icon(agent_name: str) -> str:
    """Get the icon for an agent, with fallback."""
    return AGENT_ICONS.get(agent_name, "")


def find_agent_logs_for_uuid(uuid: str) -> list[tuple[str, str]]:
    """Find all agent log files for a given UUID.
    Returns list of (file_path, agent_name) tuples sorted by file modification time.
    """
    if not os.path.exists(AGENT_LOGS_DIR):
        return []

    files = []
    prefix = f"{uuid}_"
    for f in os.listdir(AGENT_LOGS_DIR):
        if f.startswith(prefix) and f.endswith('.jsonl'):
            full_path = os.path.join(AGENT_LOGS_DIR, f)
            # Extract agent name from filename: {uuid}_{agent}.jsonl
            agent_name = f[len(prefix):-6]  # Remove prefix and .jsonl
            mtime = os.path.getmtime(full_path)
            files.append((full_path, agent_name, mtime))

    # Sort by modification time
    files.sort(key=lambda x: x[2])
    return [(f[0], f[1]) for f in files]


def get_latest_uuid() -> str | None:
    """Get the most recently modified UUID from agent_logs directory."""
    if not os.path.exists(AGENT_LOGS_DIR):
        return None

    latest_uuid = None
    latest_mtime = 0

    for f in os.listdir(AGENT_LOGS_DIR):
        match = UUID_PATTERN.match(f)
        if match and f.endswith('.jsonl'):
            full_path = os.path.join(AGENT_LOGS_DIR, f)
            mtime = os.path.getmtime(full_path)
            if mtime > latest_mtime:
                latest_mtime = mtime
                latest_uuid = match.group(1)

    return latest_uuid


def list_available_uuids() -> list[tuple[str, datetime, int]]:
    """List all available UUIDs with their latest modification time and file count."""
    if not os.path.exists(AGENT_LOGS_DIR):
        return []

    uuid_info = defaultdict(lambda: {"mtime": 0, "count": 0})

    for f in os.listdir(AGENT_LOGS_DIR):
        match = UUID_PATTERN.match(f)
        if match and f.endswith('.jsonl'):
            uuid = match.group(1)
            full_path = os.path.join(AGENT_LOGS_DIR, f)
            mtime = os.path.getmtime(full_path)
            uuid_info[uuid]["mtime"] = max(uuid_info[uuid]["mtime"], mtime)
            uuid_info[uuid]["count"] += 1

    result = []
    for uuid, info in uuid_info.items():
        dt = datetime.fromtimestamp(info["mtime"])
        result.append((uuid, dt, info["count"]))

    # Sort by modification time, newest first
    result.sort(key=lambda x: x[1], reverse=True)
    return result


def format_timestamp(ts_str: str) -> str:
    """Format ISO timestamp to readable time."""
    try:
        dt = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        return dt.strftime("%H:%M:%S.%f")[:-3]  # Include milliseconds
    except:
        return ts_str[:12] if ts_str else "??:??:??"


def parse_log_entry(line: str) -> dict | None:
    """Parse a JSON log entry."""
    if not line.strip():
        return None
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        return None


def read_all_entries(files: list[tuple[str, str]]) -> list[dict]:
    """Read all log entries from all files, adding agent info."""
    all_entries = []

    for file_path, agent_name in files:
        try:
            with open(file_path, 'r') as f:
                for line in f:
                    entry = parse_log_entry(line)
                    if entry:
                        # Ensure agent_name is set
                        if "agent_name" not in entry:
                            entry["agent_name"] = agent_name
                        entry["_source_file"] = os.path.basename(file_path)
                        all_entries.append(entry)
        except Exception as e:
            print(f"{RED}Error reading {file_path}: {e}{RESET}")

    # Sort by timestamp
    def get_timestamp(entry):
        ts = entry.get("timestamp", "")
        try:
            return datetime.fromisoformat(ts.replace("Z", "+00:00"))
        except:
            return datetime.min

    all_entries.sort(key=get_timestamp)
    return all_entries


def truncate(text: str, max_len: int = 500) -> str:
    """Truncate text to max length."""
    if not text:
        return ""
    if len(text) <= max_len:
        return text
    return text[:max_len - 3] + "..."


def print_agent_header(agent_name: str, is_first: bool = False):
    """Print a visual separator header for an agent section."""
    color = get_agent_color(agent_name)
    icon = get_agent_icon(agent_name)

    if not is_first:
        print()

    print(f"{color}{BOLD}{'═' * 80}{RESET}")
    print(f"{color}{BOLD}  {icon}  {agent_name.upper()}{RESET}")
    print(f"{color}{'═' * 80}{RESET}")


def print_entry(entry: dict, show_agent_inline: bool = False):
    """Print a single log entry with formatting."""
    ts = format_timestamp(entry.get("timestamp", ""))
    level = entry.get("level", "INFO")
    source = entry.get("source", "")
    message = entry.get("message", "")
    agent_name = entry.get("agent_name", "?")

    # Color based on level
    level_color = {
        "INFO": DARK_GRAY,
        "WARN": YELLOW,
        "ERROR": RED,
        "DEBUG": BLUE,
    }.get(level, WHITE)

    agent_color = get_agent_color(agent_name)

    # Inline agent tag if requested
    agent_tag = ""
    if show_agent_inline:
        icon = get_agent_icon(agent_name)
        agent_tag = f"{agent_color}[{icon} {agent_name}]{RESET} "

    # Handle LLM queries specially
    if entry.get("type") == "log" and entry.get("source") == "LLM":
        prompt = entry.get("prompt", "")
        response = entry.get("response", "")
        model = entry.get("model", "unknown")
        duration = entry.get("duration_ms", "?")

        print(f"\n{agent_tag}{CYAN}{BOLD}╭{'─' * 68}╮{RESET}")
        print(f"{CYAN}{BOLD}│{RESET}  LLM Query  {DARK_GRAY}│ {model} │ {duration}ms │ {ts}{RESET}")
        print(f"{CYAN}{BOLD}╰{'─' * 68}╯{RESET}")

        print(f"\n{BOLD}{GREEN}➜ PROMPT{RESET}")
        print(f"{GREEN}{truncate(prompt, 800)}{RESET}")

        print(f"\n{BOLD}{PINK}➜ RESPONSE{RESET}")
        print(f"{PINK}{truncate(response, 1200)}{RESET}")
        print()
    else:
        # Regular log entry
        source_tag = f"[{source}] " if source else ""
        print(f"{DARK_GRAY}{ts}{RESET} {agent_tag}{level_color}{source_tag}{RESET}{message}")


def main():
    # Parse command line arguments
    uuid = None
    show_list = False
    group_by_agent = True  # Default: group by agent with headers

    args = sys.argv[1:]
    for arg in args:
        if arg in ("-l", "--list"):
            show_list = True
        elif arg in ("-t", "--timeline"):
            group_by_agent = False  # Show pure chronological timeline
        elif arg in ("-h", "--help"):
            print(f"""
{BOLD}Usage:{RESET} python read_last_log.py [UUID] [OPTIONS]

{BOLD}Arguments:{RESET}
  UUID              Conversation UUID to read (uses latest if not specified)

{BOLD}Options:{RESET}
  -l, --list        List all available UUIDs
  -t, --timeline    Show chronological timeline (don't group by agent)
  -h, --help        Show this help message

{BOLD}Examples:{RESET}
  python read_last_log.py                           # Read latest conversation
  python read_last_log.py -l                        # List all conversations
  python read_last_log.py 759b0e58-...              # Read specific conversation
  python read_last_log.py 759b0e58-... --timeline   # Show as timeline
""")
            return
        elif UUID_PATTERN.match(arg):
            uuid = arg

    # Handle list mode
    if show_list:
        uuids = list_available_uuids()
        if not uuids:
            print(f"{RED}No conversation logs found in ./{AGENT_LOGS_DIR}{RESET}")
            return

        print(f"\n{BOLD}{DARK_GRAY}󱩼 AVAILABLE CONVERSATIONS{RESET}")
        print(f"{DARK_GRAY}{'─' * 80}{RESET}")

        for i, (uid, dt, count) in enumerate(uuids):
            marker = f"{CYAN}→{RESET} " if i == 0 else "  "
            print(f"{marker}{uid}  {DARK_GRAY}{dt.strftime('%Y-%m-%d %H:%M:%S')}  ({count} agent files){RESET}")

        print(f"\n{DARK_GRAY}Tip: Use the UUID as an argument to read that conversation{RESET}\n")
        return

    # Get UUID (from argument or latest)
    if not uuid:
        uuid = get_latest_uuid()
        if not uuid:
            print(f"{RED}Error: No conversation logs found in ./{AGENT_LOGS_DIR}{RESET}")
            print(f"{DARK_GRAY}Run with -l to list available conversations{RESET}")
            return

    # Find all agent files for this UUID
    files = find_agent_logs_for_uuid(uuid)

    if not files:
        print(f"{RED}Error: No log files found for UUID: {uuid}{RESET}")
        print(f"{DARK_GRAY}Run with -l to list available conversations{RESET}")
        return

    # Print header
    agents_found = [f[1] for f in files]
    print(f"\n{BOLD}{DARK_GRAY}󱩼 CONVERSATION LOG{RESET}")
    print(f"{DARK_GRAY}ID: {uuid}{RESET}")
    print(f"{DARK_GRAY}Agents: {', '.join(agents_found)}{RESET}")
    print(f"{DARK_GRAY}{'━' * 80}{RESET}")

    # Read all entries
    all_entries = read_all_entries(files)

    if not all_entries:
        print(f"{YELLOW}No log entries found.{RESET}")
        return

    print(f"{DARK_GRAY}Total entries: {len(all_entries)}{RESET}\n")

    if group_by_agent:
        # Group entries by agent, maintaining temporal order within each group
        current_agent = None

        for entry in all_entries:
            agent_name = entry.get("agent_name", "unknown")

            # Print header when agent changes
            if agent_name != current_agent:
                print_agent_header(agent_name, is_first=(current_agent is None))
                current_agent = agent_name

            print_entry(entry)
    else:
        # Pure timeline mode - show all entries chronologically with inline agent tags
        for entry in all_entries:
            print_entry(entry, show_agent_inline=True)

    # Summary
    print(f"\n{DARK_GRAY}{'━' * 80}{RESET}")

    # Count LLM queries
    llm_queries = sum(1 for e in all_entries if e.get("source") == "LLM")

    print(f"{DARK_GRAY}End of conversation | {len(all_entries)} entries | {llm_queries} LLM queries{RESET}\n")


if __name__ == "__main__":
    main()
