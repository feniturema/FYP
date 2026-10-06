export interface AuthResponse {
  token: string;
  userId: number;
  name: string;
  email: string;
  role: 'STUDENT' | 'ADMIN';
}

export interface UserProfile {
  userId: number;
  name: string;
  email: string;
  role: 'STUDENT' | 'ADMIN';
}

export interface Item {
  id: number;
  sellerId: number;
  title: string;
  description?: string;
  price: number;
  category?: string;
  condition?: string;
  imageUrl?: string;
  status: string;
  createdAt: string;
}

export interface Product {
  id: number;
  name: string;
  description?: string;
  price: number;
  imageUrl?: string;
  totalStock: number;
  category?: string;
  createdAt: string;
}

export interface SeckillEvent {
  id: number;
  productId: number;
  productName: string;
  imageUrl?: string;
  originalPrice?: number;
  seckillPrice: number;
  seckillStock: number;
  startTime: string;
  endTime: string;
  status: 'PENDING' | 'ACTIVE' | 'ENDED';
}

export interface SeckillBuyResponse {
  result: 'ACCEPTED' | 'SOLD_OUT' | 'ALREADY_BOUGHT' | 'NOT_ACTIVE' | 'UNAVAILABLE';
  trackingToken?: string;
  message: string;
}

export interface SeckillResult {
  trackingToken: string;
  orderStatus: 'PENDING' | 'PAID' | 'FAILED' | 'NOT_FOUND';
  orderId?: number;
}

export interface Order {
  id: number;
  buyerId: number;
  sourceType: string;
  refId: number;
  amount: number;
  paymentMethod?: string;
  status: string;
  createdAt: string;
}

export interface Review {
  id: number;
  authorId: number;
  targetType: 'ITEM' | 'PRODUCT' | 'SELLER';
  targetRefId: number;
  rating: number;
  comment?: string;
  createdAt: string;
}

export interface ReviewSummary {
  targetType: 'ITEM' | 'PRODUCT' | 'SELLER';
  targetRefId: number;
  averageRating: number;
  count: number;
  reviews: Review[];
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  /** true while the assistant bubble is still receiving streamed tokens */
  streaming?: boolean;
}

// ---- Catalogue search (GET /api/search, P5a) ----
export type SearchType = 'product' | 'item' | 'all';

export interface SearchFilters {
  maxPrice?: number;
  category?: string;
  type?: SearchType;
}

export interface SearchParams extends SearchFilters {
  q: string;
  limit?: number;
}

export interface SearchHit {
  ref: string;
  type: 'product' | 'item';
  id: number;
  title: string;
  price: number;
  category?: string | null;
  imageUrl?: string | null;
  score: number;
}

export interface SearchResponse {
  query: string;
  effectiveMode: string;
  hits: SearchHit[];
}
