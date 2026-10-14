import type { MyRoom, RefundStatus, Status } from './api'

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

// 내 방 목록 한 줄에서 "지금 할 일"을 문구로. 서버는 상태값만 주고 문구는 화면이 정한다 (ADR-053)
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
