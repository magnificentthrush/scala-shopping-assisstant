// Defines the TypeScript data models used throughout the application
// These interfaces match the Scala backend case classes (see docs/API_CONTRACT.md)

// Data structure for a single product
export interface Product {
  id: string;
  name: string;
  brand?: string;              // Optional field - not every product has a brand
  category: string;
  price: number;
  originalPrice?: number;      // Price before the discount was applied
  rating?: string;
  description?: string;
  imageUrl?: string;
  productUrl?: string;
  productSpecifications?: string;
}

// Data structure for a single chat message
// Represents either a user message or an assistant response
export interface ConversationTurn {
  role: "user" | "assistant";   // Only these two values are allowed
  content: string;              // Text content of the message
}

export interface Message extends ConversationTurn {
  id: string;
  sequenceNumber: number;
  createdAt: string;
  products?: Product[];
}

export interface ConversationSummary {
  id: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  lastMessageAt: string;
}

export interface User {
  id: string;
  fullName: string;
  email: string;
}

export interface AuthResponse {
  user: User;
  token: string;
}

// Data structure returned by the backend for an assistant response
export interface AssistantReply {
  mode: "recommend" | "info" | "clarify" | "other";
  reply: string;                 // Assistant's text response
  products: Product[];           // Product recommendations, if available
  followUpQuestion?: string;     // Optional follow-up question from the assistant
}