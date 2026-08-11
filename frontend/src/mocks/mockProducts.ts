// Small fake product catalog — used only until the real backend has product search.
// productUrl points to a search results page on the brand's real site (since
// these aren't real SKUs, there's no exact product page to link to).
import type { Product } from "../types";

export const mockProducts: Product[] = [
  { id: "1", name: "Nike Air Zoom Pegasus 40", brand: "Nike", category: "shoes", price: 89.99, originalPrice: 120, rating: "4.5", description: "Lightweight running shoe.", imageUrl: "https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=400", productUrl: "https://www.nike.com/w?q=Air%20Zoom%20Pegasus%2040" },
  { id: "2", name: "Nike Revolution 6", brand: "Nike", category: "shoes", price: 64.99, originalPrice: 75, rating: "4.2", description: "Everyday comfort running shoe.", imageUrl: "https://images.unsplash.com/photo-1608231387042-66d1773070a5?w=400", productUrl: "https://www.nike.com/w?q=Revolution%206" },
  { id: "3", name: "Adidas Ultraboost 22", brand: "Adidas", category: "shoes", price: 149.99, originalPrice: 180, rating: "4.7", description: "Premium running shoe.", imageUrl: "https://images.unsplash.com/photo-1543508282-6319a3e2621f?w=400", productUrl: "https://www.adidas.com/us/search?q=Ultraboost%2022" },
  { id: "4", name: "Wireless Bluetooth Headphones", brand: "Sony", category: "electronics", price: 79.99, originalPrice: 99.99, rating: "4.6", description: "Noise cancelling headphones.", imageUrl: "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=400", productUrl: "https://www.sony.com/electronics/search?q=wireless%20bluetooth%20headphones" },
  { id: "5", name: "Classic Denim Jacket", brand: "Levi's", category: "jackets", price: 69.99, originalPrice: 89.99, rating: "4.5", description: "Timeless denim jacket.", imageUrl: "https://images.unsplash.com/photo-1551028719-00167b16eac5?w=400", productUrl: "https://www.levi.com/US/en_US/search?q=denim%20jacket" },
  { id: "6", name: "Stainless Steel Water Bottle", brand: "Hydro Flask", category: "accessories", price: 34.99, rating: "4.8", description: "Insulated water bottle.", imageUrl: "https://images.unsplash.com/photo-1602143407151-7111542de6e8?w=400", productUrl: "https://www.hydroflask.com/search?q=water%20bottle" },
];