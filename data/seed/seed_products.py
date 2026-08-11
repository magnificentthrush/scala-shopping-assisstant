#!/usr/bin/env python3
"""
One-time-per-environment SEED script — not a migration. Loads
data/clean_products.jsonl (one product object per line; columns already
match the `products` table — see docs/database-schema.md) and upserts
into the shared Supabase `products` table.

Run this manually, once per environment, after
data/migrations/001_products_catalog.sql has been applied there. It is
idempotent: rows are upserted on `id` (INSERT ... ON CONFLICT DO UPDATE via
the Supabase client), so re-running it is safe and never duplicates rows.

Do NOT seed from raw files under data/raw/ — clean first with
data/scripts/clean_products.py, then seed from the JSONL.

Connects to Supabase via its client library (SUPABASE_URL / SUPABASE_KEY),
not a direct Postgres connection string.

Requires:
    SUPABASE_URL - e.g. https://xxxx.supabase.co
    SUPABASE_KEY - a service-role / secret key with insert/upsert rights on
                   `products`. Never commit this key.

Usage:
    pip install supabase
    export SUPABASE_URL=...
    export SUPABASE_KEY=...
    python data/seed/seed_products.py [--jsonl path/to/file.jsonl] [--limit 500]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Optional

from supabase import create_client

DEFAULT_JSONL = Path(__file__).resolve().parent.parent / "clean_products.jsonl"
BATCH_SIZE = 200


def map_row(row: dict[str, Any]) -> Optional[dict[str, Any]]:
    product_id = row.get("id")
    name = row.get("name")
    if not product_id or not name:
        return None

    rating = row.get("rating")
    if rating is not None:
        rating = str(rating)

    specs = row.get("product_specifications")
    if specs is not None and not isinstance(specs, str):
        specs = json.dumps(specs, ensure_ascii=False)

    return {
        "id": product_id,
        "name": name,
        "brand": row.get("brand"),
        "category": row.get("category"),
        "price": row.get("price"),
        "original_price": row.get("original_price"),
        "rating": rating,
        "description": row.get("description"),
        "image_url": row.get("image_url"),
        "product_url": row.get("product_url"),
        "product_specifications": specs,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--jsonl",
        default=str(DEFAULT_JSONL),
        help="Path to cleaned products JSONL",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Only seed the first N rows",
    )
    args = parser.parse_args()

    supabase_url = os.environ.get("SUPABASE_URL")
    supabase_key = os.environ.get("SUPABASE_KEY")
    if not supabase_url or not supabase_key:
        print("SUPABASE_URL and SUPABASE_KEY must be set.")
        sys.exit(1)

    jsonl_path = Path(args.jsonl)
    if not jsonl_path.exists():
        print(f"JSONL not found: {jsonl_path}")
        sys.exit(1)

    client = create_client(supabase_url, supabase_key)

    rows: list[dict[str, Any]] = []
    with jsonl_path.open(encoding="utf-8") as f:
        for i, line in enumerate(f):
            if args.limit is not None and i >= args.limit:
                break
            line = line.strip()
            if not line:
                continue
            mapped = map_row(json.loads(line))
            if mapped is not None:
                rows.append(mapped)

    print(f"Upserting {len(rows)} products into Supabase...")
    for start in range(0, len(rows), BATCH_SIZE):
        batch = rows[start : start + BATCH_SIZE]
        client.table("products").upsert(batch, on_conflict="id").execute()
        print(f"  upserted {start + len(batch)}/{len(rows)}")

    print("Done.")


if __name__ == "__main__":
    main()
