import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type MyRoom } from '../api'
import { InlineError, StatusChip } from '../components'
import { nextStep } from '../format'

const PAGE_SIZE = 20

// 내가 만들었거나 참여한 방. 할 일이 남은 방(진행 중)과 끝난 방으로 나뉜다
export default function MyRooms() {
  const [tab, setTab] = useState<'active' | 'done'>('active')
  const [rooms, setRooms] = useState<MyRoom[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoaded(false)
    const request = tab === 'active' ? api.myActive() : api.myDone(page, PAGE_SIZE)
    request
      .then((r) => {
        setRooms((prev) => (page === 0 ? r.rooms : [...prev, ...r.rooms]))
        setHasNext(r.hasNext)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoaded(true))
  }, [tab, page])

  const switchTo = (next: 'active' | 'done') => {
    if (next === tab) return
    setRooms([])
    setPage(0)
    setTab(next)
  }

  const active = tab === 'active'
  const todoCount = active ? rooms.filter((r) => nextStep(r, true).urgent).length : 0

  return (
    <>
      <div className="pagehead">
        <h1>내 방</h1>
      </div>

      <div className="segment" role="tablist">
        <button role="tab" aria-selected={active} className={active ? 'on' : ''} onClick={() => switchTo('active')}>
          진행 중{todoCount > 0 && <span className="segment__count">{todoCount}</span>}
        </button>
        <button role="tab" aria-selected={!active} className={!active ? 'on' : ''} onClick={() => switchTo('done')}>
          끝난 방
        </button>
      </div>

      <InlineError message={error} />

      {loaded && rooms.length === 0 && !error && (
        <div className="empty">
          <p className="empty__big">{active ? '진행 중인 방이 없어요' : '끝난 방이 아직 없어요'}</p>
          {active && <Link to="/">모집 중인 방 보러 가기 →</Link>}
        </div>
      )}

      <ul className="roomlist">
        {rooms.map((r) => {
          const step = nextStep(r, active)
          return (
            <li key={r.id}>
              <Link className={`myroom${step.urgent ? ' myroom--todo' : ''}`} to={`/rooms/${r.id}`}>
                <div className="myroom__top">
                  <span className="myroom__store">{r.storeName}</span>
                  <StatusChip status={r.status} />
                </div>
                <div className="myroom__bottom">
                  <span className="badge">{r.host ? '방장' : '참여'}</span>
                  {step.text && <span className="myroom__step">{step.text}</span>}
                </div>
              </Link>
            </li>
          )
        })}
      </ul>

      {hasNext && (
        <button className="btn btn--ghost btn--block" onClick={() => setPage((p) => p + 1)}>
          더 보기
        </button>
      )}
    </>
  )
}
