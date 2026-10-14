import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, LOGGED_OUT_EVENT, tokenStore, type Me } from './api'

interface AuthState {
  me: Me | null
  // 토큰은 있는데 내 정보를 아직 못 받은 상태
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  reload: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.get() !== null)

  const reload = useCallback(async () => {
    if (!tokenStore.get()) {
      setMe(null)
      setLoading(false)
      return
    }
    try {
      setMe(await api.me())
    } catch {
      tokenStore.clear()
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
    // 갱신까지 실패하면 api.ts가 알린다. 로그인 화면으로 돌아간다
    const onLoggedOut = () => setMe(null)
    window.addEventListener(LOGGED_OUT_EVENT, onLoggedOut)
    return () => window.removeEventListener(LOGGED_OUT_EVENT, onLoggedOut)
  }, [reload])

  const login = useCallback(
    async (email: string, password: string) => {
      await api.login(email, password)
      await reload()
    },
    [reload],
  )

  const logout = useCallback(async () => {
    await api.logout()
    setMe(null)
  }, [])

  return <AuthContext.Provider value={{ me, loading, login, logout, reload }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('AuthProvider 안에서만 쓸 수 있습니다')
  return ctx
}
