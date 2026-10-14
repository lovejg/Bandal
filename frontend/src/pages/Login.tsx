import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { ThemeToggle, useToast } from '../components'

// DevDataSeeder가 심는 계정. 개발 서버에서만 보인다
const DEV_ACCOUNTS = [
  { email: 'kim@hankuk.ac.kr', who: '배고파', role: '방장 역할 · 계좌 있음' },
  { email: 'lee@hankuk.ac.kr', who: '마라탕러버', role: '참여자 역할' },
  { email: 'park@minguk.ac.kr', who: '외부인', role: '다른 학교' },
]
const DEV_PASSWORD = 'password1234'

export default function Login() {
  const { me, login } = useAuth()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)

  if (me) return <Navigate to="/" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      await login(email, password)
    } catch (err) {
      toast(err instanceof Error ? err.message : '로그인하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page page--login">
      <div className="login__top">
        <ThemeToggle />
      </div>
      <div className="hero">
        <span className="hero__moon" aria-hidden="true" />
        <h1 className="hero__title">반달</h1>
        <p className="hero__copy">
          최소주문금액 혼자 못 채울 때,
          <br />
          <mark>같은 건물 사람들이랑 반씩</mark>.
        </p>
      </div>

      <form className="stack" onSubmit={submit}>
        <label className="field">
          <span className="field__label">학교 이메일</span>
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="me@school.ac.kr" required />
        </label>
        <label className="field">
          <span className="field__label">비밀번호</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
          {busy ? '확인하는 중…' : '로그인'}
        </button>
      </form>

      {import.meta.env.DEV && (
        <section className="samples">
          <p className="samples__head">개발용 계정 · 눌러서 채우기</p>
          {DEV_ACCOUNTS.map((a) => (
            <button
              key={a.email}
              type="button"
              className="samples__row"
              onClick={() => {
                setEmail(a.email)
                setPassword(DEV_PASSWORD)
              }}
            >
              <b>{a.who}</b>
              <span className="sub">{a.role}</span>
            </button>
          ))}
        </section>
      )}
    </div>
  )
}
