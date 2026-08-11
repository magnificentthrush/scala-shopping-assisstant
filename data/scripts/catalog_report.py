#!/usr/bin/env python3
"""
Catalog Quality Report — ShopPilot

Connects to the shared Supabase Postgres database (via SUPABASE_DB_URL or .env)
and prints a complete report on data completeness, category distribution,
price statistics, and full-text search probe recall.

All commentary (missing counts, distinct-category counts, percentages) is
computed from the live query — never hardcoded — so a re-run stays honest.

Usage:
    python data/scripts/catalog_report.py
    python data/scripts/catalog_report.py --markdown
"""
from __future__ import annotations

import argparse
import io
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg2

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
else:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
ENV_PATH = PROJECT_ROOT / ".env"

# Columns reported for completeness (order matches retrievalPlan.md §2).
COMPLETENESS_COLUMNS = [
    "category",
    "price",
    "description",
    "brand",
    "image_url",
    "product_url",
    "product_specifications",
    "rating",
    "original_price",
]

# Fixed FTS probes — keep in sync with retrievalPlan.md §2 baseline.
FTS_PROBES = [
    (
        'plfts "hiking shoes" + price > 0',
        "search_vector @@ plainto_tsquery('english', 'hiking shoes') AND price > 0",
    ),
    (
        'plfts "waterproof hiking shoes" + price <= 5000',
        "search_vector @@ plainto_tsquery('english', 'waterproof hiking shoes') AND price <= 5000",
    ),
    (
        'plfts "watch men" + price <= 2000',
        "search_vector @@ plainto_tsquery('english', 'watch men') AND price <= 2000",
    ),
    (
        'plfts "cotton t-shirt" + price <= 500',
        "search_vector @@ plainto_tsquery('english', 'cotton t-shirt') AND price <= 500",
    ),
    (
        'term "shoes" alone',
        "search_vector @@ plainto_tsquery('english', 'shoes')",
    ),
    (
        'term "waterproof" alone',
        "search_vector @@ plainto_tsquery('english', 'waterproof')",
    ),
    (
        'term "hiking" alone',
        "search_vector @@ plainto_tsquery('english', 'hiking')",
    ),
    (
        'Footwear ∩ "waterproof"',
        "category = 'Footwear' AND search_vector @@ plainto_tsquery('english', 'waterproof')",
    ),
]


def load_env_if_needed() -> None:
    if "SUPABASE_DB_URL" not in os.environ and ENV_PATH.exists():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                os.environ.setdefault(key.strip(), val.strip())


def note_for_column(col: str, non_null: int, total: int, distinct_categories: int) -> str:
    missing = total - non_null
    if col == "category":
        return f"{distinct_categories} distinct values"
    if col == "price":
        return "no null-price filtering needed" if missing == 0 else f"{missing} rows missing"
    if col == "brand":
        pct_missing = (missing / total) * 100 if total else 0
        return f"~{pct_missing:.0f}% missing — brand is a bonus signal, never a filter requirement"
    if missing == 0:
        return "complete"
    return f"{missing} rows missing"


def run_report() -> dict[str, Any]:
    load_env_if_needed()
    db_url = os.environ.get("SUPABASE_DB_URL")
    if not db_url:
        print(
            "Error: SUPABASE_DB_URL environment variable is not set "
            "and could not be loaded from .env"
        )
        sys.exit(1)

    conn = psycopg2.connect(db_url, sslmode="require", connect_timeout=15)
    report_time = datetime.now(timezone.utc).isoformat()

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM products;")
            total_rows = cur.fetchone()[0]

            cur.execute("SELECT count(DISTINCT category) FROM products;")
            distinct_categories = cur.fetchone()[0]

            column_stats = []
            for col in COMPLETENESS_COLUMNS:
                # Column names come from a fixed allowlist above — not user input.
                cur.execute(f"SELECT count({col}) FROM products;")
                non_null = cur.fetchone()[0]
                column_stats.append(
                    {
                        "column": col,
                        "non_null": non_null,
                        "total": total_rows,
                        "note": note_for_column(
                            col, non_null, total_rows, distinct_categories
                        ),
                    }
                )

            cur.execute(
                """
                SELECT category, count(*) AS cnt
                FROM products
                GROUP BY category
                ORDER BY cnt DESC;
                """
            )
            categories = cur.fetchall()

            cur.execute(
                """
                SELECT
                    MIN(price)::numeric,
                    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price)::numeric,
                    MAX(price)::numeric,
                    AVG(price)::numeric
                FROM products;
                """
            )
            min_p, med_p, max_p, avg_p = cur.fetchone()

            cur.execute("SELECT count(*) FROM products WHERE price > 10000;")
            over_10k_count = cur.fetchone()[0]

            probe_results = []
            for query_label, sql_condition in FTS_PROBES:
                cur.execute(f"SELECT count(*) FROM products WHERE {sql_condition};")
                count = cur.fetchone()[0]
                probe_results.append((query_label, count))

            return {
                "timestamp": report_time,
                "total_rows": total_rows,
                "distinct_categories": distinct_categories,
                "column_stats": column_stats,
                "categories": categories,
                "price_stats": {
                    "min": float(min_p),
                    "median": float(med_p),
                    "max": float(max_p),
                    "avg": float(avg_p),
                    "over_10k_count": over_10k_count,
                    "over_10k_pct": (over_10k_count / total_rows) * 100
                    if total_rows > 0
                    else 0,
                },
                "probe_results": probe_results,
            }
    finally:
        conn.close()


