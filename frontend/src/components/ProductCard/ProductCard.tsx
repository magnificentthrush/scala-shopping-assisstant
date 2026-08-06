import { ExternalLink, Star } from "lucide-react";
import type { Product } from "../../types";

interface ProductCardProps {
  product: Product;
}

export default function ProductCard({ product }: ProductCardProps) {
  const hasDiscount = product.originalPrice && product.originalPrice > product.price;

  return (
    <article className="product-card">
      <div className="product-card__image-wrap">
        <img
          src={product.imageUrl || "https://placehold.co/460x340/f1f1f1/777?text=No+Image"}
          alt={product.name}
          className="product-card__image"
          loading="lazy"
        />
      </div>
      <div className="product-card__body">
        {product.brand ? <p className="product-card__brand">{product.brand}</p> : null}
        <h3>{product.name}</h3>

        <div className="product-card__price-row">
          <span className="product-card__price">${product.price}</span>
        {hasDiscount && (
            <span className="product-card__original">${product.originalPrice}</span>
        )}
        </div>

        {product.rating ? (
          <p className="product-card__rating">
            <Star size={12} fill="currentColor" strokeWidth={1.5} aria-hidden="true" /> {product.rating}
          </p>
        ) : null}

        {product.productUrl ? (
          <a href={product.productUrl} target="_blank" rel="noopener noreferrer" className="product-card__link">
            View product
            <ExternalLink size={13} strokeWidth={1.7} aria-hidden="true" />
          </a>
        ) : null}
      </div>
    </article>
  );
}