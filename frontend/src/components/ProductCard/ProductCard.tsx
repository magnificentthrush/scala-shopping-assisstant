import { useState } from "react";
import { ExternalLink, Star } from "lucide-react";
import type { Product } from "../../types";

interface ProductCardProps {
  product: Product;
}

const PLACEHOLDER_IMAGE = "https://placehold.co/460x340/f1f1f1/777?text=No+Image";

// Flipkart's CDN serves any asset at an arbitrary size by inserting
// "{width}/{height}" after /image/, and the rukminim1 edge serves the same
// assets over plain HTTPS (img5a/img6a 403 without a Flipkart referer).
// Cards render at 4:3, so request ~460x340 instead of the 1100x1100 original.
function cdnImageVariants(url: string): string[] {
  const httpsUrl = url.replace(/^http:\/\//, "https://");
  const match = httpsUrl.match(/^https:\/\/img\d+a\.flixcart\.com\/image\/(.+)$/);
  if (!match) return [httpsUrl];
  const path = match[1];
  const resized = `https://rukminim1.flixcart.com/image/460/340/${path}`;
  const original = `https://rukminim1.flixcart.com/image/${path}`;
  return resized === original ? [original] : [resized, original];
}

function formatInr(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}

export default function ProductCard({ product }: ProductCardProps) {
  const hasDiscount = product.originalPrice && product.originalPrice > product.price;
  const variants = product.imageUrl
    ? [...cdnImageVariants(product.imageUrl), PLACEHOLDER_IMAGE]
    : [PLACEHOLDER_IMAGE];
  const [variantIndex, setVariantIndex] = useState(0);

  return (
    <article className="product-card">
      <div className="product-card__image-wrap">
        <img
          src={variants[variantIndex]}
          alt={product.name}
          className="product-card__image"
          loading="lazy"
          decoding="async"
          width="460"
          height="340"
          onError={() => setVariantIndex((i) => Math.min(i + 1, variants.length - 1))}
        />
      </div>
      <div className="product-card__body">
        {product.brand ? <p className="product-card__brand">{product.brand}</p> : null}
        <h3>{product.name}</h3>

        <div className="product-card__price-row">
          <span className="product-card__price">{formatInr(product.price)}</span>
          {hasDiscount && (
            <>
              <span className="product-card__original">{formatInr(product.originalPrice!)}</span>
              <span className="product-card__discount">
                {Math.round((1 - product.price / product.originalPrice!) * 100)}% off
              </span>
            </>
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
