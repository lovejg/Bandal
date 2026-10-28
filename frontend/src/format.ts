import type { AppNotification, MyRoom, RefundStatus, Status } from './api'

export const won = (n: number) => n.toLocaleString('ko-KR')

export const STATUS_LABEL: Record<Status, string> = {
  RECRUITING: '모집중',
  CLOSED: '마감',
  SETTLING: '정산중',
  ORDERED: '주문완료',
  DELIVERED: '배달완료',
  CANCELED: '취소',
}

export const REFUND_LABEL: Record<RefundStatus, string> = {
  REFUND_PENDING: '환불 대기',
  PAYMENT_DENIED: '방장이 입금을 못 받았대요',
  REFUND_SENT: '방장이 돌려줬대요 · 받았는지 확인해 주세요',
  REFUNDED: '환불 완료',
}

const pad = (n: number) => String(n).padStart(2, '0')

export function clock(iso: string) {
  const d = new Date(iso)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function dateClock(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}.${pad(d.getDate())} ${clock(iso)}`
}

// 마감까지 남은 시간
export function remaining(iso: string, now = Date.now()) {
  const ms = new Date(iso).getTime() - now
  if (ms <= 0) return '마감 시간 지남'
  const minutes = Math.floor(ms / 60000)
  if (minutes < 60) return `${minutes}분 남음`
  const hours = Math.floor(minutes / 60)
  return `${hours}시간 ${minutes % 60}분 남음`
}

// 방 번호
export const serial = (id: number) => `#${id}`

// datetime-local 입력값(시간대 없음)을 서버가 받는 ISO 문자열로
export function localInputToIso(value: string) {
  return new Date(value).toISOString()
}

export function isoToLocalInput(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// 시각 입력(input type="time", "19:40")을 ISO 문자열로. 날짜는 고르지 않는다
// 지금보다 이른 시각이면 다음 날로 본다. 밤 11시 50분에 주문하고 "00:30"을 넣으면 내일 0시 30분이다
export function timeInputToIso(value: string, now = new Date()) {
  const [h, m] = value.split(':').map(Number)
  const d = new Date(now)
  d.setHours(h, m, 0, 0)
  if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1)
  return d.toISOString()
}

// 지금부터 minutes분 뒤를 5분 단위로 올린 "HH:MM". 도착 예정 입력의 처음 값
export function clockAfter(minutes: number, now = new Date()) {
  const d = new Date(now.getTime() + minutes * 60000)
  d.setMinutes(Math.ceil(d.getMinutes() / 5) * 5, 0, 0)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 도착 예정까지 남은 시간. "35분 뒤", "곧 도착"
export function untilArrival(iso: string, now = Date.now()) {
  const minutes = Math.round((new Date(iso).getTime() - now) / 60000)
  if (minutes <= 0) return '곧 도착'
  if (minutes < 60) return `${minutes}분 뒤`
  return `${Math.floor(minutes / 60)}시간 ${minutes % 60}분 뒤`
}

// 알림 한 줄의 문장. 서버는 "무엇으로 바뀌었나"만 저장하고 문장은 여기서 만든다
// 문구를 고쳐도 이미 쌓인 알림까지 새 문구로 보인다
export function notificationText(n: AppNotification): string {
  // 배달비 수정은 상태가 그대로라 상태로는 가릴 수 없다. 종류부터 본다
  // 아무도 "보냈어요"를 누르기 전에만 고칠 수 있지만, 은행 앱으로 먼저 보낸 사람이 있을 수 있어 차액 안내를 붙인다
  if (n.kind === 'DELIVERY_FEE_CHANGED') {
    const fee = n.deliveryFee === null ? '배달비가 바뀌었어요' : `배달비가 ${won(n.deliveryFee)}원으로 바뀌었어요`
    const mine = n.amount === null ? '' : ` · 내 몫 ${won(n.amount)}원`
    return `${fee}${mine}. 이미 보냈다면 방장과 차액을 맞춰 주세요`
  }
  // 도착 예정 수정도 상태가 그대로(주문완료)라 종류부터 본다
  if (n.kind === 'ARRIVAL_CHANGED') {
    // "19:40로/으로"는 읽는 법에 따라 조사가 달라져서 시각을 뒤에 둔다
    return n.expectedArrivalAt ? `도착 예정이 바뀌었어요 · ${clock(n.expectedArrivalAt)} 도착` : '도착 예정 시각이 바뀌었어요'
  }
  switch (n.status) {
    case 'CLOSED':
      return '모집이 마감됐어요. 방장이 배달비를 넣으면 정산이 시작돼요'
    case 'SETTLING':
      // 금액은 정산표에 내 줄이 있을 때만 온다
      return n.amount === null ? '정산이 시작됐어요' : `정산이 시작됐어요 · 내 몫 ${won(n.amount)}원`
    case 'ORDERED':
      // 도착 예정을 받기 전의 옛 알림에는 시각이 없다
      return n.expectedArrivalAt
        ? `방장이 주문했어요 · ${clock(n.expectedArrivalAt)} 도착 예정`
        : '방장이 주문했어요. 배달이 오는 중이에요'
    case 'DELIVERED':
      return '배달이 도착했어요'
    case 'CANCELED':
      return n.cancelReason ? `방이 취소됐어요 · ${n.cancelReason}` : '방이 취소됐어요'
    case 'RECRUITING':
      return '모집 중이에요'
  }
}

// "3분 전", "2시간 전"처럼. 하루가 넘으면 날짜와 시각
export function ago(iso: string, now = Date.now()) {
  const minutes = Math.floor((now - new Date(iso).getTime()) / 60000)
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  return dateClock(iso)
}

// 내 방 목록 한 줄에서 "지금 할 일"을 문구로. 서버는 상태값만 주고 문구는 화면이 정한다
// active: 진행 중 탭인가. 취소된 방이 진행 중 탭에 있다면 환불이 남았다는 뜻이다
export function nextStep(room: MyRoom, active: boolean): { text: string; urgent: boolean } {
  if (!active) return { text: room.refundStatus === 'REFUNDED' ? '환불 완료' : '', urgent: false }
  if (room.host) {
    if (room.status === 'SETTLING') {
      const n = room.awaitingConfirmCount ?? 0
      return n > 0 ? { text: `확인할 송금 ${n}건`, urgent: true } : { text: '송금 기다리는 중', urgent: false }
    }
    if (room.status === 'CLOSED') return { text: '배달비 넣고 정산 시작하기', urgent: true }
    if (room.status === 'ORDERED') return { text: '음식 오면 배달완료 누르기', urgent: true }
    if (room.status === 'CANCELED') return { text: '돌려줄 돈이 남아 있어요', urgent: true }
    if (room.status === 'RECRUITING') return { text: '모집 중', urgent: false }
    return { text: '', urgent: false }
  }
  if (room.refundStatus) {
    return { text: REFUND_LABEL[room.refundStatus], urgent: room.refundStatus === 'REFUND_SENT' }
  }
  if (room.status === 'SETTLING') {
    if (room.confirmedPaidAt) return { text: '방장이 입금 확인했어요', urgent: false }
    if (room.markedPaidAt) return { text: '보냄 · 방장 확인 기다리는 중', urgent: false }
    return { text: '송금하고 보냈어요 누르기', urgent: true }
  }
  if (room.status === 'CLOSED') return { text: '방장이 배달비 넣는 중', urgent: false }
  if (room.status === 'ORDERED') return { text: '주문 완료 · 배달 오는 중', urgent: false }
  if (room.status === 'RECRUITING') return { text: '모집 중', urgent: false }
  return { text: '', urgent: false }
}
