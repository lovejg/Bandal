import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { api, type Campus } from '../api'
import { useAuth } from '../auth'
import { ThemeToggle, useToast } from '../components'

// 이메일의 @ 뒤를 떼어 학교를 찾는다. 아직 다 안 쳤으면 null
function domainOf(email: string): string | null {
  const at = email.lastIndexOf('@')
  if (at < 1) return null
  const domain = email.slice(at + 1).trim().toLowerCase()
  return domain.includes('.') && !domain.endsWith('.') ? domain : null
}

// 서버의 요청 검사 실패는 "password: 비밀번호는 ..."처럼 필드 이름이 붙어 온다. 사람에게는 뒷부분만 보인다
function readable(message: string): string {
  return message
    .split(', ')
    .map((part) => part.replace(/^\w+: /, ''))
    .join(' · ')
}

type CampusState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'none'; domain: string }
  | { kind: 'found'; domain: string; campuses: Campus[] }

export default function SignUp() {
  const { me } = useAuth()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [campus, setCampus] = useState<CampusState>({ kind: 'idle' })
  const [universityId, setUniversityId] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const domain = domainOf(email)

  // 도메인이 바뀌면 그 도메인의 캠퍼스를 묻는다. 치는 중에는 기다렸다가 멈추면 한 번만 묻는다
  useEffect(() => {
    if (!domain) {
      setCampus({ kind: 'idle' })
      setUniversityId(null)
      return
    }
    let stale = false
    setCampus({ kind: 'loading' })
    const timer = setTimeout(() => {
      api
        .campuses(domain)
        .then((campuses) => {
          if (stale) return
          if (campuses.length === 0) {
            setCampus({ kind: 'none', domain })
            setUniversityId(null)
          } else {
            setCampus({ kind: 'found', domain, campuses })
            // 하나뿐이면 고를 것이 없다. 여럿이면 사용자가 직접 고르게 비워 둔다
            setUniversityId(campuses.length === 1 ? campuses[0].id : null)
          }
        })
        .catch(() => {
          if (!stale) setCampus({ kind: 'idle' })
        })
    }, 300)
    return () => {
      stale = true
      clearTimeout(timer)
    }
  }, [domain])

  if (me) return <Navigate to="/" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (universityId === null) {
      toast(campus.kind === 'none' ? '아직 지원하지 않는 학교예요' : '캠퍼스를 골라 주세요')
      return
    }
    setBusy(true)
    try {
      await api.signUp({ email: email.trim(), password, nickname: nickname.trim(), universityId })
      setSentTo(email.trim())
    } catch (err) {
      toast(err instanceof Error ? readable(err.message) : '가입하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  if (sentTo) {
    return (
      <div className="page page--login">
        <div className="login__top">
          <ThemeToggle />
        </div>
        <div className="hero">
          <span className="hero__moon" aria-hidden="true" />
          <h1 className="hero__title">메일함을 봐 주세요</h1>
          <p className="hero__copy">
            <mark>{sentTo}</mark>로
            <br />
            인증 링크를 보냈어요.
          </p>
        </div>
        <div className="stack">
          <p className="sub">링크를 누르기 전에도 로그인해서 방을 구경할 수 있어요. 방을 만들고 참여하는 건 인증한 뒤부터예요.</p>
          <Link to="/login" className="btn btn--primary btn--block btn--lg">
            로그인하러 가기
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="page page--login">
      <div className="login__top">
        <ThemeToggle />
      </div>
      <div className="hero hero--small">
        <span className="hero__moon" aria-hidden="true" />
        <h1 className="hero__title">가입하기</h1>
        <p className="hero__copy">학교 메일로 같은 학교 사람인지만 확인해요.</p>
      </div>

      <form className="stack" onSubmit={submit}>
        <label className="field">
          <span className="field__label">학교 이메일</span>
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="me@school.ac.kr" required />
        </label>

        <CampusPicker state={campus} value={universityId} onChange={setUniversityId} />

        <label className="field">
          <span className="field__label">닉네임</span>
          <input value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder="방 목록에 이 이름이 보여요" required minLength={2} maxLength={20} />
        </label>
        <label className="field">
          <span className="field__label">비밀번호</span>
          <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="8자 이상" required minLength={8} maxLength={64} />
        </label>
        <button className="btn btn--primary btn--block btn--lg" disabled={busy || universityId === null}>
          {busy ? '보내는 중…' : '인증 메일 받기'}
        </button>
      </form>

      <p className="login__switch sub">
        이미 계정이 있나요? <Link to="/login">로그인</Link>
      </p>
    </div>
  )
}

// 도메인에 맞는 캠퍼스. 없으면 안내, 하나면 이름만, 여럿이면 고르게 한다
function CampusPicker({ state, value, onChange }: { state: CampusState; value: number | null; onChange: (id: number | null) => void }) {
  if (state.kind === 'idle') return null
  if (state.kind === 'loading') return <p className="campus sub small">학교를 찾는 중…</p>
  if (state.kind === 'none') {
    return (
      <p className="campus campus--none small" role="status">
        <b>{state.domain}</b>은 아직 지원하지 않는 학교예요.
      </p>
    )
  }
  if (state.campuses.length === 1) {
    return (
      <p className="campus small" role="status">
        <span className="campus__dot" aria-hidden="true" />
        {state.campuses[0].name}
      </p>
    )
  }
  return (
    <fieldset className="field campus-pick">
      <legend className="field__label">캠퍼스</legend>
      <div className="campus-pick__list">
        {state.campuses.map((c) => (
          <label key={c.id} className={`campus-pick__item${value === c.id ? ' on' : ''}`}>
            <input type="radio" name="campus" value={c.id} checked={value === c.id} onChange={() => onChange(c.id)} />
            {c.name}
          </label>
        ))}
      </div>
      <span className="sub small">방 목록과 거점이 캠퍼스마다 따로예요. 다니는 곳을 골라 주세요.</span>
    </fieldset>
  )
}