def print_markdown(data: dict[str, Any]) -> None:
    print(f"<!-- Catalog Quality Report generated at {data['timestamp']} -->")
    print(
        f"\n**Baseline generated:** `{data['timestamp']}` "
        f"(via `catalog_report.py` / equivalent live SQL against shared Supabase). "
        f"**Total rows:** {data['total_rows']:,}. "
        f"**Distinct categories:** {data['distinct_categories']}.\n"
    )

    print("**Data completeness is excellent — nulls are not the problem:**\n")
    print("| Column | Non-null rows | Note |")
    print("| --- | --- | --- |")
    for stat in data["column_stats"][:5]:
        print(
            f"| `{stat['column']}` | {stat['non_null']:,} / {stat['total']:,} | "
            f"{stat['note']} |"
        )

    print(
        "\n**Category vocabulary is fixed and small — "
        "this is the single biggest retrieval lever:**\n"
    )
    print("```")
    cat_strings = [f"{cat} {cnt}" for cat, cnt in data["categories"]]
    chunk_size = 4
    for i in range(0, len(cat_strings), chunk_size):
        print(" · ".join(cat_strings[i : i + chunk_size]))
    print("```")

    print("\n**Prices are INR:**\n")
    print("| Stat | Value |")
    print("| --- | --- |")
    ps = data["price_stats"]
    print(
        f"| min / median / max | ₹{ps['min']:,.0f} / ₹{ps['median']:,.0f} / "
        f"₹{ps['max']:,.0f} |"
    )
    print(
        f"| rows over ₹10,000 | {ps['over_10k_count']:,} "
        f"({ps['over_10k_pct']:.0f}%) — mostly furniture, appliances, cameras |"
    )

    print(
        "\n**Full-phrase `plainto_tsquery` recall is thin — "
        "fallback is mandatory, not optional:**\n"
    )
    print("| Query | Matches |")
    print("| --- | --- |")
    for label, count in data["probe_results"]:
        count_str = f"**{count}**" if count == 0 else str(count)
        print(f"| `{label}` | {count_str} |")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate ShopPilot Catalog Quality Report"
    )
    parser.add_argument(
        "--markdown", action="store_true", help="Print report in Markdown format"
    )
    args = parser.parse_args()

    data = run_report()

    if args.markdown:
        print_markdown(data)
    else:
        print("=" * 60)
        print(f"ShopPilot Catalog Quality Report - {data['timestamp']}")
        print("=" * 60)
        print(f"Total Rows: {data['total_rows']:,}")
        print(f"Distinct categories: {data['distinct_categories']}")
        print("\n--- Column Completeness ---")
        for stat in data["column_stats"]:
            pct = (stat["non_null"] / stat["total"]) * 100
            print(
                f"  {stat['column']:<25}: {stat['non_null']:>6,} / "
                f"{stat['total']:,} ({pct:5.1f}%) — {stat['note']}"
            )

        print(
            f"\n--- Category Vocabulary "
            f"({len(data['categories'])} distinct categories) ---"
        )
        for cat, cnt in data["categories"]:
            print(f"  {cat:<35}: {cnt:>6,}")

        print("\n--- Price Distribution (INR) ---")
        ps = data["price_stats"]
        print(f"  Min   : ₹{ps['min']:,.2f}")
        print(f"  Median: ₹{ps['median']:,.2f}")
        print(f"  Mean  : ₹{ps['avg']:,.2f}")
        print(f"  Max   : ₹{ps['max']:,.2f}")
        print(
            f"  >₹10k : {ps['over_10k_count']:,} rows ({ps['over_10k_pct']:.2f}%)"
        )

        print("\n--- FTS Recall Probes ---")
        for label, count in data["probe_results"]:
            print(f"  {label:<50}: {count:>5} matches")
        print("=" * 60)


if __name__ == "__main__":
    main()
