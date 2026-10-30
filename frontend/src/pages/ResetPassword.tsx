import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { ThemeToggle, useAction } from '../components'

// 재설정 메일의 링크가 여는 화면(/reset-password?token=...). 새 비밀번호를 정한다
// 바꾸면 모든 기기가 로그아웃된다. 이 기기도 자동 로그인하지 않고 로그인 화면으로 보낸다(새 비밀번호로 들어오게)
export default function ResetPassword() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const navigate = useNavigate()
  const { busy, run } = useAction()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      if (password !== confirm) throw new Error('새 비밀번호가 서로 달라요')
      await api.confirmPasswordReset(token ?? '', password)
      navigate('/login', { replace: true })
    }, '비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요')
  }

  return (
    <div className="page page--login">
      <div className="login__top">
        <ThemeToggle />
      </div>
      <div className="hero hero--small">
        <span className="hero__moon" aria-hidden="true" />
        <h1 className="hero__title">새 비밀번호</h1>
      </div>

      {!token ? (
        <p>링크가 올바르지 않아요. 메일의 버튼을 다시 눌러 주세요.</p>
      ) : (
        <form className="stack" onSubmit={submit}>
          <label className="field">
            <span className="field__label">새 비밀번호</span>
            <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="8자 이상" required minLength={8} maxLength={64} />
          </label>
          <label className="field">
            <span className="field__label">새 비밀번호 확인</span>
            <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required minLength={8} maxLength={64} />
          </label>
          <p className="sub small">바꾸면 모든 기기에서 로그아웃돼요.</p>
          <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
            {busy ? '바꾸는 중…' : '비밀번호 바꾸기'}
          </button>
        </form>
      )}

      <p className="login__switch sub">
        링크가 만료됐나요? <Link to="/forgot-password">다시 받기</Link>
      </p>
    </div>
  )
}
