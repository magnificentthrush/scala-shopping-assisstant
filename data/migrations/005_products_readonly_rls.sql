-- Make the products catalog read-only for Data API roles (anon / authenticated).
-- SELECT is allowed for everyone; INSERT / UPDATE / DELETE have no policies, so
-- those roles cannot write. The service_role / secret key still bypasses RLS
-- (needed for intentional re-seeds via data/seed/seed_products.py).

ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS products_select_public ON public.products;

CREATE POLICY products_select_public
  ON public.products
  FOR SELECT
  TO anon, authenticated
  USING (true);
