import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import type { Status } from './api'
import { STATUS_LABEL, won } from './format'
import { useTheme } from './theme'

// ---------- 토스트 ----------
// 버튼이 실패하면 화면 어디에 있든 아래에 띄운다. 서버의 message를 그대로 보여준다

type ToastKind = 'error' | 'ok'
type Toast = { id: number; message: string; kind: ToastKind }

const ToastContext = createContext<(message: string, kind?: ToastKind) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const push = useCallback((message: string, kind: ToastKind = 'error') => {
    const id = Date.now() + Math.random()
    setToasts((prev) => [...prev.slice(-2), { id, message, kind }])
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), kind === 'error' ? 4200 : 2400)
  }, [])

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast--${t.kind}`} role={t.kind === 'error' ? 'alert' : 'status'}>
            <span className="toast__icon" aria-hidden="true">
              {t.kind === 'error' ? '!' : '✓'}
            </span>
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export const useToast = () => useContext(ToastContext)

// 버튼을 누르는 동안 다시 못 누르게 하고, 실패하면 토스트로 사유를 띄운다. 성공했는지를 돌려준다
export function useAction() {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const run = async (fn: () => Promise<unknown>, success?: string): Promise<boolean> => {
    if (busy) return false
    setBusy(true)
    try {
      await fn()
      if (success) toast(success, 'ok')
      return true
    } catch (e) {
      toast(e instanceof Error ? e.message : '요청을 처리하지 못했어요')
      return false
    } finally {
      setBusy(false)
    }
  }
  return { busy, run }
}

// ---------- 보기 조각 ----------

export function StatusChip({ status }: { status: Status }) {
  return <span className={`chip chip--${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
}

// 최소주문금액까지 얼마나 찼는지
export function Progress({ value, goal }: { value: number; goal: number }) {
  const pct = goal <= 0 ? 100 : Math.min(100, (value / goal) * 100)
  const reached = value >= goal
  return (
    <div className="progress">
      <div
        className="progress__track"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={goal}
        aria-valuenow={Math.min(value, goal)}
        aria-label="최소주문금액 채운 정도"
      >
        <div className={`progress__fill${reached ? ' progress__fill--done' : ''}`} style={{ width: `${pct}%` }} />
      </div>
      <div className="progress__labels">
        <span className="num">
          <b>{won(value)}</b>
          <span className="sub"> / {won(goal)}원</span>
        </span>
        {reached ? <span className="progress__done">최소금액 달성</span> : <span className="sub num">{won(goal - value)}원 남음</span>}
      </div>
    </div>
  )
}

// 이름과 값 한 줄
export function Row({ label, value, strong = false }: { label: ReactNode; value: ReactNode; strong?: boolean }) {
  return (
    <div className={`row${strong ? ' row--strong' : ''}`}>
      <span className="row__label">{label}</span>
      <span className="row__value num">{value}</span>
    </div>
  )
}

// 영수증 한 장. 제목과 오른쪽 덧말. accent는 지금 내가 할 일이 있는 카드, warn은 경고
export function Card({ title, aside, children, tone }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; tone?: 'accent' | 'warn' }) {
  return (
    <section className={`card${tone ? ` card--${tone}` : ''}`}>
      {(title || aside) && (
        <div className="card__head">
          {title && <h2 className="card__title">{title}</h2>}
          {aside && <span className="card__aside">{aside}</span>}
        </div>
      )}
      {children}
    </section>
  )
}

// 불러오기 실패처럼 버튼과 상관없는 오류는 그 자리에 둔다
export function InlineError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p className="inline-error" role="alert">
      {message}
    </p>
  )
}

// 계좌번호 같은 걸 눌러서 복사
export function CopyButton({ text }: { text: string }) {
  const toast = useToast()
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      toast('복사했어요', 'ok')
    } catch {
      toast('복사하지 못했어요. 길게 눌러 직접 복사해 주세요')
    }
  }
  return (
    <button type="button" className="copy" onClick={copy}>
      <span className="num">{text}</span>
      <span className="copy__hint">복사</span>
    </button>
  )
}

// 라이트/다크 전환. 해와 달 대신 반달 모양 손잡이가 좌우로 움직인다
export function ThemeToggle() {
  const { theme, toggle } = useTheme()
  return (
    <button className="theme-toggle" onClick={toggle} aria-label={theme === 'dark' ? '라이트 모드로' : '다크 모드로'}>
      <span className={`theme-toggle__knob theme-toggle__knob--${theme}`} aria-hidden="true" />
    </button>
  )
}
