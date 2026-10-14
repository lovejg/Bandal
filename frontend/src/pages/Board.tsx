import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type RoomSummary } from '../api'
import { InlineError, Progress } from '../components'
import { clock, remaining } from '../format'

const PAGE_SIZE = 20

// 우리 학교에서 지금 모집 중인 방. 마감이 가까운 순서
export default function Board() {
  const [rooms, setRooms] = useState<RoomSummary[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .rooms(page, PAGE_SIZE)
      .then((r) => {
        setRooms((prev) => (page === 0 ? r.rooms : [...prev, ...r.rooms]))
        setHasNext(r.hasNext)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoaded(true))
  }, [page])

  return (
    <>
      <div className="pagehead">
        <h1>지금 모이는 방</h1>
        <Link className="btn btn--primary" to="/rooms/new">
          + 새 방 만들기
        </Link>
      </div>

      <InlineError message={error} />

      {loaded && rooms.length === 0 && !error && (
        <div className="empty">
          <p className="empty__big">아직 열린 방이 없어요</p>
          <p className="sub">배고픈 사람이 먼저 열면 됩니다.</p>
        </div>
      )}

      <ul className="roomlist">
        {rooms.map((r) => {
          const minutesLeft = (new Date(r.deadlineAt).getTime() - Date.now()) / 60000
          return (
            <li key={r.id}>
              <Link className="roomcard" to={`/rooms/${r.id}`}>
                <div className="roomcard__top">
                  <span className="roomcard__store">{r.storeName}</span>
                  <span className={`pill${minutesLeft < 30 ? ' pill--hot' : ''}`}>{remaining(r.deadlineAt)}</span>
                </div>
                <div className="roomcard__meta sub">
                  <span>{r.pickupSpotName}</span>
                  <span>·</span>
                  <span>{clock(r.deadlineAt)} 마감</span>
                  <span>·</span>
                  <span className="num">
                    {r.participantCount}/{r.capacity}명
                  </span>
                </div>
                <Progress value={r.menuTotalAmount} goal={r.minOrderAmount} />
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
