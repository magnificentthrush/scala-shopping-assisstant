// Displays a single product as a card
// ChatWidget renders this component for each product returned by the assistant

import type { Product } from "../../types";

interface ProductCardProps {
  product: Product;
}

export default function ProductCard({ product }: ProductCardProps) {
  const hasDiscount =
    product.originalPrice && product.originalPrice > product.price;

  return (
    <div className="border border-gray-200 rounded-xl p-3 w-56 shrink-0 bg-white shadow-sm hover:shadow-md transition-shadow">
      <img
        src={product.imageUrl || "https://placehold.co/200x200?text=No+Image"}
        alt={product.name}
        className="w-full h-32 object-cover rounded-lg mb-2"
      />

      <h3 className="text-sm font-semibold line-clamp-2 text-gray-800">
        {product.name}
      </h3>

      {product.brand && (
        <p className="text-xs text-gray-500">{product.brand}</p>
      )}

      <div className="flex items-center gap-2 mt-1">
        <span className="text-sm font-bold text-gray-900">
          ${product.price}
        </span>
        {hasDiscount && (
          <span className="text-xs text-gray-400 line-through">
            ${product.originalPrice}
          </span>
        )}
      </div>

      {product.rating && (
        <p className="text-xs text-yellow-600 mt-1">⭐ {product.rating}</p>
      )}

      {product.productUrl ? (
        <a
          href={product.productUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="text-xs text-blue-600 mt-2 inline-block hover:underline"
        >
          View product →
        </a>
      ) : null}
    </div>
  );
}