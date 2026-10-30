import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, FOOD_CATEGORIES, type FoodCategory, type RoomSummary } from '../api'
import { InlineError, Progress } from '../components'
import { CATEGORY_LABEL, clock, remaining } from '../format'

const PAGE_SIZE = 20

// 우리 학교에서 지금 모집 중인 방. 마감이 가까운 순서
// 위의 카테고리 칩으로 거른다. 여러 개를 고르면 하나라도 맞는 방이 나온다. 아무것도 안 고르면 전체
export default function Board() {
  const [rooms, setRooms] = useState<RoomSummary[]>([])
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<FoodCategory[]>([])

  // 고른 카테고리가 바뀌면 첫 페이지부터 다시. 칩 순서와 상관없이 같은 조합이면 같은 요청이 되게 정렬한 글자를 의존값으로 쓴다
  const selectedKey = [...selected].sort().join(',')

  useEffect(() => {
    api
      .rooms(page, PAGE_SIZE, selected)
      .then((r) => {
        setRooms((prev) => (page === 0 ? r.rooms : [...prev, ...r.rooms]))
        setHasNext(r.hasNext)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoaded(true))
    // selected는 selectedKey로 대신 본다(배열은 매번 새로 만들어져 같은 내용이어도 다르게 보인다)
  }, [page, selectedKey])

  const toggle = (c: FoodCategory) => {
    setPage(0)
    setSelected((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : [...prev, c]))
  }

  return (
    <>
      <div className="pagehead">
        <h1>지금 모이는 방</h1>
        <Link className="btn btn--primary" to="/rooms/new">
          + 새 방 만들기
        </Link>
      </div>

      <div className="catfilter" role="group" aria-label="음식 카테고리로 거르기">
        <button
          type="button"
          className={`catchip${selected.length === 0 ? ' catchip--on' : ''}`}
          aria-pressed={selected.length === 0}
          onClick={() => {
            setPage(0)
            setSelected([])
          }}
        >
          전체
        </button>
        {FOOD_CATEGORIES.map((c) => (
          <button key={c} type="button" className={`catchip${selected.includes(c) ? ' catchip--on' : ''}`} aria-pressed={selected.includes(c)} onClick={() => toggle(c)}>
            {CATEGORY_LABEL[c]}
          </button>
        ))}
      </div>

      <InlineError message={error} />

      {loaded && rooms.length === 0 && !error && (
        <div className="empty">
          <p className="empty__big">{selected.length ? '고른 종류의 방이 없어요' : '아직 열린 방이 없어요'}</p>
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
                {r.categories.length > 0 && <p className="roomcard__cats">{r.categories.map((c) => CATEGORY_LABEL[c]).join(' · ')}</p>}
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
