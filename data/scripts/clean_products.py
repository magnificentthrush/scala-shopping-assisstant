"""
clean_products.py

Transforms a raw product export (CSV/TSV) into a clean, DB-ready file for
the `products` table (see docs/database-schema.md). Streams the input
row-by-row so it scales to large exports, validates/repairs each field
according to fixed rules, and writes a structured warning log plus a
summary of what was dropped and why.

Input columns (in the source file, delimiter configurable via --delimiter,
default tab):
    id, name, brand, category, price, original_price, rating, description,
    image_url, product_url, product_specifications

`product_specifications` arrives as Ruby hash-rocket syntax, e.g.:
    {"product_specification"=>[{"key"=>"Fabric", "value"=>"Cotton Lycra"},
                                {"value"=>"3 shorts"}]}
which is NOT valid JSON ('=>' is not a JSON token).

Transformation rules applied (see the docstring of each function for
detail):
    1. Blank/whitespace-only values -> null, for every field.
    2. rating: "" / "No rating available" -> null; must parse as a float
       in [0, 5] or it becomes null (with a warning).
    3. price / original_price: strip thousands-separator commas, parse as
       float; null (with a warning) if non-numeric or <= 0. If both parse
       and price > original_price, log a warning but keep the row.
    4. product_specifications: Ruby hash-rocket -> JSON, parsed and
       normalized to a flat `[{"key": ..., "value": ...}, ...]` list, or
       null if empty/unparseable.
    5. name / brand / category / description: mojibake repair (UTF-8
       decoded as Latin-1), "...View More" truncation-artifact cleanup,
       and (description only) a length warning under 15 characters.
    6. image_url / product_url: must match `^https?://\\S+$` or become
       null, with a warning either way if invalid/empty.
    7. id: validated against ID_PATTERN (configurable below). Rows with a
       missing id, or a duplicate id, are dropped entirely.
    8. Exact duplicate rows (all raw fields identical) -> only the first
       occurrence is kept.

Usage:
    python data/scripts/clean_products.py data/raw/products.tsv \\
        --delimiter '\\t' \\
        --output data/clean_products.jsonl \\
        --format jsonl \\
        --log-file data/clean_products_report.log

    # Review warnings without writing the output file:
    python data/scripts/clean_products.py data/raw/products.tsv --dry-run

Unit tests live in data/scripts/test_clean_products.py
    (run with: python -m unittest data/scripts/test_clean_products.py)
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import re
from dataclasses import dataclass, field as dc_field
from typing import Any, Dict, List, Optional, Set, Tuple

# --------------------------------------------------------------------------
# Configuration constants
# --------------------------------------------------------------------------

# Adjust this if the real dataset's ids don't look like 32-char lowercase
# hex (e.g. Kaggle's `uniq_id` column does).
ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")

URL_PATTERN = re.compile(r"^https?://\S+$")

RATING_MIN = 0.0
RATING_MAX = 5.0

DESCRIPTION_MIN_LENGTH = 15

# "Ã" followed by another special/non-ascii char, or a literal "â€" run --
# both are classic signs of UTF-8 bytes that got decoded as Latin-1.
_MOJIBAKE_RE = re.compile(r"Ã.|â€")

# This dataset truncates long text with "...View More" and then repeats the
# pre-marker text again in full afterward.
_VIEW_MORE_RE = re.compile(r"\.{0,3}\s*View More\s*")

# Ruby hash-rocket ("key"=>value) -> JSON colon ("key": value). Every
# occurrence in this dataset is immediately preceded by a closing quote.
_HASH_ROCKET_RE = re.compile(r'"\s*=>\s*')

FINAL_COLUMNS = [
    "id",
    "name",
    "brand",
    "category",
    "price",
    "original_price",
    "rating",
    "description",
    "image_url",
    "product_url",
    "product_specifications",
]

DEFAULT_OUTPUT = "data/clean_products.jsonl"
DEFAULT_FORMAT = "jsonl"
DEFAULT_LOG_FILE = "data/clean_products_report.log"


# --------------------------------------------------------------------------
# Stats
# --------------------------------------------------------------------------


@dataclass
class Stats:
    """Accumulates counts for the end-of-run summary."""

    rows_read: int = 0
    rows_kept: int = 0
    dropped_duplicate_id: int = 0
    dropped_missing_id: int = 0
    dropped_exact_duplicate_row: int = 0
    warnings_by_field: Dict[str, int] = dc_field(default_factory=dict)

    @property
    def total_dropped(self) -> int:
        return (
            self.dropped_duplicate_id
            + self.dropped_missing_id
            + self.dropped_exact_duplicate_row
        )

    @property
    def total_warnings(self) -> int:
        return sum(self.warnings_by_field.values())


def log_warning(
    logger: logging.Logger,
    stats: Stats,
    row_num: int,
    row_id: Optional[str],
    field: str,
    issue: str,
) -> None:
    """Emit one structured warning line and tally it in `stats`.

    Format: [WARNING] row=<n> id=<id or 'MISSING'> field=<field> issue=<message>
    """
    id_display = row_id if row_id else "MISSING"
    logger.warning("[WARNING] row=%d id=%s field=%s issue=%s", row_num, id_display, field, issue)
    stats.warnings_by_field[field] = stats.warnings_by_field.get(field, 0) + 1


# --------------------------------------------------------------------------
# Field-level transforms
# --------------------------------------------------------------------------


def repair_mojibake(
    text: str,
    row_num: int,
    row_id: Optional[str],
    field: str,
    stats: Stats,
    logger: logging.Logger,
) -> str:
    """Repair UTF-8 text that was mistakenly decoded as Latin-1.

    Detects the artifact via `_MOJIBAKE_RE` and attempts
    `text.encode('latin1').decode('utf8')`. Falls back to the original text
    (with a warning) if the repair itself raises.
    """
    if not _MOJIBAKE_RE.search(text):
        return text
    try:
        return text.encode("latin1").decode("utf8")
    except (UnicodeDecodeError, UnicodeEncodeError):
        log_warning(logger, stats, row_num, row_id, field, "mojibake repair failed; kept original text")
        return text


def strip_view_more(text: str) -> Tuple[str, bool]:
    """Collapse '...View More <duplicated text>' truncation artifacts.

    The dataset truncates a field with "...View More" and then repeats the
    text that came right before the marker, in full, immediately after it.
    If we can find that exact repeated block (a long-enough suffix of the
    pre-marker text reappearing as a prefix of the post-marker text), we
    remove the duplicate and return the single, non-truncated version.

    If no such repeat can be confidently detected, we just strip the
    literal marker and return `needs_review=True` so the caller can log a
    warning for manual review, per spec.
    """
    match = _VIEW_MORE_RE.search(text)
    if not match:
        return text, False

    before = text[: match.start()]
    after = text[match.end() :]
    after_trimmed = after.lstrip(" -")

    max_check = min(len(before), 400)
    best_len = 0
    for length in range(max_check, 19, -1):  # require a >=20 char match to be confident
        suffix = before[-length:]
        if after_trimmed.startswith(suffix):
            best_len = length
            break

    if best_len:
        cleaned = (before + after_trimmed[best_len:]).strip()
        # Multiple "...View More" markers can appear in one field; collapse
        # them all rather than stopping after the first.
        if _VIEW_MORE_RE.search(cleaned):
            cleaned, needs_review = strip_view_more(cleaned)
            return cleaned, needs_review
        return cleaned, False

    # Couldn't confidently find the duplicate -- strip just the marker.
    cleaned = (before + after).strip()
    return cleaned, True


def clean_text_field(
    raw: Optional[str],
    row_num: int,
    row_id: Optional[str],
    field: str,
    stats: Stats,
    logger: logging.Logger,
    min_length: Optional[int] = None,
) -> Optional[str]:
    """Blank -> null, mojibake repair, '...View More' cleanup, and an
    optional minimum-length warning (used for `description`)."""
    if raw is None:
        return None
    text = raw.strip()
    if not text:
        return None

    text = repair_mojibake(text, row_num, row_id, field, stats, logger)
    text, needs_review = strip_view_more(text)
    text = text.strip()

    if needs_review:
        log_warning(
            logger, stats, row_num, row_id, field,
            "contains a 'View More' truncation marker; could not confidently "
            "de-duplicate the repeated text, marker stripped as-is -- flag for manual review",
        )

    if min_length is not None and text and len(text) < min_length:
        log_warning(logger, stats, row_num, row_id, field, f"text is only {len(text)} chars after cleaning")

    return text if text else None


def parse_price(
    raw: Optional[str],
    row_num: int,
    row_id: Optional[str],
    field: str,
    stats: Stats,
    logger: logging.Logger,
) -> Optional[float]:
    """Parse a price field: strip thousands-separator commas, parse float.
    Non-numeric or <= 0 values become null, with a warning."""
    text = (raw or "").strip()
    if not text:
        return None

    text = text.replace(",", "")
    try:
        value = float(text)
    except ValueError:
        log_warning(logger, stats, row_num, row_id, field, f"could not parse price {raw!r}")
        return None

    if value <= 0:
        log_warning(logger, stats, row_num, row_id, field, f"non-positive price {value}")
        return None

    return value


def parse_rating(
    raw: Optional[str],
    row_num: int,
    row_id: Optional[str],
    stats: Stats,
    logger: logging.Logger,
) -> Optional[float]:
    """"" / "No rating available" -> null. Otherwise must parse as a float
    in [0, 5], or it becomes null with a warning."""
    text = (raw or "").strip()
    if not text or text == "No rating available":
        return None

    try:
        value = float(text)
    except ValueError:
        log_warning(logger, stats, row_num, row_id, "rating", f"could not parse rating {raw!r}")
        return None

    if not (RATING_MIN <= value <= RATING_MAX):
        log_warning(logger, stats, row_num, row_id, "rating", f"rating {value} outside [{RATING_MIN}, {RATING_MAX}]")
        return None

    return value


def validate_url(
    raw: Optional[str],
    row_num: int,
    row_id: Optional[str],
    field: str,
    stats: Stats,
    logger: logging.Logger,
) -> Optional[str]:
    """Must match `^https?://\\S+$`. Invalid or empty -> null, with a
    warning in both cases."""
    text = (raw or "").strip()
    if not text:
        log_warning(logger, stats, row_num, row_id, field, "empty URL")
        return None
    if not URL_PATTERN.match(text):
        log_warning(logger, stats, row_num, row_id, field, f"invalid URL {text!r}")
        return None
    return text


def parse_specifications(
    raw: Optional[str],
    row_num: int,
    row_id: Optional[str],
    stats: Stats,
    logger: logging.Logger,
) -> Optional[List[Dict[str, Any]]]:
    """Ruby hash-rocket -> JSON, then normalized to a flat
    `[{"key": <str|null>, "value": <str>}, ...]` list.

    Returns None (not []) when the field is empty/missing, so "no spec
    data" stays distinguishable from "spec data was an empty list".
    Returns None (with a warning) if the string can't be converted/parsed.
    """
    text = (raw or "").strip()
    if not text:
        return None

    json_text = _HASH_ROCKET_RE.sub('": ', text)

    try:
        parsed = json.loads(json_text)
    except (json.JSONDecodeError, ValueError) as exc:
        truncated = text[:200]
        log_warning(
            logger, stats, row_num, row_id, "product_specifications",
            f"failed to parse ({exc}); raw={truncated!r}",
        )
        return None

    if isinstance(parsed, dict) and "product_specification" in parsed:
        items = parsed["product_specification"]
    elif isinstance(parsed, list):
        items = parsed
    else:
        log_warning(
            logger, stats, row_num, row_id, "product_specifications",
            f"unexpected structure after parsing: {type(parsed).__name__}",
        )
        return None

    if not isinstance(items, list):
        log_warning(
            logger, stats, row_num, row_id, "product_specifications",
            f"expected a list of spec entries, got {type(items).__name__}",
        )
        return None

    normalized: List[Dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        value = item.get("value")
        if value is None:
            continue
        normalized.append({"key": item.get("key"), "value": str(value)})

    return normalized


# --------------------------------------------------------------------------
# Row-level cleaning
# --------------------------------------------------------------------------


def clean_row(
    row: Dict[str, Optional[str]],
    row_num: int,
    seen_ids: Dict[str, int],
    seen_raw_rows: Set[Tuple[str, ...]],
    fieldnames: List[str],
    stats: Stats,
    logger: logging.Logger,
) -> Optional[Dict[str, Any]]:
    """Clean and validate one raw row.

    Returns the cleaned row (ready for output) or None if the row must be
    dropped entirely: an exact duplicate of an earlier row, a missing id,
    or a duplicate id.
    """
    stats.rows_read += 1

    raw_key = tuple((row.get(c) or "") for c in fieldnames)
    if raw_key in seen_raw_rows:
        log_warning(logger, stats, row_num, row.get("id") or None, "row", "exact duplicate of an earlier row; dropped")
        stats.dropped_exact_duplicate_row += 1
        return None
    seen_raw_rows.add(raw_key)

    raw_id = (row.get("id") or "").strip()
    if not raw_id:
        log_warning(logger, stats, row_num, None, "id", "missing id; row dropped (cannot have a null primary key)")
        stats.dropped_missing_id += 1
        return None

    if not ID_PATTERN.match(raw_id):
        log_warning(
            logger, stats, row_num, raw_id, "id",
            f"id does not match expected pattern {ID_PATTERN.pattern!r}",
        )

    if raw_id in seen_ids:
        log_warning(
            logger, stats, row_num, raw_id, "id",
            f"duplicate id, first seen at row={seen_ids[raw_id]}; row {row_num} dropped",
        )
        stats.dropped_duplicate_id += 1
        return None
    seen_ids[raw_id] = row_num

    cleaned: Dict[str, Any] = {"id": raw_id}
    cleaned["name"] = clean_text_field(row.get("name"), row_num, raw_id, "name", stats, logger)
    cleaned["brand"] = clean_text_field(row.get("brand"), row_num, raw_id, "brand", stats, logger)
    cleaned["category"] = clean_text_field(row.get("category"), row_num, raw_id, "category", stats, logger)
    cleaned["description"] = clean_text_field(
        row.get("description"), row_num, raw_id, "description", stats, logger,
        min_length=DESCRIPTION_MIN_LENGTH,
    )

    price = parse_price(row.get("price"), row_num, raw_id, "price", stats, logger)
    original_price = parse_price(row.get("original_price"), row_num, raw_id, "original_price", stats, logger)
    if price is not None and original_price is not None and price > original_price:
        log_warning(
            logger, stats, row_num, raw_id, "price",
            f"price ({price}) is higher than original_price ({original_price}); suspicious, row kept",
        )
    cleaned["price"] = price
    cleaned["original_price"] = original_price

    cleaned["rating"] = parse_rating(row.get("rating"), row_num, raw_id, stats, logger)
    cleaned["image_url"] = validate_url(row.get("image_url"), row_num, raw_id, "image_url", stats, logger)
    cleaned["product_url"] = validate_url(row.get("product_url"), row_num, raw_id, "product_url", stats, logger)
    cleaned["product_specifications"] = parse_specifications(
        row.get("product_specifications"), row_num, raw_id, stats, logger,
    )

    stats.rows_kept += 1
    return cleaned


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------


class RowWriter:
    """Incrementally writes cleaned rows as jsonl or csv, or writes nothing
    at all in --dry-run mode (validation/logging still runs)."""

    def __init__(self, output_path: str, fmt: str, fieldnames: List[str], dry_run: bool) -> None:
        self._dry_run = dry_run
        self._fmt = fmt
        self._file = None
        self._csv_writer: Optional["csv._writer"] = None
        if not dry_run:
            newline = "" if fmt == "csv" else None
            self._file = open(output_path, "w", encoding="utf-8", newline=newline)
            if fmt == "csv":
                self._csv_writer = csv.DictWriter(self._file, fieldnames=fieldnames)
                self._csv_writer.writeheader()

    def write(self, row: Dict[str, Any]) -> None:
        if self._dry_run:
            return
        if self._fmt == "jsonl":
            self._file.write(json.dumps(row, ensure_ascii=False))
            self._file.write("\n")
        else:
            out_row = dict(row)
            specs = out_row.get("product_specifications")
            out_row["product_specifications"] = json.dumps(specs, ensure_ascii=False) if specs is not None else ""
            out_row = {k: ("" if v is None else v) for k, v in out_row.items()}
            self._csv_writer.writerow(out_row)

    def close(self) -> None:
        if self._file is not None:
            self._file.close()


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("input", help="Path to the raw input CSV/TSV file")
    parser.add_argument("--delimiter", default="\t", help=r"Field delimiter of the input file (default: '\t')")
    parser.add_argument("--output", default=DEFAULT_OUTPUT, help=f"Output file path (default: {DEFAULT_OUTPUT})")
    parser.add_argument(
        "--format", choices=["jsonl", "csv"], default=DEFAULT_FORMAT,
        help=f"Output format (default: {DEFAULT_FORMAT})",
    )
    parser.add_argument(
        "--log-file", default=DEFAULT_LOG_FILE,
        help=f"Path to write the structured warning log (default: {DEFAULT_LOG_FILE})",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Run all validation/cleaning and logging, but do not write the output file",
    )
    return parser


def setup_logger(log_file: str) -> logging.Logger:
    logger = logging.getLogger("clean_products")
    logger.setLevel(logging.WARNING)
    logger.handlers.clear()
    logger.propagate = False
    handler = logging.FileHandler(log_file, mode="w", encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
    return logger


def print_summary(stats: Stats, args: argparse.Namespace) -> None:
    print("=" * 60)
    print("clean_products.py summary")
    print("=" * 60)
    print(f"Input:  {args.input}")
    if args.dry_run:
        print(f"Output: (dry run -- no file written; would have been {args.output})")
    else:
        print(f"Output: {args.output} ({args.format})")
    print(f"Log:    {args.log_file}")
    print("-" * 60)
    print(f"Rows read:        {stats.rows_read}")
    print(f"Rows written:     {0 if args.dry_run else stats.rows_kept}")
    print(f"Rows dropped:     {stats.total_dropped}")
    print(f"  - duplicate id:        {stats.dropped_duplicate_id}")
    print(f"  - missing id:          {stats.dropped_missing_id}")
    print(f"  - exact duplicate row: {stats.dropped_exact_duplicate_row}")
    print("-" * 60)
    print(f"Warnings: {stats.total_warnings}")
    for field_name, count in sorted(stats.warnings_by_field.items()):
        print(f"  - {field_name}: {count}")
    print("=" * 60)


def main(argv: Optional[List[str]] = None) -> Stats:
    args = build_arg_parser().parse_args(argv)
    logger = setup_logger(args.log_file)
    stats = Stats()

    seen_ids: Dict[str, int] = {}
    seen_raw_rows: Set[Tuple[str, ...]] = set()

    with open(args.input, newline="", encoding="utf-8") as infile:
        reader = csv.DictReader(infile, delimiter=args.delimiter)
        fieldnames = reader.fieldnames or []

        writer = RowWriter(args.output, args.format, FINAL_COLUMNS, args.dry_run)
        try:
            for row_num, row in enumerate(reader, start=1):
                cleaned = clean_row(row, row_num, seen_ids, seen_raw_rows, fieldnames, stats, logger)
                if cleaned is not None:
                    writer.write(cleaned)
        finally:
            writer.close()

    print_summary(stats, args)
    return stats


if __name__ == "__main__":
    main()
