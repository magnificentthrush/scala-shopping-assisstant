// Product card — dark theme version

import type { Product } from "../../types";

interface ProductCardProps {
  product: Product;
}

const currencyFormatter = new Intl.NumberFormat(undefined, {
  style: "currency",
  currency: "USD",
});

export default function ProductCard({ product }: ProductCardProps) {
  const hasDiscount =
    product.originalPrice && product.originalPrice > product.price;

  return (
    <article className="w-56 shrink-0 rounded-xl border border-gray-800 bg-[#1a1a1a] p-3 transition-colors hover:border-gray-600">
      <img
        src={product.imageUrl || "https://placehold.co/200x200?text=No+Image"}
        alt={product.name}
        width={200}
        height={128}
        loading="lazy"
        decoding="async"
        className="w-full h-32 object-cover rounded-lg mb-2"
      />

      <h2 className="line-clamp-2 wrap-break-word text-sm font-semibold text-gray-100">
        {product.name}
      </h2>

      {product.brand && (
        <p className="truncate text-xs text-gray-500">{product.brand}</p>
      )}

      <div className="flex items-center gap-2 mt-1">
        <span className="text-sm font-bold tabular-nums text-white">{currencyFormatter.format(product.price)}</span>
        {hasDiscount && (
          <span className="text-xs tabular-nums text-gray-500 line-through">
            {currencyFormatter.format(product.originalPrice!)}
          </span>
        )}
      </div>

      {product.rating && (
        <p className="text-xs text-yellow-500 mt-1">⭐ {product.rating}</p>
      )}

      {product.productUrl ? (
        <a href={product.productUrl} target="_blank" rel="noopener noreferrer" className="mt-2 inline-block rounded text-xs font-medium text-blue-400 underline-offset-4 hover:text-blue-300 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400">
          View Product <span aria-hidden="true">→</span>
          <span className="sr-only"> (opens in a new tab)</span>
        </a>
      ) : null}
    </article>
  );
}