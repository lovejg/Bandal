import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth'
import { api } from '../api'
import { Card, Row, useAction } from '../components'

// 내 정보와 돈 받을 계좌
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

      <button className="btn btn--ghost" onClick={() => void logout()}>
        로그아웃
      </button>
    </div>
  )
}
