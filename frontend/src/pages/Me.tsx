import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth'
import { api } from '../api'
import { Card, Row, useAction } from '../components'
import { clock } from '../format'

// 내 정보, 돈 받을 계좌, 닉네임, 비밀번호, 탈퇴
export default function MePage() {
  const { me, reload, logout } = useAuth()
  const { busy, run } = useAction()
  const [bankName, setBankName] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [accountHolder, setAccountHolder] = useState('')

  if (!me) return null

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await api.registerAccount(bankName.trim(), accountNumber.trim(), accountHolder.trim())
      await reload()
      setAccountNumber('')
    }, '계좌를 저장했어요')
  }

  return (
    <div className="stack">
      <div className="profile">
        <span className="profile__avatar" aria-hidden="true">
          {me.nickname.slice(0, 1)}
        </span>
        <div>
          <p className="profile__name">{me.nickname}</p>
          <p className="sub small">
            {me.universityName} · {me.email}
          </p>
        </div>
      </div>

      <Card>
        <Row label="메일 인증" value={me.emailVerified ? '완료' : '대기 중'} />
        {/* 신뢰도는 점수가 움직이는 규칙이 아직 없어 늘 50이다. 규칙이 생기면 다시 보여 준다 */}
        <Row label="돈 받을 계좌" value={me.hasAccount ? '등록됨' : '없음'} />
      </Card>

      <Card title={me.hasAccount ? '계좌 바꾸기' : '계좌 등록하기'}>
        <p className="sub small">방장이 되면 참여자들이 이 계좌로 보내요. 예금주 이름은 가운데를 가려서 보여줘요.</p>
        <form className="stack stack--tight" onSubmit={submit}>
          <div className="grid2">
            <label className="field">
              <span className="field__label">은행</span>
              <input value={bankName} onChange={(e) => setBankName(e.target.value)} placeholder="토스뱅크" required maxLength={20} />
            </label>
            <label className="field">
              <span className="field__label">예금주</span>
              <input value={accountHolder} onChange={(e) => setAccountHolder(e.target.value)} required maxLength={20} />
            </label>
          </div>
          <label className="field">
            <span className="field__label">계좌번호</span>
            <input className="num" inputMode="numeric" value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} placeholder="- 없이 숫자만 적어도 돼요" required maxLength={30} />
          </label>
          <button className="btn btn--primary btn--block" disabled={busy}>
            저장
          </button>
        </form>
      </Card>

      <NicknameCard />

      <PasswordCard />

      <button className="btn btn--ghost" onClick={() => void logout()}>
        로그아웃
      </button>

      <WithdrawCard />
    </div>
  )
}

// 탈퇴. 실수로 누르지 않게 처음엔 접어 두고, 펼치면 무엇이 지워지는지 보여 준 뒤 비밀번호를 받는다
// 진행 중인 방이나 남은 환불이 있으면 서버가 막고 이유를 알려 준다
function WithdrawCard() {
  const { logout } = useAuth()
  const { busy, run } = useAction()
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')

  if (!open) {
    return (
      <button className="textbtn textbtn--danger" onClick={() => setOpen(true)}>
        탈퇴하기
      </button>
    )
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await api.withdraw(password)
      // 서버에서는 이미 로그아웃됐다. 저장해 둔 토큰을 지우고 로그인 화면으로 간다
      await logout()
    })
  }

  return (
    <Card title="탈퇴하기" tone="warn">
      <ul className="sub small">
        <li>학교 메일, 닉네임, 계좌, 예금주 이름, 알림이 지워져요. 되돌릴 수 없어요.</li>
        <li>지난 방 기록은 다른 사람의 정산표에 필요해서 남아요. 이름은 "탈퇴한 사용자"로 보여요.</li>
        <li>진행 중인 방이나 끝나지 않은 환불이 있으면 탈퇴할 수 없어요.</li>
        <li>같은 학교 메일로 다시 가입할 수 있지만, 예전 기록과 이어지지 않아요.</li>
      </ul>
      <form className="stack stack--tight" onSubmit={submit}>
        <label className="field">
          <span className="field__label">비밀번호</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <div className="grid2">
          <button type="button" className="btn btn--ghost" onClick={() => setOpen(false)} disabled={busy}>
            그만두기
          </button>
          <button className="btn btn--danger" disabled={busy}>
            탈퇴
          </button>
        </div>
      </form>
    </Card>
  )
}

// 닉네임 바꾸기. 7일에 한 번, 진행 중인 방이 없을 때만(서버가 막고 이유를 알려 준다)
// 기다려야 하면 언제부터 되는지 미리 보여 주고 버튼을 막는다. 진행 중인 방 여부는 눌렀을 때 서버 답으로 안내한다
function NicknameCard() {
  const { me, reload } = useAuth()
  const { busy, run } = useAction()
  const [nickname, setNickname] = useState('')
  if (!me) return null

  const changeableAt = me.nicknameChangeableAt ? new Date(me.nicknameChangeableAt) : null
  const waiting = changeableAt !== null && changeableAt.getTime() > Date.now()

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await api.changeNickname(nickname.trim())
      await reload()
      setNickname('')
    }, '닉네임을 바꿨어요')
  }

  return (
    <Card title="닉네임 바꾸기">
      <form className="stack stack--tight" onSubmit={submit}>
        <label className="field">
          <span className="field__label">새 닉네임</span>
          <input value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder={me.nickname} required minLength={2} maxLength={20} disabled={waiting} />
        </label>
        <p className="sub small">
          {waiting
            ? `${changeableAt.getMonth() + 1}월 ${changeableAt.getDate()}일 ${clock(changeableAt.toISOString())}부터 다시 바꿀 수 있어요.`
            : '7일에 한 번 바꿀 수 있어요. 진행 중인 방이 있으면 방이 끝난 뒤에 바꿀 수 있어요.'}
        </p>
        <button className="btn btn--primary btn--block" disabled={busy || waiting}>
          닉네임 바꾸기
        </button>
      </form>
    </Card>
  )
}

// 비밀번호 바꾸기. 바꾸면 다른 기기는 로그아웃되고 이 기기는 그대로다
function PasswordCard() {
  const { busy, run } = useAction()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      // 새 비밀번호를 두 번 받아 오타로 잠기는 걸 막는다. 서버에 보내기 전에 화면에서 확인한다
      if (next !== confirm) throw new Error('새 비밀번호가 서로 달라요')
      await api.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
    }, '비밀번호를 바꿨어요. 다른 기기는 로그아웃돼요')
  }

  return (
    <Card title="비밀번호 바꾸기">
      <form className="stack stack--tight" onSubmit={submit}>
        <label className="field">
          <span className="field__label">현재 비밀번호</span>
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
        </label>
        <label className="field">
          <span className="field__label">새 비밀번호</span>
          <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} placeholder="8자 이상" required minLength={8} maxLength={64} />
        </label>
        <label className="field">
          <span className="field__label">새 비밀번호 확인</span>
          <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required minLength={8} maxLength={64} />
        </label>
        <p className="sub small">바꾸면 이 기기를 뺀 다른 기기는 로그아웃돼요.</p>
        <button className="btn btn--primary btn--block" disabled={busy}>
          비밀번호 바꾸기
        </button>
      </form>
    </Card>
  )
}
