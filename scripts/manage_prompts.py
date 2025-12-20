#!/usr/bin/env python3
"""
Prompt Management CLI for prompts.db

Usage:
    python manage_prompts.py init                          # Initialize database
    python manage_prompts.py add-prompt <name> <desc>      # Add new prompt
    python manage_prompts.py add-version <prompt_name> <version> <content_file>
    python manage_prompts.py enable <version_id>           # Enable a version
    python manage_prompts.py disable <version_id>          # Disable a version
    python manage_prompts.py list                          # List all prompts
    python manage_prompts.py list-versions <prompt_name>   # List versions for a prompt
    python manage_prompts.py show <version_id>             # Show version content
"""

import sqlite3
import sys
import uuid
from datetime import datetime
from pathlib import Path

DB_PATH = "prompts.db"


def get_connection():
    return sqlite3.connect(DB_PATH)


def init_db():
    """Initialize database tables."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS prompts (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            description TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS prompt_versions (
            id TEXT PRIMARY KEY,
            prompt_id TEXT NOT NULL,
            version TEXT NOT NULL,
            content TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            FOREIGN KEY (prompt_id) REFERENCES prompts(id),
            UNIQUE(prompt_id, version)
        )
    """)

    cursor.execute("""
        CREATE INDEX IF NOT EXISTS idx_versions_prompt_id
        ON prompt_versions(prompt_id)
    """)

    cursor.execute("""
        CREATE INDEX IF NOT EXISTS idx_versions_enabled
        ON prompt_versions(prompt_id, enabled)
    """)

    conn.commit()
    conn.close()
    print("Database initialized successfully.")


def add_prompt(name: str, description: str):
    """Add a new prompt."""
    conn = get_connection()
    cursor = conn.cursor()

    prompt_id = str(uuid.uuid4())
    created_at = datetime.utcnow().isoformat() + "Z"

    try:
        cursor.execute(
            "INSERT INTO prompts (id, name, description, created_at) VALUES (?, ?, ?, ?)",
            (prompt_id, name, description, created_at)
        )
        conn.commit()
        print(f"Prompt created: {name} (ID: {prompt_id})")
    except sqlite3.IntegrityError:
        print(f"Error: Prompt '{name}' already exists.")
    finally:
        conn.close()


def add_version(prompt_name: str, version: str, content_file: str):
    """Add a new version to a prompt."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("SELECT id FROM prompts WHERE name = ?", (prompt_name,))
    row = cursor.fetchone()

    if not row:
        print(f"Error: Prompt '{prompt_name}' not found.")
        conn.close()
        return

    prompt_id = row[0]

    content_path = Path(content_file)
    if not content_path.exists():
        print(f"Error: Content file '{content_file}' not found.")
        conn.close()
        return

    content = content_path.read_text()
    version_id = str(uuid.uuid4())
    created_at = datetime.utcnow().isoformat() + "Z"

    try:
        cursor.execute(
            "INSERT INTO prompt_versions (id, prompt_id, version, content, enabled, created_at) VALUES (?, ?, ?, ?, 1, ?)",
            (version_id, prompt_id, version, content, created_at)
        )
        conn.commit()
        print(f"Version created: {version} (ID: {version_id})")
    except sqlite3.IntegrityError:
        print(f"Error: Version '{version}' already exists for prompt '{prompt_name}'.")
    finally:
        conn.close()


def enable_version(version_id: str):
    """Enable a prompt version."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("UPDATE prompt_versions SET enabled = 1 WHERE id = ?", (version_id,))

    if cursor.rowcount > 0:
        conn.commit()
        print(f"Version {version_id} enabled.")
    else:
        print(f"Error: Version '{version_id}' not found.")

    conn.close()


def disable_version(version_id: str):
    """Disable a prompt version."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("UPDATE prompt_versions SET enabled = 0 WHERE id = ?", (version_id,))

    if cursor.rowcount > 0:
        conn.commit()
        print(f"Version {version_id} disabled.")
    else:
        print(f"Error: Version '{version_id}' not found.")

    conn.close()


def list_prompts():
    """List all prompts."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT p.id, p.name, p.description, p.created_at,
               COUNT(CASE WHEN v.enabled = 1 THEN 1 END) as enabled_versions,
               COUNT(v.id) as total_versions
        FROM prompts p
        LEFT JOIN prompt_versions v ON p.id = v.prompt_id
        GROUP BY p.id
        ORDER BY p.name
    """)

    rows = cursor.fetchall()
    conn.close()

    if not rows:
        print("No prompts found.")
        return

    print("\nPrompts:")
    print("-" * 80)
    for row in rows:
        id_, name, description, created_at, enabled, total = row
        print(f"  {name}")
        print(f"    ID: {id_}")
        print(f"    Description: {description}")
        print(f"    Versions: {enabled}/{total} enabled")
        print(f"    Created: {created_at}")
        print()


def list_versions(prompt_name: str):
    """List all versions for a prompt."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("SELECT id FROM prompts WHERE name = ?", (prompt_name,))
    row = cursor.fetchone()

    if not row:
        print(f"Error: Prompt '{prompt_name}' not found.")
        conn.close()
        return

    prompt_id = row[0]

    cursor.execute("""
        SELECT id, version, enabled, created_at, LENGTH(content) as content_length
        FROM prompt_versions
        WHERE prompt_id = ?
        ORDER BY created_at DESC
    """, (prompt_id,))

    rows = cursor.fetchall()
    conn.close()

    if not rows:
        print(f"No versions found for prompt '{prompt_name}'.")
        return

    print(f"\nVersions for '{prompt_name}':")
    print("-" * 80)
    for row in rows:
        id_, version, enabled, created_at, content_length = row
        status = "[ENABLED]" if enabled else "[DISABLED]"
        print(f"  {version} {status}")
        print(f"    ID: {id_}")
        print(f"    Content length: {content_length} chars")
        print(f"    Created: {created_at}")
        print()


def show_version(version_id: str):
    """Show content of a specific version."""
    conn = get_connection()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT v.version, v.content, v.enabled, v.created_at, p.name
        FROM prompt_versions v
        JOIN prompts p ON v.prompt_id = p.id
        WHERE v.id = ?
    """, (version_id,))

    row = cursor.fetchone()
    conn.close()

    if not row:
        print(f"Error: Version '{version_id}' not found.")
        return

    version, content, enabled, created_at, prompt_name = row
    status = "ENABLED" if enabled else "DISABLED"

    print(f"\nPrompt: {prompt_name}")
    print(f"Version: {version} [{status}]")
    print(f"Created: {created_at}")
    print("-" * 80)
    print(content)
    print("-" * 80)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1]

    if command == "init":
        init_db()
    elif command == "add-prompt" and len(sys.argv) >= 4:
        add_prompt(sys.argv[2], sys.argv[3])
    elif command == "add-version" and len(sys.argv) >= 5:
        add_version(sys.argv[2], sys.argv[3], sys.argv[4])
    elif command == "enable" and len(sys.argv) >= 3:
        enable_version(sys.argv[2])
    elif command == "disable" and len(sys.argv) >= 3:
        disable_version(sys.argv[2])
    elif command == "list":
        list_prompts()
    elif command == "list-versions" and len(sys.argv) >= 3:
        list_versions(sys.argv[2])
    elif command == "show" and len(sys.argv) >= 3:
        show_version(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
