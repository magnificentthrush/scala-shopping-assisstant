# Queryable Catalog Report — what users can actually ask for

**Generated:** 2026-08-11, via Supabase MCP `execute_sql` against project `ecommerse_ai_assisstant` (`ymhanlwqjotenkxgokcx`), live `products` table (19,595 rows, 32 categories).

**Purpose:** map the variety of shopping queries this catalog can answer *well* — i.e. queries where the retrieval pipeline (FTS rung 1: `category` + `plainto_tsquery` terms + `price <= budget`) returns a deep, relevant result set — and name the queries that are most ideal for demos and testing. Complements `docs/retrievalPlan.md` §2 (which covered data quality); this report covers *query coverage*.

**Method:** per-category price/brand/rating stats; `ts_stat` over product **names** per top category (cleaner than `search_vector`, which is polluted by spec-sheet boilerplate — the top lexemes across `search_vector` are `key`, `valu`, `type`, `rs`, `flipkart.com`); ~70 intent-term probes against the GIN FTS index; and 9 end-to-end "ideal query" simulations (category + terms + budget, with sample rows).

---

## 1. Where the depth is (category strength tiers)

| Tier | Categories | Rows | Verdict |
| --- | --- | --- | --- |
| **Deep — demo-safe** | Clothing (6,170), Jewellery (3,522), Footwear (1,225), Mobiles & Accessories (1,097), Automotive (1,010), Home Decor & Festive Needs (927) | ~14k | Rich result sets for almost any mainstream query |
| **Workable** | Beauty & Personal Care (709), Home Furnishing (700), Kitchen & Dining (645), Computers (572), Watches (528), Baby Care (481), Tools & Hardware (387), Toys (329), Pens & Stationery (313), Bags/Wallets/Belts (264) | ~6k | Good for the specific product types listed in §2 |
| **Thin — avoid in demos** | Furniture (180), Sports & Fitness (166), Cameras (72), and everything under 80 rows (Sunglasses 35, Gaming 35, Pet Supplies 29, … Automation & Robotics 1) | <1k | Sparse; only generic queries work |

Prices are INR everywhere. Median price by category ranges from ₹299 (Kitchen) to ₹2,364 (Furniture); a "reasonable budget" is ₹500–2,000 for most categories.

## 2. What users can query, per category (verified vocabulary)

Product-type nouns below are the **actual top words in product names** per category (`ts_stat`), with FTS match counts for intent terms across the whole catalog.

### Clothing (6,170 rows) — strongest category
- **Deep:** women's wear in general (`women` in 3,665 names), shirts (2,240 catalog-wide), **t-shirts (1,179 in-name)**, casual wear (5,635), printed/solid styles, bras (1,061 — a known noise source, see §4), tops, dresses (739)
- **Workable:** ethnic wear (931), kurti (284), jeans (304), jackets (112), saree (71 — thin)
- **Intent terms that hit:** cotton (4,471), silk (234), denim (188), formal (808)

### Jewellery (3,522) — second strongest, 100% brand coverage
- **Deep:** necklaces (1,667), rings (897), bangles (708), gold-plated/alloy fashion jewellery (names: `gold` 1,090, `alloy` 1,388, `diamond` 648, `18k` plating)
- **Workable:** bracelets (344), earrings (279), pendants (148), silver (273)

### Footwear (1,225) — women's fashion-leaning
- **Deep:** heels (1,086 catalog-wide), wedges, boots (211), loafers, flats, bellies, women's styles (`women` in 470 names)
- **Thin:** sandals (362), slippers (64), running (84) / sneakers (48) / sports — athletic footwear is weak; **hiking is effectively absent (11 rows catalog-wide)**

### Mobiles & Accessories (1,097) — mostly Apple-ecosystem accessories
- **Deep:** iPad covers/cases (`ipad` in 661 names, `cover` 714), iPhone/iPad cables, Samsung Galaxy accessories, flip covers
- **Thin:** wireless (219), bluetooth (57), headphones (63), earphones (31), speakers (62), chargers (126) — audio accessories exist but are sparse; "bluetooth earphones under 1000" returns only **5 rows**

### Automotive (1,010)
- **Deep:** car mats (names: `car` 676, `mat` 547), sun shades, vehicle covers, model-specific fitments (Maruti 123, Hyundai 85, Tata 77, Toyota 55, Chevrolet 54)
- 100% brand coverage; helmets thin (32)

### Home Decor & Festive Needs (927)
- **Deep:** showpieces (443), wall stickers (121), wall clocks (78, mostly analog), paintings/canvas, rice-light decorative lighting, diyas (festive)

### Home Furnishing (700)
- **Deep:** curtains (213; door/window, eyelet style), cushion covers (385), blankets, bedsheets (42 — thin but present); cotton/polyester, floral/abstract patterns

### Kitchen & Dining (645)
- **Deep:** mugs (307; mostly ceramic printed), kadhai (90), pizza cutters, bottles (164), LED bulbs bleed into this category
- **Thin:** knives (31), cookers (12)

### Computers (572)
- **Deep:** USB accessories (162), laptop accessories/batteries (140), routers (88), LED/usb lights, HP Pavilion parts, adapters
- **Thin:** mice (73), keyboards (21), printers (42), pendrives (6)

