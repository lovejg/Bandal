// 백엔드와 주고받는 모양과 호출 한 곳.
// 토큰은 localStorage에 둔다. 액세스 토큰이 만료돼 401이 오면 리프레시 토큰으로 한 번 갱신하고 다시 부른다

export type Status = 'RECRUITING' | 'CLOSED' | 'SETTLING' | 'ORDERED' | 'DELIVERED' | 'CANCELED'
export type RefundStatus = 'REFUND_PENDING' | 'PAYMENT_DENIED' | 'REFUND_SENT' | 'REFUNDED'

export interface Me {
  id: number
  email: string
  nickname: string
  universityId: number
  universityName: string
  emailVerified: boolean
  trustScore: number
  hasAccount: boolean
}

export interface RoomSummary {
  id: number
  storeName: string
  pickupSpotId: number
  pickupSpotName: string
  hostNickname: string
  minOrderAmount: number
  menuTotalAmount: number
  participantCount: number
  capacity: number
  deadlineAt: string
}

export interface Room {
  id: number
  hostId: number
  hostNickname: string
  pickupSpotId: number
  pickupSpotName: string
  storeName: string
  minOrderAmount: number
  deadlineAt: string
  capacity: number
  status: Status
  participantCount: number
  menuTotalAmount: number
  cancelReason: string | null
  cancelType: string | null
  // 주문완료 뒤에만 있다. 배달앱이 알려 준 도착 예정 시각
  expectedArrivalAt: string | null
  myParticipationId: number | null
}

export interface OrderItem {
  id: number
  participationId: number
  menuName: string
  options: string | null
  unitPrice: number
  quantity: number
  amount: number
  editedByHost: boolean
}

export interface MyItems {
  participationId: number
  items: OrderItem[]
  participationTotal: number
  groupOrderTotal: number
}

export interface ReviewList {
  groupOrderId: number
  status: Status
  groupOrderTotal: number
  participants: {
    participationId: number
    userId: number
    nickname: string
    host: boolean
    items: OrderItem[]
    participationTotal: number
  }[]
}

export interface SettlementLine {
  id: number
  userId: number
  nickname: string
  host: boolean
  menuTotalAmount: number
  feeShare: number
  totalAmount: number
  markedPaidAt: string | null
  confirmedPaidAt: string | null
  confirmRevokedAt: string | null
  refundSentAt: string | null
  refundReceivedAt: string | null
  paymentDeniedAt: string | null
  refundStatus: RefundStatus | null
}

export interface Settlement {
  groupOrderId: number
  status: Status
  deliveryFee: number
  menuTotalAmount: number
  hostAccount: { bankName: string; accountNumber: string; accountHolder: string }
  lines: SettlementLine[]
}

export interface MyRoom {
  id: number
  storeName: string
  status: Status
  host: boolean
  markedPaidAt: string | null
  confirmedPaidAt: string | null
  refundStatus: RefundStatus | null
  awaitingConfirmCount: number | null
}

// 알림함의 한 줄. 서버는 사실(상태, 금액, 사유)만 주고 문장은 화면이 만든다(format.ts의 notificationText)
export interface AppNotification {
  id: number
  groupOrderId: number
  storeName: string
  // 무엇이 바뀌었나. 배달비 수정이면 status는 정산중, 도착 예정 수정이면 주문완료 그대로다
  kind: 'STATUS_CHANGED' | 'DELIVERY_FEE_CHANGED' | 'ARRIVAL_CHANGED'
  status: Status
  cancelReason: string | null
  amount: number | null
  deliveryFee: number | null
  expectedArrivalAt: string | null
  occurredAt: string
  read: boolean
}

export interface PickupSpot {
  id: number
  name: string
  description: string | null
}

// 가입할 때 고르는 캠퍼스. 같은 메일 도메인을 여러 캠퍼스가 같이 쓴다
export interface Campus {
  id: number
  name: string
}

export interface SignUpInput {
  email: string
  password: string
  nickname: string
  universityId: number
}

