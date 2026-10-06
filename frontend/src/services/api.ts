import api from './axios';
import type {
  AuthResponse, Item, Product, SeckillEvent, SeckillBuyResponse,
  SeckillResult, Order, Review, ReviewSummary, SearchParams, SearchResponse,
} from '../types';
import { streamAssistant } from './sse';

// ---- Auth ----
export const authApi = {
  register: (data: { name: string; email: string; password: string }) =>
    api.post<{ message: string }>('/auth/register', data).then((r) => r.data),
  verifyOtp: (data: { email: string; code: string }) =>
    api.post<AuthResponse>('/auth/verify-otp', data).then((r) => r.data),
  resendOtp: (data: { email: string }) =>
    api.post<{ message: string }>('/auth/resend-otp', data).then((r) => r.data),
  login: (data: { email: string; password: string }) =>
    api.post<AuthResponse>('/auth/login', data).then((r) => r.data),
};

// ---- Catalogue search (public, P5a) ----
export const searchApi = {
  search: (params: SearchParams, signal?: AbortSignal) =>
    api.get<SearchResponse>('/search', { params, signal }).then((r) => r.data),
};

// ---- Marketplace (C2C items) ----
export const itemApi = {
  list: (params?: { q?: string; category?: string }) =>
    api.get<Item[]>('/items', { params }).then((r) => r.data),
  get: (id: number) => api.get<Item>(`/items/${id}`).then((r) => r.data),
  create: (data: Partial<Item>) => api.post<Item>('/items', data).then((r) => r.data),
  update: (id: number, data: Partial<Item>) =>
    api.put<Item>(`/items/${id}`, data).then((r) => r.data),
  remove: (id: number) => api.delete(`/items/${id}`).then((r) => r.data),
};

// ---- B2C products ----
export const productApi = {
  list: (q?: string) => api.get<Product[]>('/products', { params: { q } }).then((r) => r.data),
  get: (id: number) => api.get<Product>(`/products/${id}`).then((r) => r.data),
};

// ---- SecKill ----
export const seckillApi = {
  events: () => api.get<SeckillEvent[]>('/seckill/events').then((r) => r.data),
  event: (id: number) => api.get<SeckillEvent>(`/seckill/events/${id}`).then((r) => r.data),
  buy: (eventId: number) =>
    api.post<SeckillBuyResponse>(`/seckill/${eventId}/buy`).then((r) => r.data),
  result: (token: string) =>
    api.get<SeckillResult>('/seckill/result', { params: { token } }).then((r) => r.data),
};

// ---- Orders ----
export const orderApi = {
  list: () => api.get<Order[]>('/orders').then((r) => r.data),
  get: (id: number) => api.get<Order>(`/orders/${id}`).then((r) => r.data),
  create: (data: { sourceType: string; refId: number; paymentMethod: string }) =>
    api.post<Order>('/orders', data).then((r) => r.data),
  pay: (id: number, paymentMethod: string) =>
    api.post<Order>(`/orders/${id}/pay`, { paymentMethod }).then((r) => r.data),
};

// ---- Reviews ----
export const reviewApi = {
  list: (targetType: 'ITEM' | 'PRODUCT' | 'SELLER', targetRefId: number) =>
    api.get<ReviewSummary>('/reviews', { params: { targetType, targetRefId } }).then((r) => r.data),
  create: (data: { targetType: 'ITEM' | 'PRODUCT' | 'SELLER'; targetRefId: number; rating: number; comment?: string }) =>
    api.post<Review>('/reviews', data).then((r) => r.data),
};

// ---- Upload ----
export const uploadApi = {
  image: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<{ url: string }>('/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => r.data);
  },
};

// ---- Chat ----
export const chatApi = {
  /** Non-streaming reply (kept for existing callers). */
  send: (message: string, conversationId?: string) =>
    api.post<{ reply: string }>('/chat', { message, conversationId }).then((r) => r.data),
  /** Streaming reply over SSE: POST /api/assistant/stream (see sse.ts). */
  stream: streamAssistant,
};

// ---- Admin ----
export const adminApi = {
  createProduct: (data: Partial<Product>) =>
    api.post<Product>('/admin/products', data).then((r) => r.data),
  updateProduct: (id: number, data: Partial<Product>) =>
    api.put<Product>(`/admin/products/${id}`, data).then((r) => r.data),
  deleteProduct: (id: number) => api.delete(`/admin/products/${id}`).then((r) => r.data),
  createSeckill: (data: {
    productId: number; seckillPrice: number; seckillStock: number;
    startTime: string; endTime: string;
  }) => api.post<SeckillEvent>('/admin/seckill-events', data).then((r) => r.data),
  listSeckill: () => api.get<SeckillEvent[]>('/admin/seckill-events').then((r) => r.data),
  updateSeckill: (id: number, data: {
    seckillPrice: number; seckillStock: number; startTime: string; endTime: string;
  }) => api.put<SeckillEvent>(`/admin/seckill-events/${id}`, data).then((r) => r.data),
  deleteSeckill: (id: number) => api.delete(`/admin/seckill-events/${id}`).then((r) => r.data),
};
