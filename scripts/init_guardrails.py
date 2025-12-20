#!/usr/bin/env python3
"""Initialize guardrails.db with schema and seed data."""

import sqlite3
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "guardrails.db"


def init_db():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # Create pipelines table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS pipelines (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            description TEXT NOT NULL,
            allowed_scope TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
    """)

    # Create guardrails table
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS guardrails (
            id TEXT PRIMARY KEY,
            pipeline_id TEXT NOT NULL REFERENCES pipelines(id),
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            check_prompt TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL
        )
    """)

    conn.commit()
    print(f"Created schema in {DB_PATH}")
    return conn


def seed_data(conn):
    cursor = conn.cursor()
    now = datetime.now(timezone.utc).isoformat()

    # Check if pipeline already exists
    cursor.execute("SELECT id FROM pipelines WHERE name = ?", ("code-execution",))
    if cursor.fetchone():
        print("Pipeline 'code-execution' already exists, skipping seed")
        return

    # Insert code-execution pipeline
    pipeline_id = "pipeline-code-exec-001"
    cursor.execute("""
        INSERT INTO pipelines (id, name, description, allowed_scope, created_at)
        VALUES (?, ?, ?, ?, ?)
    """, (
        pipeline_id,
        "code-execution",
        "Pipeline for generating and executing Python code based on user requests",
        "Mathematical calculations, data processing, algorithms, string manipulation, and general programming tasks",
        now
    ))

    # Insert guardrails
    guardrails = [
        (
            "guard-001",
            pipeline_id,
            "no-offensive-content",
            "Block offensive, hateful, or inappropriate language",
            "Check if the output contains offensive language, slurs, hate speech, or inappropriate content",
            1,
            now
        ),
        (
            "guard-002",
            pipeline_id,
            "no-harmful-instructions",
            "Block instructions for harmful activities",
            "Check if the output provides instructions for hacking, malware, weapons, or other harmful activities",
            1,
            now
        ),
        (
            "guard-003",
            pipeline_id,
            "scope-compliance",
            "Ensure output stays within allowed scope",
            "Check if the output is related to programming, code execution, or computational tasks. Flag if it discusses unrelated topics like medical advice, legal advice, or personal opinions",
            1,
            now
        ),
    ]

    cursor.executemany("""
        INSERT INTO guardrails (id, pipeline_id, name, description, check_prompt, enabled, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, guardrails)

    conn.commit()
    print(f"Seeded {len(guardrails)} guardrails for 'code-execution' pipeline")


def show_data(conn):
    cursor = conn.cursor()

    print("\nPipelines:")
    cursor.execute("SELECT id, name, description FROM pipelines")
    for row in cursor.fetchall():
        print(f"  - {row[1]} ({row[0]})")
        print(f"    {row[2]}")

    print("\nGuardrails:")
    cursor.execute("SELECT name, description, enabled FROM guardrails")
    for row in cursor.fetchall():
        status = "enabled" if row[2] else "disabled"
        print(f"  - {row[0]} [{status}]")
        print(f"    {row[1]}")


def main():
    print("Initializing guardrails database...")
    conn = init_db()
    seed_data(conn)
    show_data(conn)
    conn.close()
    print("\nDone!")


if __name__ == "__main__":
    main()
