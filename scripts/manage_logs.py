#!/usr/bin/env python3
"""
Logs Management CLI for JSONL conversation logs

Usage:
    python manage_logs.py stats                             # Show overall statistics
    python manage_logs.py stats-by-version                  # Show stats per prompt version
    python manage_logs.py conversation <conversation_id>    # View a conversation
    python manage_logs.py recent [limit]                    # Show recent exchanges
    python manage_logs.py list                              # List all conversations
"""

import json
import sys
from pathlib import Path
from datetime import datetime

LOGS_DIR = Path("conversation_logs")


def load_all_exchanges():
    """Load all exchanges from all JSONL files."""
    exchanges = []
    if not LOGS_DIR.exists():
        return exchanges

    for log_file in LOGS_DIR.glob("*.jsonl"):
        with open(log_file, "r") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        exchanges.append(json.loads(line))
                    except json.JSONDecodeError:
                        continue
    return exchanges


def load_conversation(conversation_id: str):
    """Load exchanges for a specific conversation."""
    log_file = LOGS_DIR / f"{conversation_id}.jsonl"
    exchanges = []

    if not log_file.exists():
        return exchanges

    with open(log_file, "r") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    exchanges.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return exchanges


def show_stats():
    """Show overall statistics."""
    exchanges = load_all_exchanges()

    if not exchanges:
        print("No exchanges logged yet.")
        return

    total = len(exchanges)
    conversations = set(e["conversation_id"] for e in exchanges)
    versions = set(e["prompt_version_id"] for e in exchanges)
    errors = sum(1 for e in exchanges if e.get("error"))
    avg_latency = sum(e["latency_ms"] for e in exchanges) / total
    avg_input = sum(e["input_tokens"] for e in exchanges) / total
    avg_output = sum(e["output_tokens"] for e in exchanges) / total
    error_rate = (errors / total * 100) if total > 0 else 0

    print("\nOverall Statistics:")
    print("-" * 50)
    print(f"  Total exchanges: {total}")
    print(f"  Total conversations: {len(conversations)}")
    print(f"  Unique prompt versions: {len(versions)}")
    print(f"  Average latency: {avg_latency:.0f} ms")
    print(f"  Average input tokens: {avg_input:.1f}")
    print(f"  Average output tokens: {avg_output:.1f}")
    print(f"  Error rate: {error_rate:.1f}% ({errors} errors)")


def show_stats_by_version():
    """Show statistics per prompt version."""
    exchanges = load_all_exchanges()

    if not exchanges:
        print("No exchanges logged yet.")
        return

    # Group by version
    by_version = {}
    for e in exchanges:
        version_id = e["prompt_version_id"]
        if version_id not in by_version:
            by_version[version_id] = []
        by_version[version_id].append(e)

    print("\nStatistics by Prompt Version:")
    print("-" * 80)

    for version_id, items in sorted(by_version.items(), key=lambda x: -len(x[1])):
        uses = len(items)
        avg_lat = sum(e["latency_ms"] for e in items) / uses
        avg_in = sum(e["input_tokens"] for e in items) / uses
        avg_out = sum(e["output_tokens"] for e in items) / uses
        errors = sum(1 for e in items if e.get("error"))
        error_rate = (errors / uses * 100) if uses > 0 else 0

        print(f"  Version: {version_id}")
        print(f"    Uses: {uses}")
        print(f"    Avg latency: {avg_lat:.0f} ms")
        print(f"    Avg tokens: {avg_in:.1f} in / {avg_out:.1f} out")
        print(f"    Error rate: {error_rate:.1f}%")
        print()


def show_conversation(conversation_id: str):
    """View all exchanges in a conversation."""
    exchanges = load_conversation(conversation_id)

    if not exchanges:
        print(f"No exchanges found for conversation: {conversation_id}")
        return

    # Sort by timestamp
    exchanges.sort(key=lambda e: e["timestamp"])

    print(f"\nConversation: {conversation_id}")
    print("=" * 80)

    for i, e in enumerate(exchanges, 1):
        print(f"\n--- Exchange {i} [{e['timestamp']}] ---")
        print(f"Version: {e['prompt_version_id']}")
        print(f"Model: {e['model_name']} | Latency: {e['latency_ms']}ms")
        if e.get("error"):
            print(f"ERROR: {e['error']}")
        input_msg = e["input_messages"]
        output = e["output_response"]
        print(f"\nInput:\n{input_msg[:500]}{'...' if len(input_msg) > 500 else ''}")
        print(f"\nOutput:\n{output[:500]}{'...' if len(output) > 500 else ''}")


def show_recent(limit: int = 10):
    """Show recent exchanges."""
    exchanges = load_all_exchanges()

    if not exchanges:
        print("No exchanges logged yet.")
        return

    # Sort by timestamp descending
    exchanges.sort(key=lambda e: e["timestamp"], reverse=True)
    recent = exchanges[:limit]

    print(f"\nRecent {len(recent)} Exchanges:")
    print("-" * 80)

    for e in recent:
        status = "ERROR" if e.get("error") else "OK"
        print(f"  [{e['timestamp']}] {status}")
        print(f"    ID: {e['id']}")
        print(f"    Conversation: {e['conversation_id']}")
        print(f"    Version: {e['prompt_version_id']}")
        print(f"    Model: {e['model_name']} | {e['latency_ms']}ms | {e['input_tokens']}/{e['output_tokens']} tokens")
        print()


def list_conversations():
    """List all conversation files."""
    if not LOGS_DIR.exists():
        print("No logs directory found.")
        return

    files = list(LOGS_DIR.glob("*.jsonl"))
    if not files:
        print("No conversation logs found.")
        return

    print(f"\nConversations ({len(files)} total):")
    print("-" * 50)

    for log_file in sorted(files, key=lambda f: f.stat().st_mtime, reverse=True):
        conv_id = log_file.stem
        size = log_file.stat().st_size
        mtime = datetime.fromtimestamp(log_file.stat().st_mtime)

        # Count exchanges
        with open(log_file, "r") as f:
            count = sum(1 for line in f if line.strip())

        print(f"  {conv_id}")
        print(f"    Exchanges: {count} | Size: {size} bytes | Modified: {mtime}")
        print()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1]

    if command == "stats":
        show_stats()
    elif command == "stats-by-version":
        show_stats_by_version()
    elif command == "conversation" and len(sys.argv) >= 3:
        show_conversation(sys.argv[2])
    elif command == "recent":
        limit = int(sys.argv[2]) if len(sys.argv) >= 3 else 10
        show_recent(limit)
    elif command == "list":
        list_conversations()
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
