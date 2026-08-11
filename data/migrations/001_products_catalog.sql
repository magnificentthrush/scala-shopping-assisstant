-- Products catalog (see docs/database-schema.md).
-- Structure only — seeding the catalog is a separate step, see data/seed/seed_products.py.
CREATE TABLE IF NOT EXISTS products (
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

-- Full-text retrieval support
ALTER TABLE products
  ADD COLUMN IF NOT EXISTS search_vector tsvector
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

CREATE INDEX IF NOT EXISTS products_search_idx ON products USING GIN (search_vector);
CREATE INDEX IF NOT EXISTS products_price_idx ON products (price);
CREATE INDEX IF NOT EXISTS products_category_idx ON products (category);
