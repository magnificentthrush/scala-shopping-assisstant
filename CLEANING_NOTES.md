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
   (non-numeric, zero/negative, or discounted price higher than retail price)
8. **Dropped unused source columns** (per `docs/project-plan.md` §10.1):
   `crawl_timestamp`, `pid`, `is_FK_Advantage_product`, `overall_rating`.
9. **Renamed columns** to match the `products` table schema:
   `uniq_id`→`id`, `product_name`→`name`, `discounted_price`→`price`,
   `retail_price`→`original_price`, `product_rating`→`rating`.

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
- **`rating` is kept as-is (text), including the literal value
  `"No rating available"`**, since the target schema defines `rating` as TEXT,
  not a numeric column — no conversion was needed or attempted.
- **A row needs *both* `retail_price` and `discounted_price` present** to be
  considered to have a valid price, since the schema keeps both as separate
  columns (`price` and `original_price`).
