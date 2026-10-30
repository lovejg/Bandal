import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ThemeToggle, useAction } from '../components'

// 비밀번호를 잊었을 때. 메일 주소를 받아 재설정 링크를 보낸다
// 서버는 가입된 주소든 아니든 같은 문장으로 답한다. 화면도 그 문장을 그대로 보여 준다
export default function ForgotPassword() {
  const { busy, run } = useAction()
  const [email, setEmail] = useState('')
  const [sentMessage, setSentMessage] = useState<string | null>(null)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      const { message } = await api.requestPasswordReset(email.trim())
      setSentMessage(message)
    })
  }

  return (
    <div className="page page--login">
      <div className="login__top">
        <ThemeToggle />
      </div>
      <div className="hero hero--small">
        <span className="hero__moon" aria-hidden="true" />
        <h1 className="hero__title">비밀번호 재설정</h1>
      </div>

      {sentMessage ? (
        <div className="stack">
          <p>{sentMessage}</p>
          <p className="sub small">메일이 안 오면 스팸함을 확인하고, 1분 뒤에 다시 요청할 수 있어요. 링크는 30분 동안 한 번만 쓸 수 있어요.</p>
        </div>
      ) : (
        <form className="stack" onSubmit={submit}>
          <p className="sub small">가입한 학교 이메일을 적으면 새 비밀번호를 정하는 링크를 보내 드려요.</p>
          <label className="field">
            <span className="field__label">학교 이메일</span>
            <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="me@school.ac.kr" required />
          </label>
          <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
            {busy ? '보내는 중…' : '재설정 메일 받기'}
          </button>
        </form>
      )}

      <p className="login__switch sub">
        <Link to="/login">로그인으로 돌아가기</Link>
      </p>
    </div>
  )
}