export interface ItemInput {
  menuName: string
  options: string | null
  unitPrice: number
  quantity: number
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

interface Tokens {
  accessToken: string
  refreshToken: string
}

const TOKEN_KEY = 'bandal.tokens'

export const tokenStore = {
  get(): Tokens | null {
    try {
      const raw = localStorage.getItem(TOKEN_KEY)
      return raw ? (JSON.parse(raw) as Tokens) : null
    } catch {
      return null
    }
  },
  set(tokens: Tokens) {
    try {
      localStorage.setItem(TOKEN_KEY, JSON.stringify(tokens))
    } catch {
      // 저장이 막힌 브라우저(사생활 보호 창 등)에서는 이번 탭에서만 로그인이 유지된다
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY)
    } catch {
      // 위와 같다
    }
  },
}

// 한 탭 안에서는 갱신이 동시에 여러 번 나가지 않게 진행 중인 갱신을 함께 기다린다
let refreshing: Promise<boolean> | null = null

// 리프레시 토큰은 한 번만 쓸 수 있어서, 탭 두 개가 같은 토큰으로 동시에 갱신하면 하나는 진다.
// 진 탭이 실패했다고 저장소를 비우면 이긴 탭이 받은 새 토큰까지 지워 둘 다 로그아웃된다.
// 그래서 탭끼리 잠금(Web Locks)으로 차례를 정한다. 기다린 탭은 차례가 오면 저장소를 다시 보고,
// 그사이 다른 탭이 새 토큰을 써 뒀으면 서버에 가지 않고 그걸 쓴다
function refreshTokens(used: Tokens): Promise<boolean> {
  // 잠금을 못 쓰는 브라우저에서는 잠금 없이 한다
  if (!navigator.locks) return doRefresh(used)
  return navigator.locks.request('bandal.refresh', () => doRefresh(used))
}

async function doRefresh(used: Tokens): Promise<boolean> {
  const current = tokenStore.get()
  if (!current) return false
  if (current.refreshToken !== used.refreshToken) return true

  const res = await fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: current.refreshToken }),
  })
  if (!res.ok) {
    tokenStore.clear()
    return false
  }
  tokenStore.set((await res.json()) as Tokens)
  return true
}

export const LOGGED_OUT_EVENT = 'bandal:logged-out'

async function request<T>(method: string, path: string, body?: unknown, retry = true): Promise<T> {
  const tokens = tokenStore.get()
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (tokens) headers.Authorization = `Bearer ${tokens.accessToken}`

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.status === 401 && retry && tokens) {
    refreshing ??= refreshTokens(tokens).finally(() => {
      refreshing = null
    })
    if (await refreshing) return request<T>(method, path, body, false)
    window.dispatchEvent(new Event(LOGGED_OUT_EVENT))
  }

  if (!res.ok) {
    let message = '요청을 처리하지 못했습니다'
    try {
      const err = (await res.json()) as { message?: string }
      if (err.message) message = err.message
    } catch {
      // 본문이 JSON이 아니면 기본 문구를 쓴다
    }
    throw new ApiError(res.status, message)
  }

  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const get = <T,>(path: string) => request<T>('GET', path)
const post = <T,>(path: string, body?: unknown) => request<T>('POST', path, body)
const put = <T,>(path: string, body?: unknown) => request<T>('PUT', path, body)
const del = <T,>(path: string) => request<T>('DELETE', path)

