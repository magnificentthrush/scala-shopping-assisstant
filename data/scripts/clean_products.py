"""
clean_products.py

Cleans the raw Kaggle/Flipkart product CSV and maps it onto our `products`
schema (see docs/project-plan.md §10 for the column mapping / target schema).

Usage:
    python data/scripts/clean_products.py

Reads:  data/raw/flipkart_com-ecommerce_sample.csv
Writes: data/clean_products.csv
        CLEANING_NOTES.md (counts are filled in automatically from this run)
"""

import ast
import re
import pandas as pd

RAW_PATH = "data/raw/flipkart_com-ecommerce_sample.csv"
OUT_PATH = "data/clean_products.csv"
NOTES_PATH = "CLEANING_NOTES.md"

# Columns we keep from the source and their target names.
# (See project-plan.md §10.3 "Column mapping (seed script)")
RENAME_MAP = {
    "uniq_id": "id",
    "product_name": "name",
    "brand": "brand",
    "discounted_price": "price",
    "retail_price": "original_price",
    "product_rating": "rating",
    "description": "description",
    "product_url": "product_url",
    "product_specifications": "product_specifications",
    # category and image_url are derived below, not a straight rename
}

# Explicitly dropped per project-plan.md §10.1
DROP_COLUMNS = ["crawl_timestamp", "pid", "is_FK_Advantage_product", "overall_rating"]


def parse_first_from_list_string(value):
    """product_category_tree and image both arrive as a string that looks like
    a Python list, e.g. '["A >> B >> C"]' or '["url1", "url2"]'.
    Returns the first element, or None if it can't be parsed."""
    if not isinstance(value, str):
        return None
    try:
        parsed = ast.literal_eval(value)
        if isinstance(parsed, list) and len(parsed) > 0:
            return parsed[0]
    except (ValueError, SyntaxError):
        return None
    return None


def top_level_category(tree_value):
    """Extract the top-level category from the '>>' hierarchy, e.g.
    'Clothing >> Women's Clothing >> ...' -> 'Clothing'.
    Returns None for malformed/single-level trees (see notes)."""
    first = parse_first_from_list_string(tree_value)
    if first is None:
        return None
    parts = [p.strip() for p in first.split(">>")]
    if len(parts) < 2:
        # Single-level "tree" means the category is actually just the
        # product name repeated -- there's no real category here.
        return None
    return parts[0]


def standardize_category(cat):
    """Trim whitespace and apply consistent title casing so 'Hiking Shoes',
    'hiking shoes', 'HIKING SHOES' all collapse to one value."""
    if cat is None or (isinstance(cat, float) and pd.isna(cat)):
        return None
    cleaned = re.sub(r"\s+", " ", cat).strip()
    return cleaned.title() if cleaned else None


def main():
    df = pd.read_csv(RAW_PATH)
    start_count = len(df)
    notes = {"start_count": start_count}

    # --- 1. Drop exact duplicate rows -------------------------------------
    before = len(df)
    df = df.drop_duplicates()
    notes["exact_dupes_removed"] = before - len(df)

    # --- 2. Drop duplicate products (same pid = same product re-listed) ---
    before = len(df)
    df = df.drop_duplicates(subset=["pid"], keep="first")
    notes["duplicate_pid_removed"] = before - len(df)

    # --- 3. Derive category + image_url before dropping source columns ----
    df["category"] = df["product_category_tree"].apply(top_level_category)
    df["category"] = df["category"].apply(standardize_category)
    df["image_url"] = df["image"].apply(parse_first_from_list_string)

    # --- 4. Drop rows missing critical fields ------------------------------
    # Critical fields: name, price (discounted_price), category.
    before = len(df)
    missing_name = df["product_name"].isna() | (df["product_name"].astype(str).str.strip() == "")
    missing_price = df["discounted_price"].isna() | df["retail_price"].isna()
    missing_category = df["category"].isna()

    notes["removed_missing_name"] = int(missing_name.sum())
    notes["removed_missing_price"] = int((missing_price & ~missing_name).sum())
    notes["removed_missing_category"] = int((missing_category & ~missing_name & ~missing_price).sum())

    df = df[~(missing_name | missing_price | missing_category)]
    notes["total_missing_critical_removed"] = before - len(df)

    # --- 5. Validate prices are sane numeric values ------------------------
    before = len(df)
    df["discounted_price"] = pd.to_numeric(df["discounted_price"], errors="coerce")
    df["retail_price"] = pd.to_numeric(df["retail_price"], errors="coerce")
    bad_price = (
        df["discounted_price"].isna()
        | df["retail_price"].isna()
        | (df["discounted_price"] <= 0)
        | (df["retail_price"] <= 0)
        | (df["discounted_price"] > df["retail_price"])
    )
    notes["removed_invalid_price"] = int(bad_price.sum())
    df = df[~bad_price]

    # --- 6. Drop the source columns we don't keep --------------------------
    df = df.drop(columns=DROP_COLUMNS + ["product_category_tree", "image"])

    # --- 7. Rename to target schema ----------------------------------------
    df = df.rename(columns=RENAME_MAP)

    # --- 8. Final column order to match products table ----------------------
    final_columns = [
        "id", "name", "brand", "category", "price", "original_price",
        "rating", "description", "image_url", "product_url",
        "product_specifications",
    ]
    df = df[final_columns]

    df.to_csv(OUT_PATH, index=False)

    notes["final_count"] = len(df)
    notes["total_removed"] = start_count - len(df)
    write_notes(notes)

    print(f"Done. {start_count} -> {len(df)} rows. See {NOTES_PATH} for details.")


