import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface CartLine {
  refId: number;
  sourceType: 'C2C_ITEM' | 'B2C_PRODUCT';
  title: string;
  price: number;
}

interface CartState {
  lines: CartLine[];
  add: (line: CartLine) => void;
  remove: (refId: number, sourceType: string) => void;
  clear: () => void;
  total: () => number;
}

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      lines: [],
      add: (line) => set({ lines: [...get().lines, line] }),
      remove: (refId, sourceType) =>
        set({ lines: get().lines.filter((l) => !(l.refId === refId && l.sourceType === sourceType)) }),
      clear: () => set({ lines: [] }),
      total: () => get().lines.reduce((sum, l) => sum + l.price, 0),
    }),
    { name: 'ftsm-cart' }
  )
);
