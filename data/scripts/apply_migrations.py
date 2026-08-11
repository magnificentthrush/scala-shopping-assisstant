#!/usr/bin/env python3
"""
Applies numbered SQL migrations in data/migrations/ to the shared Supabase
Postgres database, in order, tracking applied versions in a
`schema_migrations` table so the same migration never runs twice.

Migrations are structure-only (CREATE TABLE / ALTER TABLE / indexes) — never
data. Do not edit a migration file that has already been applied anywhere;
write a new, corrective migration instead (e.g. 005_fix_004.sql). Hosted
Supabase has no `docker compose down -v` reset button, so migrations must be
forward-only and safe to run against a database that already has some
versions applied (this script enforces the "already applied" check; the SQL
files themselves use IF NOT EXISTS / ADD COLUMN IF NOT EXISTS as a second
line of defense).

Whoever writes a migration for a feature applies it to the shared dev
database themselves, immediately — do not batch it for someone else to
guess should run. Post in the team channel when you apply one, so people
mid-testing aren't confused by an unannounced schema change.

Requires the SUPABASE_DB_URL environment variable: a direct Postgres
connection string to the Supabase project (Project Settings > Database >
Connection string). This is deliberately different from SUPABASE_URL /
SUPABASE_KEY, which the app and data/seed/seed_products.py use to talk to
Supabase's client/REST API — running arbitrary DDL requires a real Postgres
connection, not the REST layer.

Usage:
    pip install psycopg2-binary
    export SUPABASE_DB_URL=postgresql://postgres:[password]@[host]:5432/postgres
    python data/scripts/apply_migrations.py
"""
import os
import re
import sys
from pathlib import Path

import psycopg2

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"


def _version_from_filename(filename: str) -> int:
    match = re.match(r"^(\d+)_", filename)
    if not match:
        raise ValueError(
            f"Migration file '{filename}' must start with a numeric version, "
            "e.g. 005_description.sql"
        )
    return int(match.group(1))


def main() -> None:
    db_url = os.environ.get("SUPABASE_DB_URL")
    if not db_url:
        print("SUPABASE_DB_URL is not set (see this file's docstring).")
        sys.exit(1)

    migration_files = sorted(MIGRATIONS_DIR.glob("*.sql"))
    if not migration_files:
        print(f"No migration files found in {MIGRATIONS_DIR}")
        return

    conn = psycopg2.connect(db_url)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                  version    INT PRIMARY KEY,
                  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
            )
        conn.commit()

        for path in migration_files:
            version = _version_from_filename(path.name)

            with conn.cursor() as cur:
                cur.execute(
                    "SELECT 1 FROM schema_migrations WHERE version = %s", (version,)
                )
                already_applied = cur.fetchone() is not None

            if already_applied:
                print(f"skip   {path.name} (already applied)")
                continue

            print(f"apply  {path.name}")
            sql = path.read_text(encoding="utf-8")
            with conn.cursor() as cur:
                cur.execute(sql)
                cur.execute(
                    "INSERT INTO schema_migrations (version) VALUES (%s)", (version,)
                )
            conn.commit()

        print("Done.")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()
