# CLEANING_NOTES.md

## Source
`data/raw/flipkart_com-ecommerce_sample.csv` — 20000 rows, 15 columns.

## Changes made

1. **Removed exact duplicate rows:** 0
2. **Removed duplicate products (same `pid`, re-listed under a different `uniq_id`):** 2
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
   - Missing `name`: 0
   - Missing price (`retail_price` or `discounted_price` blank): 78
   - Missing/malformed `category`: 325
   - **Total removed for missing critical fields:** 403
7. **Removed rows with invalid prices:** 0
   (non-numeric, zero/negative, or discounted price higher than retail price;
   these rows are excluded entirely, not inserted with a 0 or blank price)
8. **Dropped unused source columns** (per `docs/project-plan.md` §10.1):
   `crawl_timestamp`, `pid`, `is_FK_Advantage_product`, `overall_rating`.
9. **Renamed columns** to match the `products` table schema:
   `uniq_id`→`id`, `product_name`→`name`, `discounted_price`→`price`,
   `retail_price`→`original_price`, `product_rating`→`rating`.
10. **Normalized sentinel/missing values to real NULLs**, instead of passing
    through mixed representations of "no data":
    - `rating`: empty string and the literal `"No rating available"` → NULL.
    - `description`, `image_url`, `product_url`, `brand`: empty/whitespace
      strings → NULL.
11. **Converted `product_specifications` from Ruby hash-rocket syntax to
    valid JSON** (e.g. `{"key"=>"value"}` → `{"key": "value"}`), so it can
    be inserted into a JSON/JSONB column without failing or silently storing
    invalid data. Rows where this couldn't be parsed were set to NULL rather
    than storing the raw garbage:
    - **Unparseable, set to NULL:** 65
12. **Final dedup on `id`** (the actual primary key, sourced from `uniq_id`)
    as a safety net beyond the `pid`-dedup in step 2:
    - **Duplicate `id` rows removed:** 0
    - This is a script-level guard, not a substitute for a `UNIQUE`/`PRIMARY
      KEY` constraint on `products.id` in the schema — that constraint
      should also exist at the DB level so a future load can't silently
      drop or overwrite rows on a duplicate id.

## Row counts

| Stage | Count |
|---|---|
| Starting rows | 20000 |
| Final rows | 19595 |
| **Total rows removed** | **405** |

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
- **`rating` stays a TEXT column** (per the schema — it's not numeric), but
  `""` and the literal sentinel `"No rating available"` are both normalized
  to a true NULL rather than stored as text that looks like data.
- **`product_specifications` is stored as a JSON string**, converted from the
  source's Ruby hash-rocket syntax. Rows where conversion failed are NULL
  rather than storing the raw, invalid string.
- **A row needs *both* `retail_price` and `discounted_price` present** to be
  considered to have a valid price, since the schema keeps both as separate
  columns (`price` and `original_price`).