export const api = {
  login: (email: string, password: string) =>
    post<Tokens>('/api/auth/login', { email, password }).then((t) => tokenStore.set(t)),
  logout: () => {
    const tokens = tokenStore.get()
    tokenStore.clear()
    if (tokens) return post<void>('/api/auth/logout', { refreshToken: tokens.refreshToken }).catch(() => undefined)
    return Promise.resolve()
  },
  me: () => get<Me>('/api/auth/me'),
  campuses: (domain: string) =>
    get<{ universities: Campus[] }>(`/api/universities?domain=${encodeURIComponent(domain)}`).then((r) => r.universities),
  signUp: (input: SignUpInput) => post<{ message: string }>('/api/auth/signup', input),
  resendVerification: () => post<void>('/api/auth/verify/resend'),
  registerAccount: (bankName: string, accountNumber: string, accountHolder: string) =>
    put<void>('/api/users/me/account', { bankName, accountNumber, accountHolder }),

  pickupSpots: () => get<{ spots: PickupSpot[] }>('/api/pickup-spots').then((r) => r.spots),
  createPickupSpot: (name: string, description: string | null) =>
    post<PickupSpot>('/api/pickup-spots', { name, description }),

  rooms: (page: number, size: number) =>
    get<{ rooms: RoomSummary[]; hasNext: boolean }>(`/api/group-orders?page=${page}&size=${size}`),
  room: (id: number) => get<Room>(`/api/group-orders/${id}`),
  createRoom: (body: { pickupSpotId: number; storeName: string; minOrderAmount: number; deadlineAt: string; capacity: number }) =>
    post<Room>('/api/group-orders', body),
  closeRoom: (id: number) => post<Room>(`/api/group-orders/${id}/close`),
  cancelRoom: (id: number, reason: string | null) => post<Room>(`/api/group-orders/${id}/cancel`, { reason }),

  join: (roomId: number) => post<{ id: number }>(`/api/group-orders/${roomId}/participations`),
  leave: (participationId: number) => del<void>(`/api/participations/${participationId}`),
  myItems: (participationId: number) => get<MyItems>(`/api/participations/${participationId}/order-items`),
  addItem: (participationId: number, item: ItemInput) =>
    post<MyItems>(`/api/participations/${participationId}/order-items`, item),
  updateItem: (itemId: number, item: ItemInput) => put<MyItems>(`/api/order-items/${itemId}`, item),
  removeItem: (itemId: number) => del<MyItems>(`/api/order-items/${itemId}`),
  reviewList: (roomId: number) => get<ReviewList>(`/api/group-orders/${roomId}/order-items`),

  settlement: (roomId: number) => get<Settlement>(`/api/group-orders/${roomId}/settlement`),
  startSettlement: (roomId: number, deliveryFee: number) =>
    post<Settlement>(`/api/group-orders/${roomId}/delivery-fee`, { deliveryFee }),
  changeDeliveryFee: (roomId: number, deliveryFee: number) =>
    put<Settlement>(`/api/group-orders/${roomId}/delivery-fee`, { deliveryFee }),
  lineAction: (lineId: number, action: 'mark-paid' | 'confirm' | 'unconfirm' | 'send-refund' | 'deny-payment' | 'receive-refund') =>
    post<Settlement>(`/api/settlements/${lineId}/${action}`),
  order: (roomId: number, totalPaidAmount: number | null, expectedArrivalAt: string) =>
    post<Settlement>(`/api/group-orders/${roomId}/order`, { totalPaidAmount, expectedArrivalAt }),
  changeArrival: (roomId: number, expectedArrivalAt: string) =>
    put<Settlement>(`/api/group-orders/${roomId}/expected-arrival`, { expectedArrivalAt }),
  deliver: (roomId: number) => post<Settlement>(`/api/group-orders/${roomId}/deliver`),

  myActive: () => get<{ rooms: MyRoom[]; hasNext: boolean }>('/api/users/me/group-orders/active'),
  myDone: (page: number, size: number) =>
    get<{ rooms: MyRoom[]; hasNext: boolean }>(`/api/users/me/group-orders/done?page=${page}&size=${size}`),

  // 알림 목록은 페이지 번호가 아니라 "이 알림보다 오래된 것"(before)으로 다음 묶음을 받는다
  // 처음엔 before 없이, "더 보기"는 받은 목록의 마지막 줄 id를 넣는다
  notifications: (before: number | null, size: number) =>
    get<{ notifications: AppNotification[]; hasNext: boolean }>(
      `/api/notifications?size=${size}${before === null ? '' : `&before=${before}`}`,
    ),
  unreadNotificationCount: () => get<{ count: number }>('/api/notifications/unread-count').then((r) => r.count),
  readNotification: (id: number) => post<void>(`/api/notifications/${id}/read`),
  // upToId: 화면에 보인 가장 최신 알림 id. 그 뒤에 들어온 알림은 안 읽음으로 남는다
  readAllNotifications: (upToId: number) => post<void>('/api/notifications/read-all', { upToId }),
}

// 알림을 읽음으로 바꾼 뒤 이 이벤트를 쏘면 종 아이콘이 다음 주기를 기다리지 않고 숫자를 다시 묻는다
export const NOTIFICATIONS_CHANGED_EVENT = 'bandal:notifications-changed'
