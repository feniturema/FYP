import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { AuthResponse, UserProfile } from '../types';

interface AuthState {
  token: string | null;
  user: UserProfile | null;
  isAuthenticated: () => boolean;
  isAdmin: () => boolean;
  setSession: (auth: AuthResponse) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      token: null,
      user: null,
      isAuthenticated: () => !!get().token,
      isAdmin: () => get().user?.role === 'ADMIN',
      setSession: (auth) =>
        set({
          token: auth.token,
          user: {
            userId: auth.userId,
            name: auth.name,
            email: auth.email,
            role: auth.role,
          },
        }),
      logout: () => set({ token: null, user: null }),
    }),
    { name: 'ftsm-auth' }
  )
);
