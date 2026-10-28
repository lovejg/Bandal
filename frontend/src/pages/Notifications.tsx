import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, NOTIFICATIONS_CHANGED_EVENT, type AppNotification } from '../api'
import { InlineError, useAction } from '../components'
import { ago, notificationText } from '../format'

const PAGE_SIZE = 20

// 알림함. 종 아이콘을 누르면 온다
// 목록을 여는 것만으로는 읽음이 되지 않는다. 안 읽은 줄을 진하게 보여 줘야 해서다
// 한 줄을 누르면 그 알림만 읽음으로 바꾸고 그 방으로 간다
export default function Notifications() {
  const navigate = useNavigate()
  const { busy, run } = useAction()
  const [items, setItems] = useState<AppNotification[]>([])
  // 다음 묶음의 기준. null이면 맨 위부터
  const [before, setBefore] = useState<number | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api
      .notifications(before, PAGE_SIZE)
      .then((r) => {
        setItems((prev) => (before === null ? r.notifications : [...prev, ...r.notifications]))
        setHasNext(r.hasNext)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoaded(true))
  }, [before])

  const changed = () => window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED_EVENT))

  // 읽음 표시가 실패해도 방으로는 간다. 알림을 누른 목적은 방을 보는 것이다
  const open = (n: AppNotification) => {
    if (!n.read) {
      void api
        .readNotification(n.id)
        .then(changed)
        .catch(() => undefined)
    }
    navigate(`/rooms/${n.groupOrderId}`)
  }

  // 화면 맨 위(가장 최신) 알림 id까지만 읽음으로. 그 뒤에 들어온 알림은 안 읽음으로 남는다
  const readAll = async () => {
    const upToId = items[0].id
    const ok = await run(() => api.readAllNotifications(upToId))
    if (!ok) return
    setItems((prev) => prev.map((n) => (n.id <= upToId ? { ...n, read: true } : n)))
    changed()
  }

  const anyUnread = items.some((n) => !n.read)

  return (
    <>
      <div className="pagehead">
        <h1>알림</h1>
        {anyUnread && (
          <button className="btn btn--ghost" disabled={busy} onClick={() => void readAll()}>
            모두 읽음
          </button>
        )}
      </div>

      <InlineError message={error} />

      {loaded && items.length === 0 && !error && (
        <div className="empty">
          <p className="empty__big">아직 알림이 없어요</p>
          <p className="sub">참여한 방의 상태가 바뀌면 여기에 알려 드려요</p>
        </div>
      )}

      <ul className="notilist">
        {items.map((n) => (
          <li key={n.id}>
            <button className={`noti${n.read ? '' : ' noti--unread'}`} onClick={() => open(n)}>
              <div className="noti__top">
                <span className="noti__store">{n.storeName}</span>
                <span className="noti__time">{ago(n.occurredAt)}</span>
              </div>
              <p className="noti__text">{notificationText(n)}</p>
            </button>
          </li>
        ))}
      </ul>

      {hasNext && (
        <button className="btn btn--ghost btn--block" onClick={() => setBefore(items[items.length - 1].id)}>
          더 보기
        </button>
      )}
    </>
  )
}
