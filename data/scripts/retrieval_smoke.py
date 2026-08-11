#!/usr/bin/env python3
"""
Retrieval Smoke Harness — ShopPilot

Runs 10 canonical queries (retrievalPlan.md §5 task 6) against the live
Supabase catalog and asserts expected category overlap in the top-5 results.

Each case mirrors `SupabaseProductProvider` rung 1 exactly: the full term
set via `plainto_tsquery('english', ...)` (PostgREST `plfts`) plus a price
cap (`price <= budget`, or `price > 0` when the query has no budget), with
`category IS NOT NULL` and LIMIT 5. Read-only — SELECTs only.

Usage:
    python data/scripts/retrieval_smoke.py

Exit codes:
    0 — all cases passed
    1 — one or more cases failed
    2 — database unreachable / SUPABASE_DB_URL missing
"""
from __future__ import annotations

import io
import os
import sys
from pathlib import Path
from typing import Any, Optional

import psycopg2

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
else:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
ENV_PATH = PROJECT_ROOT / ".env"

TOP_N = 5

# Canonical cases from retrievalPlan.md §5 task 6.
# `terms` is the full term set a grounded ExtractedFilters would produce for
# the query (keywords + attributes, minus the budget phrase); `price_lte`
# mirrors filters.budget. `expect` is the category required in top-5, or
# None for the known-thin case that only asserts graceful non-crash.
CASES: list[dict[str, Any]] = [
    {
        "query": "men's watch under 2000",
        "terms": "men's watch",
        "price_lte": 2000,
        "expect": "Watches",
    },
    {
        "query": "cotton t-shirt under 500",
        "terms": "cotton t-shirt",
        "price_lte": 500,
        "expect": "Clothing",
    },
    {
        "query": "running shoes under 1000",
        "terms": "running shoes",
        "price_lte": 1000,
        "expect": "Footwear",
    },
    {
        "query": "gold necklace",
        "terms": "gold necklace",
        "price_lte": None,
        "expect": "Jewellery",
    },
    {
        "query": "laptop under 50000",
        "terms": "laptop",
        "price_lte": 50000,
        "expect": "Computers",
    },
    {
        "query": "baby diapers",
        "terms": "baby diapers",
        "price_lte": None,
        "expect": "Baby Care",
    },
    {
        "query": "kitchen knife set",
        "terms": "kitchen knife set",
        "price_lte": None,
        "expect": "Kitchen & Dining",
    },
    {
        "query": "sofa bed",
        "terms": "sofa bed",
        "price_lte": None,
        "expect": "Furniture",
    },
    {
        "query": "waterproof hiking boots",
        "terms": "waterproof hiking boots",
        "price_lte": None,
        "expect": None,  # known-thin: graceful non-crash, <=5 results, any category
    },
    {
        "query": "wireless bluetooth earbuds under 1500",
        "terms": "wireless bluetooth earbuds",
        "price_lte": 1500,
        "expect": "Mobiles & Accessories",
    },
]


def load_env_if_needed() -> None:
    if "SUPABASE_DB_URL" not in os.environ and ENV_PATH.exists():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                os.environ.setdefault(key.strip(), val.strip())


def run_rung1(cur, terms: str, price_lte: Optional[int]) -> list[tuple]:
    """Mirror SupabaseProductProvider rung 1: plfts(full term set) + price."""
    if price_lte is not None:
        cur.execute(
            """
            SELECT name, category, price
            FROM products
            WHERE category IS NOT NULL
              AND price <= %s
              AND search_vector @@ plainto_tsquery('english', %s)
            LIMIT %s;
            """,
            (price_lte, terms, TOP_N),
        )
    else:
        cur.execute(
            """
            SELECT name, category, price
            FROM products
            WHERE category IS NOT NULL
              AND price > 0
              AND search_vector @@ plainto_tsquery('english', %s)
            LIMIT %s;
            """,
            (terms, TOP_N),
        )
    return cur.fetchall()


def main() -> None:
    load_env_if_needed()
    db_url = os.environ.get("SUPABASE_DB_URL")
    if not db_url:
        print(
            "Error: SUPABASE_DB_URL environment variable is not set "
            "and could not be loaded from .env"
        )
        sys.exit(2)

    try:
        conn = psycopg2.connect(db_url, sslmode="require", connect_timeout=15)
    except Exception as exc:
        print(f"Error: could not connect to Supabase database: {exc}")
        sys.exit(2)

    print("=" * 60)
    print("Retrieval Smoke Harness — 10 canonical queries (rung 1)")
    print("=" * 60)

    passed = 0
    failed = 0
    try:
        with conn.cursor() as cur:
            for i, case in enumerate(CASES, start=1):
                try:
                    rows = run_rung1(cur, case["terms"], case["price_lte"])
                except Exception as exc:
                    print(f"[{i:>2}] FAIL  \"{case['query']}\"")
                    print(f"       query error: {exc}")
                    failed += 1
                    continue

                categories = [r[1] for r in rows]
                expect = case["expect"]

                if expect is None:
                    ok = 0 <= len(rows) <= TOP_N
                    verdict = (
                        f"graceful ({len(rows)} results, any category OK)"
                    )
                else:
                    ok = expect in categories
                    verdict = f"expect {expect} in top-{TOP_N}"

                status = "PASS" if ok else "FAIL"
                if ok:
                    passed += 1
                else:
                    failed += 1

                print(f"[{i:>2}] {status}  \"{case['query']}\"")
                print(f"       {verdict}")
                print(f"       top-{len(rows)} categories: {categories}")
                for name, cat, price in rows:
                    price_str = (
                        f"Rs.{float(price):,.0f}" if price is not None else "n/a"
                    )
                    print(f"         - [{cat}] {name[:60]} ({price_str})")
    finally:
        conn.close()

    print("=" * 60)
    print(f"Result: {passed} passed, {failed} failed out of {len(CASES)} cases")
    print("=" * 60)
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
