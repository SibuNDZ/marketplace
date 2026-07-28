import React, {
  createContext, useCallback, useContext, useEffect, useState,
} from 'react'
import { auth, bootstrapSession, clearSession, AuthResponse, RegisterResponse } from '../lib/api'

interface AuthUser {
  userId: number
  email: string
  role: 'CUSTOMER' | 'VENDOR' | 'ADMIN'
}

interface AuthCtx {
  user: AuthUser | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  /**
   * Resolves to the registration receipt, NOT a logged-in user. Verification
   * gating means there is no session to set here; the caller routes to the
   * check-your-inbox screen and reads emailSent to decide what it says.
   */
  register: (input: {
    email: string; password: string; firstName: string; lastName: string
    username: string; role: 'CUSTOMER' | 'VENDOR'
  }) => Promise<RegisterResponse>
  logout: () => Promise<void>
}

const Ctx = createContext<AuthCtx>({
  user: null, loading: true,
  login: async () => {},
  register: async () => ({ email: '', emailSent: false }),
  logout: async () => {},
})

function toUser(r: AuthResponse): AuthUser {
  return { userId: r.userId, email: r.email, role: r.role }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    // Silent refresh, then /auth/me to rehydrate the user — loading must hold
    // until BOTH settle, or RequireAuth flashes /login on every reload.
    bootstrapSession()
      .then((ok) => (ok ? auth.me() : null))
      .then((me) => { if (me) setUser(me) })
      .catch(() => { /* stale session — stay logged out */ })
      .finally(() => setLoading(false))
    const onLogout = () => { setUser(null) }
    window.addEventListener('mk:logout', onLogout)
    return () => window.removeEventListener('mk:logout', onLogout)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const r = await auth.login(email, password)
    setUser(toUser(r))
  }, [])

  // Deliberately does NOT setUser: the account exists but cannot sign in
  // until the email is confirmed, so there is no session to reflect here.
  const register = useCallback(async (input: Parameters<typeof auth.register>[0]) => {
    return auth.register(input)
  }, [])

  const logout = useCallback(async () => {
    await auth.logout()
    setUser(null)
    clearSession()
  }, [])

  return (
    <Ctx.Provider value={{ user, loading, login, register, logout }}>
      {children}
    </Ctx.Provider>
  )
}

export function useAuth() { return useContext(Ctx) }