### Watches (528) — small but extremely well-formed for queries
- **Deep:** analog watches (455 of 522 names say "analog watch"; 546 catalog-wide), men's (258) and women's (215), boys'/girls' (116/83)
- **Brands present:** Maxima (72), Sonata (60), Fastrack (20); digital (208) and chronograph (203) workable

### Beauty & Personal Care (709)
- **Deep:** combo gift sets (426), creams (148), deodorants/perfumes (48/52), SPF/sunscreen (41), vitamin/fruit-based skincare, massage
- **Thin:** lipstick (27), shampoo (20), facewash (32), kajal — specific single-product queries underperform

### Baby Care (481)
- Mostly **baby wall stickers/room decor** (194) plus rompers and baby clothing — not a care-products catalog

## 3. Ideal queries (validated end-to-end)

Each of these was run against the live table exactly as the pipeline's rung 1 would (`category=eq` + `plainto_tsquery` + `price <= budget`). All return deep, on-topic result sets — these are the best demo/test queries:

| # | User query | Filters it should produce | Matches | Cheapest samples (live) |
| --- | --- | --- | --- | --- |
| 1 | "I want a men's analog watch under 2000" | `Watches` + `analog, men` + ≤₹2,000 | **222** | LUBA ty47 Stylo Analog Watch ₹199 · Luba er23 ₹225 |
| 2 | "Show me gold necklaces under 1000" | `Jewellery` + `gold, necklace` + ≤₹1,000 | **624** | VR Designers Glass Necklace ₹140 · Galz4ever Alloy Necklace ₹149 |
| 3 | "Women's heels under 1000" | `Footwear` + `heels, women` + ≤₹1,000 | **459** | (fashion footwear, ₹149–249) |
| 4 | "Ceramic mugs under 500" | `Kitchen & Dining` + `ceramic, mug` + ≤₹500 | **301** | Prithish Abstract Ceramic Mug ₹125 |
| 5 | "iPad cover under 1000" | `Mobiles & Accessories` + `ipad, cover` + ≤₹1,000 | **353** | Bling Book Case for iPad ₹99 |
| 6 | "Car floor mats under 2000" | `Automotive` + `car, mat` + ≤₹2,000 | **522** | ManeKo anti-slip mat combos ₹245+ |
| 7 | "Wireless router under 2000" | `Computers` + `wireless, router` + ≤₹2,000 | **85** | Wi-Bridge APW40-01 ₹600 · D-Link DSL-2520U ₹800 |
| 8 | "Door curtains under 1000" | `Home Furnishing` + `curtain, door` + ≤₹1,000 | **35** | Shopgalore Eyelet Door Curtain ₹226 |
| 9 | "Wall clock for the living room" | `Home Decor & Festive Needs` + `clock` | 78 in-name | analog wall clocks, ~₹300–800 |

**The single most ideal query for this dataset:** *"I want a men's analog watch under 2000."* It combines the two cleanest signals the catalog has — a well-grounded category (`Watches`), a term (`analog`) that appears in 86% of watch names, a gender word the gender gate can use, and a budget that fits the ₹768 median — and returns 222 relevant rows at rung 1 with zero fallback.

**The ideal query *shape*:** `<category noun> + <one style/material/type word that appears in names> + <gender where relevant> + <budget ₹500–2,000>`. All four parts map to a real index signal.

## 4. Known traps (queries that look fine but aren't)

| Query pattern | What actually happens | Why |
| --- | --- | --- |
| "cotton t-shirt" | 135 matches, but cheapest results are **T-Shirt Bras** | `bra` appears in 1,061 clothing names; FTS can't distinguish "t-shirt bra" from "t-shirt". The new Call #3 relevance re-check exists precisely for this. For demos prefer "casual printed shirt" or "polo t-shirt men". |
| "hiking boots" (any budget) | **0 matches**; only 11 "hiking" rows in the entire catalog | Documented coverage gap (retrievalPlan §2). Expect the no-match / pending-offer flow. |
| "bluetooth earphones under 1000" | 5 matches | Audio accessories are a sliver of Mobiles & Accessories (mostly iPad/iPhone covers). |
| "saree under 1000" | 25 matches | Ethnic wear is present (931 "ethnic") but saree-specific stock is thin. |
| Budgets under ~₹150 | Near-zero everywhere | Min price is ₹35 and medians are ₹300–900; "under 100" queries are effectively empty. |
| Generic single-word queries ("shoes", "phone") | Huge unranked pools (601 "shoes" rows) | These exercise the reranker, not retrieval; fine for testing, boring for demos. |

## 5. Reproducing this report

- Category stats: see `data/scripts/catalog_report.py` (same shape as §1).
- Vocabulary: `SELECT word, ndoc FROM ts_stat('SELECT to_tsvector(''english'', name) FROM products WHERE category = ''<Category>''') ORDER BY ndoc DESC LIMIT 15;`
- Term probes: `SELECT count(*) FROM products WHERE search_vector @@ plainto_tsquery('english', '<term>');`
- Ideal-query validation: `category = '<Category>' AND search_vector @@ plainto_tsquery('english', '<terms>') AND price <= <budget>` — exactly the rung-1 query in `SupabaseProductProvider`.