def write_notes(n):
    content = f"""# CLEANING_NOTES.md

## Source
`data/raw/flipkart_com-ecommerce_sample.csv` — {n['start_count']} rows, 15 columns.

## Changes made

1. **Removed exact duplicate rows:** {n['exact_dupes_removed']}
2. **Removed duplicate products (same `pid`, re-listed under a different `uniq_id`):** {n['duplicate_pid_removed']}
3. **Derived `category`** from `product_category_tree` (which arrives as a nested
   breadcrumb string, e.g. `["Clothing >> Women's Clothing >> ... >> Shorts"]`) by taking
   the **top-level segment** (`Clothing`). Rows where the tree had only one level
   (no `>>` at all — meaning the "category" was actually just the product name
   repeated) were treated as missing category.
4. **Standardized category casing:** trimmed whitespace and applied consistent
   Title Case, so `"hiking shoes"` / `"HIKING SHOES"` / `"Hiking Shoes"` all
   collapse to the same value.
5. **Derived `image_url`** from the `image` column (also a list-like string of
   URLs) by taking the first URL.
6. **Removed rows missing critical fields:**
   - Missing `name`: {n['removed_missing_name']}
   - Missing price (`retail_price` or `discounted_price` blank): {n['removed_missing_price']}
   - Missing/malformed `category`: {n['removed_missing_category']}
   - **Total removed for missing critical fields:** {n['total_missing_critical_removed']}
7. **Removed rows with invalid prices:** {n['removed_invalid_price']}
   (non-numeric, zero/negative, or discounted price higher than retail price)
8. **Dropped unused source columns** (per `docs/project-plan.md` §10.1):
   `crawl_timestamp`, `pid`, `is_FK_Advantage_product`, `overall_rating`.
9. **Renamed columns** to match the `products` table schema:
   `uniq_id`→`id`, `product_name`→`name`, `discounted_price`→`price`,
   `retail_price`→`original_price`, `product_rating`→`rating`.

## Row counts

| Stage | Count |
|---|---|
| Starting rows | {n['start_count']} |
| Final rows | {n['final_count']} |
| **Total rows removed** | **{n['total_removed']}** |

## Assumptions made

- **Category = top-level segment of the breadcrumb, not the leaf.** The leaf
  segment is nearly as unique as the product name itself (e.g. `"Alisha Solid
  Women's Cycling Shorts"`), which isn't useful as a filterable category. The
  top-level segment (`"Clothing"`, `"Footwear"`, `"Electronics"`, etc.) is what
  the app's filter/search experience actually needs.
- **`brand` is not treated as a critical field**, even though ~29% of rows are
  missing it — the task listed name/price/category as critical, and brand is
  genuinely absent in the source data for many legitimate listings (not a data
  quality bug).
- **`rating` is kept as-is (text), including the literal value
  `"No rating available"`**, since the target schema defines `rating` as TEXT,
  not a numeric column — no conversion was needed or attempted.
- **A row needs *both* `retail_price` and `discounted_price` present** to be
  considered to have a valid price, since the schema keeps both as separate
  columns (`price` and `original_price`).
"""
    with open(NOTES_PATH, "w", encoding="utf-8") as f:
        f.write(content)


if __name__ == "__main__":
    main()