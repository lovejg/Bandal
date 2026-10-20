import { useEffect } from 'react'
import { Link, NavLink, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { api } from './api'
import { useAuth } from './auth'
import { ThemeToggle, useAction } from './components'
import Login from './pages/Login'
import SignUp from './pages/SignUp'
import Board from './pages/Board'
import NewRoom from './pages/NewRoom'
import RoomPage from './pages/Room'
import MyRooms from './pages/MyRooms'
import MePage from './pages/Me'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<SignUp />} />
      <Route element={<Shell />}>
        <Route index element={<Board />} />
        <Route path="rooms/new" element={<NewRoom />} />
        <Route path="rooms/:id" element={<RoomPage />} />
        <Route path="mine" element={<MyRooms />} />
        <Route path="me" element={<MePage />} />
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
