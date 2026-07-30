# Database Schema

The application uses PostgreSQL to store the product catalog. For the MVP, the database has one main table: `products`.

## Products table

```sql
CREATE TABLE products (
  id                      TEXT PRIMARY KEY,
  name                    TEXT NOT NULL,
  brand                   TEXT,
  category                TEXT,
  price                   NUMERIC,
  original_price          NUMERIC,
  rating                  TEXT,
  description             TEXT,
  image_url               TEXT,
  product_url             TEXT,
  product_specifications  TEXT
);
```

### Columns


| Column                   | Type      | Required | Purpose                                                   |
| ------------------------ | --------- | -------- | --------------------------------------------------------- |
| `id`                     | `TEXT`    | Yes      | Unique product ID, sourced from `uniq_id`.                |
| `name`                   | `TEXT`    | Yes      | Product name shown to the user.                           |
| `brand`                  | `TEXT`    | No       | Product manufacturer or brand.                            |
| `category`               | `TEXT`    | No       | Normalized category parsed from the source category tree. |
| `price`                  | `NUMERIC` | No       | Current or discounted product price.                      |
| `original_price`         | `NUMERIC` | No       | Original retail price before discount.                    |
| `rating`                 | `TEXT`    | No       | Product rating from the source dataset.                   |
| `description`            | `TEXT`    | No       | Product description used for display and search.          |
| `image_url`              | `TEXT`    | No       | First product image URL from the source data.             |
| `product_url`            | `TEXT`    | No       | Link to the original product page.                        |
| `product_specifications` | `TEXT`    | No       | Product attributes and technical specifications.          |




## Full-text search

PostgreSQL generates a searchable document from the fields that best describe each product:

```sql
ALTER TABLE products
  ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    to_tsvector(
      'english',
      coalesce(name, '') || ' ' ||
      coalesce(brand, '') || ' ' ||
      coalesce(category, '') || ' ' ||
      coalesce(description, '') || ' ' ||
      coalesce(product_specifications, '')
    )
  ) STORED;
```

`coalesce` converts missing values to empty strings, preventing a null field from making the complete search document null.

## Indexes

```sql
CREATE INDEX products_search_idx ON products USING GIN (search_vector);
CREATE INDEX products_price_idx ON products (price);
CREATE INDEX products_category_idx ON products (category);
```

- `products_search_idx` speeds up full-text product searches.
- `products_price_idx` speeds up budget and price-range filters.
- `products_category_idx` speeds up category filters.



## Source data mapping

The catalog is loaded from `data/raw/flipkart_com-ecommerce_sample.csv`.


| Source CSV column        | Database column          | Transformation                       |
| ------------------------ | ------------------------ | ------------------------------------ |
| `uniq_id`                | `id`                     | Use as-is.                           |
| `product_name`           | `name`                   | Use as-is.                           |
| `brand`                  | `brand`                  | Use as-is; may be empty.             |
| `product_category_tree`  | `category`               | Parse the leaf or primary category.  |
| `discounted_price`       | `price`                  | Convert to a numeric value.          |
| `retail_price`           | `original_price`         | Convert to a numeric value.          |
| `product_rating`         | `rating`                 | Use as text.                         |
| `description`            | `description`            | Use as-is.                           |
| `image`                  | `image_url`              | Extract the first URL from the list. |
| `product_url`            | `product_url`            | Use as-is.                           |
| `product_specifications` | `product_specifications` | Use as-is.                           |


The seed process should discard source-only fields such as `crawl_timestamp`, `pid`, `is_FK_Advantage_product`, and `overall_rating`.

## How the application uses the schema

1. Gemma 4 extracts keywords and filters such as category and budget.
2. PostgreSQL performs full-text search using `search_vector`.
3. SQL applies category and price filters.
4. The database returns up to 30 candidates.
5. The service layer reranks those candidates and returns the best 5 products.

This document describes the planned MVP schema. Any schema change should be reflected here and in the corresponding database migration.