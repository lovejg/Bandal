import { useCallback, useEffect, useState } from 'react'
import { Link, NavLink, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { api, NOTIFICATIONS_CHANGED_EVENT } from './api'
import { useAuth } from './auth'
import { ThemeToggle, useAction } from './components'
import Login from './pages/Login'
import SignUp from './pages/SignUp'
import ForgotPassword from './pages/ForgotPassword'
import ResetPassword from './pages/ResetPassword'
import Board from './pages/Board'
import NewRoom from './pages/NewRoom'
import RoomPage from './pages/Room'
import MyRooms from './pages/MyRooms'
import MePage from './pages/Me'
import Notifications from './pages/Notifications'
import Guide from './pages/Guide'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<SignUp />} />
      <Route path="/forgot-password" element={<ForgotPassword />} />
      <Route path="/reset-password" element={<ResetPassword />} />
      <Route element={<Shell />}>
        <Route index element={<Board />} />
        <Route path="rooms/new" element={<NewRoom />} />
        <Route path="rooms/:id" element={<RoomPage />} />
        <Route path="mine" element={<MyRooms />} />
        <Route path="me" element={<MePage />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="guide" element={<Guide />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

// 로그인한 사람만 들어오는 바깥 틀
function Shell() {
  const { me, loading } = useAuth()
  if (loading) return <div className="page page--center sub">불러오는 중…</div>
  if (!me) return <Navigate to="/login" replace />

  return (
    <div className="page">
      <header className="topbar">
        <Link to="/" className="logo">
          <span className="logo__moon" aria-hidden="true" />
          반달
        </Link>
        <span className="topbar__school">{me.universityName}</span>
        {/* 유의사항. 종과 같은 모양의 네모 버튼으로 둔다 */}
        <Link to="/guide" className="bell topbar__end" aria-label="이용 유의사항">
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
            <path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .8-1 1.5V14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <circle cx="12" cy="17" r="1.2" fill="currentColor" />
          </svg>
        </Link>
        <Bell />
        <ThemeToggle />
      </header>
      <nav className="tabbar" aria-label="주요 메뉴">
        <NavLink to="/" end>
          모집 중
        </NavLink>
        <NavLink to="/mine">내 방</NavLink>
        <NavLink to="/me">내 정보</NavLink>
      </nav>
      {!me.emailVerified && <VerifyNotice />}
      <main>
        <Outlet />
      </main>
    </div>
  )
}

// 안 읽은 알림 수를 다시 묻는 주기. 서버가 밀어주지 않아서(SSE 없음) 화면이 주기적으로 묻는다
const BELL_POLL_MS = 30_000

// 상단의 종 아이콘. 안 읽은 알림이 있으면 빨간 숫자를 단다
// 숫자를 다시 묻는 때: 30초마다(탭이 보일 때만), 이 탭으로 돌아왔을 때, 화면을 옮길 때, 알림을 읽음으로 바꿨을 때
function Bell() {
  const [count, setCount] = useState(0)
  const location = useLocation()

  // 실패하면 숫자를 그대로 둔다. 종 숫자 때문에 에러를 띄울 일은 아니다
  const refresh = useCallback(() => {
    api
      .unreadNotificationCount()
      .then(setCount)
      .catch(() => undefined)
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh, location.pathname])

  useEffect(() => {
    // 다른 탭을 보고 있는 동안에는 묻지 않는다. 돌아오면 focus에서 바로 묻는다
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, BELL_POLL_MS)
    window.addEventListener('focus', refresh)
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, refresh)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', refresh)
      window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, refresh)
    }
  }, [refresh])

  return (
    <Link to="/notifications" className="bell" aria-label={count > 0 ? `알림, 안 읽은 알림 ${count}개` : '알림'}>
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path
          d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2Z M10 20a2 2 0 0 0 4 0"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinejoin="round"
        />
      </svg>
      {count > 0 && <span className="bell__count">{count > 99 ? '99+' : count}</span>}
    </Link>
  )
}

// 메일 인증 전 안내. 링크는 보통 다른 탭(메일함)에서 누르므로, 이 탭으로 돌아오면 내 정보를 다시 읽는다
function VerifyNotice() {
  const { reload } = useAuth()
  const { busy, run } = useAction()

  useEffect(() => {
    const onFocus = () => void reload()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [reload])

  return (
    <p className="notice">
      메일 인증 전이라 구경만 할 수 있어요. 받은 메일의 링크를 눌러 주세요.
      <button className="notice__action" disabled={busy} onClick={() => void run(() => api.resendVerification(), '인증 메일을 다시 보냈어요')}>
        메일 다시 받기
      </button>
    </p>
  )
}
