import { useEffect, useState } from 'react'

// 라이트/다크. 처음에는 시스템 설정을 따르고, 한 번 고르면 기억한다
type Theme = 'light' | 'dark'
const KEY = 'bandal.theme'

function systemTheme(): Theme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function savedTheme(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null
  }
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => savedTheme() ?? systemTheme())

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    try {
      localStorage.setItem(KEY, next)
    } catch {
      // 저장이 막혀도 이번 탭에서는 바뀐다
    }
    setTheme(next)
  }

  return { theme, toggle }
}
