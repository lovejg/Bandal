import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, FOOD_CATEGORIES, type FoodCategory, type PickupSpot } from '../api'
import { useAuth } from '../auth'
import { Card, InlineError, useAction } from '../components'
import { CATEGORY_LABEL, isoToLocalInput, localInputToIso } from '../format'

// 새 방 만들기. 방장은 자동으로 첫 참여자가 된다
export default function NewRoom() {
  const { me } = useAuth()
  const navigate = useNavigate()
  const { busy, run } = useAction()
  const [spots, setSpots] = useState<PickupSpot[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [storeName, setStoreName] = useState('')
  const [spotId, setSpotId] = useState<number | ''>('')
  const [minOrder, setMinOrder] = useState('15000')
  const [deadline, setDeadline] = useState(() => isoToLocalInput(new Date(Date.now() + 60 * 60 * 1000)))
  const [capacity, setCapacity] = useState(4)
  // 음식 카테고리 1~3개. 3개를 고르면 나머지 칩은 막는다(서버도 같은 규칙)
  const [categories, setCategories] = useState<FoodCategory[]>([])
  const toggleCategory = (c: FoodCategory) =>
    setCategories((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : prev.length >= 3 ? prev : [...prev, c]))
  const [addingSpot, setAddingSpot] = useState(false)
  const [newSpot, setNewSpot] = useState('')

  useEffect(() => {
    api
      .pickupSpots()
      .then((list) => {
        setSpots(list)
        if (list.length > 0) setSpotId(list[0].id)
      })
      .catch((e: Error) => setLoadError(e.message))
  }, [])

  const addSpot = () =>
    run(async () => {
      const spot = await api.createPickupSpot(newSpot.trim(), null)
      setSpots((prev) => [...prev, spot].sort((a, b) => a.name.localeCompare(b.name, 'ko')))
      setSpotId(spot.id)
      setNewSpot('')
      setAddingSpot(false)
    }, '받을 곳을 추가했어요')

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      if (spotId === '') throw new Error('받을 곳을 골라 주세요')
      if (categories.length === 0) throw new Error('음식 종류를 하나 이상 골라 주세요')
      const room = await api.createRoom({
        pickupSpotId: spotId,
        storeName: storeName.trim(),
        minOrderAmount: Number(minOrder),
        deadlineAt: localInputToIso(deadline),
        capacity,
        categories,
      })
      navigate(`/rooms/${room.id}`, { replace: true })
    }, '방을 열었어요')
  }

  return (
    <>
      <div className="pagehead">
        <h1>새 방 만들기</h1>
        <Link className="btn btn--ghost" to="/">
          취소
        </Link>
      </div>

      {me && !me.hasAccount && (
        <p className="notice">
          방장은 돈 받을 계좌가 있어야 해요. <Link to="/me">계좌 먼저 등록하기 →</Link>
        </p>
      )}
      <InlineError message={loadError} />

      <form className="stack" onSubmit={submit}>
        <Card>
          <div className="stack stack--tight">
            <label className="field">
              <span className="field__label">어디서 시켜요?</span>
              <input value={storeName} onChange={(e) => setStoreName(e.target.value)} placeholder="배달앱에 뜨는 가게 이름" required />
            </label>

            <div className="field">
              <span className="field__label">어떤 음식이에요? (1~3개)</span>
              <div className="catpick" role="group" aria-label="음식 카테고리">
                {FOOD_CATEGORIES.map((c) => {
                  const on = categories.includes(c)
                  return (
                    <button
                      key={c}
                      type="button"
                      className={`catchip${on ? ' catchip--on' : ''}`}
                      aria-pressed={on}
                      disabled={!on && categories.length >= 3}
                      onClick={() => toggleCategory(c)}
                    >
                      {CATEGORY_LABEL[c]}
                    </button>
                  )
                })}
              </div>
            </div>

            <div className="field">
              <span className="field__label">어디서 받아요?</span>
              {!addingSpot ? (
                <div className="spotpick">
                  <select value={spotId} onChange={(e) => setSpotId(Number(e.target.value))}>
                    {spots.length === 0 && <option value="">아직 등록된 곳이 없어요</option>}
                    {spots.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                  <button type="button" className="btn btn--ghost btn--sm" onClick={() => setAddingSpot(true)}>
                    + 새 장소
                  </button>
                </div>
              ) : (
                <div className="spotpick">
                  <input value={newSpot} onChange={(e) => setNewSpot(e.target.value)} placeholder="예: 공대 7호관 1층 로비" autoFocus />
                  <button type="button" className="btn btn--primary btn--sm" disabled={busy || !newSpot.trim()} onClick={addSpot}>
                    추가
                  </button>
                  <button type="button" className="btn btn--ghost btn--sm" onClick={() => setAddingSpot(false)}>
                    닫기
                  </button>
                </div>
              )}
            </div>
          </div>
        </Card>

        <Card>
          <div className="stack stack--tight">
            <label className="field">
              <span className="field__label">최소주문금액</span>
              <div className="suffix">
                <input className="num" inputMode="numeric" value={minOrder} onChange={(e) => setMinOrder(e.target.value.replace(/\D/g, ''))} required />
                <span>원</span>
              </div>
            </label>

            <div className="field">
              <span className="field__label">몇 명까지? (나 포함)</span>
              <div className="stepper">
                <button type="button" onClick={() => setCapacity((c) => Math.max(2, c - 1))} aria-label="한 명 줄이기">
                  −
                </button>
                <span className="num">{capacity}명</span>
                <button type="button" onClick={() => setCapacity((c) => Math.min(20, c + 1))} aria-label="한 명 늘리기">
                  +
                </button>
              </div>
            </div>

            <label className="field">
              <span className="field__label">언제 마감해요?</span>
              <input className="num" type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} required />
            </label>
          </div>
        </Card>

        <button className="btn btn--primary btn--block btn--lg" disabled={busy}>
          {busy ? '만드는 중…' : '방 열기'}
        </button>
      </form>
    </>
  )
}
