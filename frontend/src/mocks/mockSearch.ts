// Very simple keyword + budget matcher over the mock catalog.
// This stands in for what Gemma + Postgres full-text search will do later.

import { mockProducts } from "./mockProducts";
import type { Product } from "../types";

interface MockSearchResult {
  products: Product[];
  budget: number | null;
  keywords: string[];
}

export function mockSearchProducts(message: string): MockSearchResult {
  const lowerMessage = message.toLowerCase();

  // Extract a budget if the user mentioned "under $X" or "$X" or "below X"
  const budgetMatch = lowerMessage.match(/(?:under|below|less than)?\s*\$?(\d+)/);
  const budget = budgetMatch ? parseInt(budgetMatch[1], 10) : null;

  // Pull out simple keywords by splitting on spaces and removing common filler words
  const stopWords = ["i", "need", "want", "a", "the", "for", "under", "below", "than", "less", "some", "me", "find", "show"];
  const keywords = lowerMessage
    .replace(/\$?\d+/g, "") // remove numbers/prices from keyword matching
    .split(/\s+/)
    .filter((word) => word.length > 2 && !stopWords.includes(word));

  // Score each product by how many keywords match its name/brand/category/description
  const scored = mockProducts.map((product) => {
    const searchable = `${product.name} ${product.brand} ${product.category} ${product.description}`.toLowerCase();
    let score = 0;
    for (const kw of keywords) {
      if (searchable.includes(kw)) score += 1;
    }
    return { product, score };
  });

  // Filter to products that matched at least one keyword (or all, if no keywords given)
  let candidates = keywords.length > 0 ? scored.filter((s) => s.score > 0) : scored;

  // Apply budget filter if one was mentioned
  if (budget !== null) {
    candidates = candidates.filter((s) => s.product.price <= budget);
  }

  // Sort by best match first, then take top 5
  candidates.sort((a, b) => b.score - a.score);
  const products = candidates.slice(0, 5).map((s) => s.product);

  return { products, budget, keywords };
}