import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  api,
  type ItemInput,
  type MyItems,
  type OrderItem,
  type ReviewList,
  type Room,
  type Settlement,
  type SettlementLine,
} from '../api'
import { useAuth } from '../auth'
import { Card, CopyButton, Progress, Row, StatusChip, useAction } from '../components'
import { clock, REFUND_LABEL, remaining, serial, won } from '../format'

const REFUND_CANCEL_TYPES = ['HOST_WHILE_SETTLING', 'SETTLING_TIMEOUT']

type LineAction = 'mark-paid' | 'confirm' | 'unconfirm' | 'send-refund' | 'deny-payment' | 'receive-refund'

const LINE_ACTION_DONE: Record<LineAction, string> = {
  'mark-paid': '보냈다고 알렸어요',
  confirm: '입금 확인했어요',
  unconfirm: '확인을 취소했어요',
  'send-refund': '돌려줬다고 알렸어요',
  'deny-payment': '입금 안 됨으로 표시했어요',
  'receive-refund': '환불 받음으로 표시했어요',
}

// 방 하나. 상태와 보는 사람(방장, 참여자, 구경꾼)에 따라 보이는 블록과 버튼이 달라진다
export default function RoomPage() {
  const { id } = useParams()
  const roomId = Number(id)
  const { me } = useAuth()
  const { busy, run } = useAction()

  const [room, setRoom] = useState<Room | null>(null)
  const [myItems, setMyItems] = useState<MyItems | null>(null)
  const [review, setReview] = useState<ReviewList | null>(null)
  const [settlement, setSettlement] = useState<Settlement | null>(null)
  const [notFound, setNotFound] = useState(false)

  const load = useCallback(async () => {
    let r: Room
    try {
      r = await api.room(roomId)
    } catch {
      setNotFound(true)
      return
    }
    const host = me?.id === r.hostId
    const involved = host || r.myParticipationId !== null
    const settlementVisible =
      ['SETTLING', 'ORDERED', 'DELIVERED'].includes(r.status) ||
      (r.status === 'CANCELED' && r.cancelType !== null && REFUND_CANCEL_TYPES.includes(r.cancelType))

    const [items, reviewList, settle] = await Promise.all([
      r.myParticipationId ? api.myItems(r.myParticipationId).catch(() => null) : null,
      host && ['RECRUITING', 'CLOSED'].includes(r.status) ? api.reviewList(roomId).catch(() => null) : null,
      involved && settlementVisible ? api.settlement(roomId).catch(() => null) : null,
    ])
    setRoom(r)
    setMyItems(items)
    setReview(reviewList)
    setSettlement(settle)
  }, [roomId, me?.id])

  useEffect(() => {
    void load()
  }, [load])

  // 버튼 하나를 누르고 화면 전체를 다시 읽는다. 여러 블록이 같이 바뀌기 때문이다
  const act = (fn: () => Promise<unknown>, done?: string) =>
    run(async () => {
      await fn()
      await load()
    }, done)

  if (notFound) {
    return (
      <div className="empty">
        <p className="empty__big">없는 방이에요</p>
        <Link to="/">목록으로 →</Link>
      </div>
    )
  }
  if (!room || !me) return <p className="empty sub">불러오는 중…</p>

  const isHost = me.id === room.hostId
  const joined = room.myParticipationId !== null
  const canceled = room.status === 'CANCELED'
  const myLine = settlement?.lines.find((l) => l.userId === me.id && !l.host)

  return (
    <div className="stack">
      <section className={`roomhero${canceled ? ' roomhero--void' : ''}`}>
        <div className="roomhero__top">
          <StatusChip status={room.status} />
          <span className="sub num">{serial(room.id)}</span>
        </div>
        <h1 className="roomhero__store">{room.storeName}</h1>
        <p className="roomhero__meta">
          {room.pickupSpotName}에서 받아요 · 방장 <b>{room.hostNickname}</b>
          {isHost && <span className="badge">나</span>}
        </p>
        <div className="facts">
          <div className="fact">
            <span className="fact__label">마감</span>
            <span className="fact__value num">{clock(room.deadlineAt)}</span>
            {room.status === 'RECRUITING' && <span className="fact__hint">{remaining(room.deadlineAt)}</span>}
          </div>
          <div className="fact">
            <span className="fact__label">인원</span>
            <span className="fact__value num">
              {room.participantCount}
              <small>/{room.capacity}</small>
            </span>
          </div>
          <div className="fact">
            <span className="fact__label">최소금액</span>
            <span className="fact__value num">{won(room.minOrderAmount)}</span>
          </div>
        </div>
        <Progress value={room.menuTotalAmount} goal={room.minOrderAmount} />
      </section>

      {canceled && (
        <Card tone="warn">
          <p className="strong">취소된 방이에요</p>
          {room.cancelReason && <p className="sub">사유: {room.cancelReason}</p>}
        </Card>
      )}

      {room.status === 'RECRUITING' && !joined && !isHost && (
        <Card>
          <p className="sub">같이 시키려면 참여하고 메뉴를 적어 주세요.</p>
          <button className="btn btn--primary btn--block btn--lg" disabled={busy} onClick={() => act(() => api.join(room.id), '참여했어요')}>
            이 방 참여하기
          </button>
        </Card>
      )}

      {!isHost && myLine && settlement && !canceled && (
        <PayCard
          line={myLine}
          settlement={settlement}
          busy={busy}
          onMarkPaid={() => act(() => api.lineAction(myLine.id, 'mark-paid'), LINE_ACTION_DONE['mark-paid'])}
        />
      )}

      {!isHost && myLine && canceled && myLine.refundStatus && (
        <Card title="환불" tone={myLine.refundStatus === 'REFUND_SENT' ? 'accent' : undefined}>
          <p className="strong">{REFUND_LABEL[myLine.refundStatus]}</p>
          <p className="sub">보낸 돈 {won(myLine.totalAmount)}원을 방장에게 돌려받아야 해요.</p>
          {myLine.refundStatus !== 'REFUNDED' && (
            <button
              className="btn btn--dark btn--block"
              disabled={busy}
              onClick={() => act(() => api.lineAction(myLine.id, 'receive-refund'), LINE_ACTION_DONE['receive-refund'])}
            >
              돌려받았어요
            </button>
          )}
        </Card>
      )}

      {joined && myItems && (
        <MyMenu
          items={myItems}
          editable={room.status === 'RECRUITING'}
          busy={busy}
          onAdd={(input) => act(() => api.addItem(myItems.participationId, input), '메뉴를 담았어요')}
          onUpdate={(itemId, input) => act(() => api.updateItem(itemId, input), '메뉴를 고쳤어요')}
          onRemove={(itemId) => act(() => api.removeItem(itemId), '메뉴를 뺐어요')}
        />
      )}

      {isHost && review && (
        <HostReview
          review={review}
          editable={room.status === 'CLOSED'}
          busy={busy}
          onUpdate={(itemId, input) => act(() => api.updateItem(itemId, input), '메뉴를 고쳤어요')}
        />
      )}

      {isHost && settlement && (
        <SettlementBoard
          settlement={settlement}
          busy={busy}
          onLine={(lineId, action) => act(() => api.lineAction(lineId, action), LINE_ACTION_DONE[action])}
        />
      )}

      {isHost && (
        <HostPanel
          room={room}
          busy={busy}
          onClose={() => act(() => api.closeRoom(room.id), '마감했어요')}
          onCancel={(reason) => act(() => api.cancelRoom(room.id, reason), '방을 취소했어요')}
          onStart={(fee) => act(() => api.startSettlement(room.id, fee), '정산을 시작했어요')}
          onChangeFee={(fee) => act(() => api.changeDeliveryFee(room.id, fee), '배달비를 고쳤어요')}
          onOrder={(paid) => act(() => api.order(room.id, paid), '주문 완료!')}
          onDeliver={() => act(() => api.deliver(room.id), '배달 완료!')}
        />
      )}

      {room.status === 'RECRUITING' && joined && !isHost && (
        <button
          className="btn btn--ghost btn--danger-text"
          disabled={busy}
          onClick={() => act(() => api.leave(room.myParticipationId!), '방에서 나왔어요')}
        >
          이 방에서 나가기
        </button>
      )}
    </div>
  )
}

// ---------- 참여자가 보낼 돈 ----------

function PayCard({ line, settlement, busy, onMarkPaid }: { line: SettlementLine; settlement: Settlement; busy: boolean; onMarkPaid: () => void }) {
  const account = settlement.hostAccount
  const sent = line.markedPaidAt !== null || line.confirmedPaidAt !== null
  return (
    <Card tone={sent ? undefined : 'accent'}>
      <p className="paycard__label">{sent ? '보낸 돈' : '방장에게 보낼 돈'}</p>
      <p className="paycard__amount num">{won(line.totalAmount)}원</p>
      <p className="sub small num">
        메뉴 {won(line.menuTotalAmount)} + 배달비 {won(line.feeShare)}
      </p>

      {!sent && (
        <>
          <div className="account">
            <span className="account__bank">{account.bankName}</span>
            <CopyButton text={account.accountNumber} />
            <span className="small account__holder">예금주 {account.accountHolder} · 받는 분 통장 표시는 닉네임으로</span>
          </div>
          <button className="btn btn--dark btn--block btn--lg" disabled={busy} onClick={onMarkPaid}>
            보냈어요
          </button>
        </>
      )}
      {sent && (
        <p className="paycard__state">{line.confirmedPaidAt ? `방장이 ${clock(line.confirmedPaidAt)}에 확인했어요` : '방장이 확인하면 끝나요'}</p>
      )}
    </Card>
  )
}

// ---------- 내 메뉴 ----------

function MyMenu({
  items,
  editable,
  busy,
  onAdd,
  onUpdate,
  onRemove,
}: {
  items: MyItems
  editable: boolean
  busy: boolean
  onAdd: (input: ItemInput) => Promise<boolean>
  onUpdate: (itemId: number, input: ItemInput) => Promise<boolean>
  onRemove: (itemId: number) => Promise<boolean>
}) {
  const [editing, setEditing] = useState<number | null>(null)
  const [adding, setAdding] = useState(items.items.length === 0 && editable)

  return (
    <Card title="내 메뉴" aside={<span className="num">{won(items.participationTotal)}원</span>}>
      {items.items.length === 0 && !adding && <p className="sub">아직 담은 메뉴가 없어요.</p>}
      {items.items.length > 0 && (
        <ul className="items">
          {items.items.map((item) =>
            editing === item.id ? (
              <li key={item.id}>
                <ItemForm
                  initial={item}
                  submitLabel="저장"
                  busy={busy}
                  onSubmit={async (input) => {
                    const ok = await onUpdate(item.id, input)
                    if (ok) setEditing(null)
                    return ok
                  }}
                  onCancel={() => setEditing(null)}
                />
              </li>
            ) : (
              <ItemRow
                key={item.id}
                item={item}
                actions={
                  editable && (
                    <>
                      <button className="textbtn" onClick={() => setEditing(item.id)}>
                        수정
                      </button>
                      <button className="textbtn textbtn--danger" disabled={busy} onClick={() => onRemove(item.id)}>
                        삭제
                      </button>
                    </>
                  )
                }
              />
            ),
          )}
        </ul>
      )}
      {editable &&
        editing === null &&
        (adding ? (
          <ItemForm
            submitLabel="담기"
            busy={busy}
            onSubmit={async (input) => {
              const ok = await onAdd(input)
              if (ok) setAdding(false)
              return ok
            }}
            onCancel={items.items.length > 0 ? () => setAdding(false) : undefined}
          />
        ) : (
          <button className="btn btn--ghost btn--block" onClick={() => setAdding(true)}>
            + 메뉴 추가
          </button>
        ))}
    </Card>
  )
}

function ItemRow({ item, actions }: { item: OrderItem; actions?: ReactNode }) {
  return (
    <li className="itemrow">
      <div className="itemrow__main">
        <span className="itemrow__name">
          {item.menuName}
          {item.quantity > 1 && <span className="itemrow__qty num"> × {item.quantity}</span>}
        </span>
        {item.options && <span className="itemrow__opt">{item.options}</span>}
        {item.editedByHost && <span className="badge badge--warn">방장이 고침</span>}
      </div>
      <span className="itemrow__price num">{won(item.amount)}</span>
      {actions && <div className="itemrow__actions">{actions}</div>}
    </li>
  )
}

function ItemForm({
  initial,
  submitLabel,
  busy,
  onSubmit,
  onCancel,
}: {
  initial?: OrderItem
  submitLabel: string
  busy: boolean
  onSubmit: (input: ItemInput) => Promise<boolean>
  onCancel?: () => void
}) {
  const [menuName, setMenuName] = useState(initial?.menuName ?? '')
  const [options, setOptions] = useState(initial?.options ?? '')
  const [price, setPrice] = useState(initial ? String(initial.unitPrice) : '')
  const [quantity, setQuantity] = useState(initial?.quantity ?? 1)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const ok = await onSubmit({ menuName: menuName.trim(), options: options.trim() || null, unitPrice: Number(price), quantity })
    if (ok && !initial) {
      setMenuName('')
      setOptions('')
      setPrice('')
      setQuantity(1)
    }
  }

  return (
    <form className="itemform" onSubmit={submit}>
      <label className="field">
        <span className="field__label">메뉴</span>
        <input value={menuName} onChange={(e) => setMenuName(e.target.value)} placeholder="마라탕 (중)" required maxLength={50} />
      </label>
      <label className="field">
        <span className="field__label">옵션 · 선택</span>
        <input value={options} onChange={(e) => setOptions(e.target.value)} placeholder="2단계, 분모자 추가" maxLength={100} />
      </label>
      <div className="itemform__row">
        <label className="field">
          <span className="field__label">한 개 가격</span>
          <div className="suffix">
            <input className="num" inputMode="numeric" value={price} onChange={(e) => setPrice(e.target.value.replace(/\D/g, ''))} placeholder="9000" required />
            <span>원</span>
          </div>
        </label>
        <div className="field">
          <span className="field__label">개수</span>
          <div className="stepper stepper--sm">
            <button type="button" onClick={() => setQuantity((q) => Math.max(1, q - 1))} aria-label="하나 줄이기">
              −
            </button>
            <span className="num">{quantity}</span>
            <button type="button" onClick={() => setQuantity((q) => Math.min(99, q + 1))} aria-label="하나 늘리기">
              +
            </button>
          </div>
        </div>
      </div>
      <div className="btnrow">
        {onCancel && (
          <button type="button" className="btn btn--ghost" onClick={onCancel}>
            취소
          </button>
        )}
        <button className="btn btn--primary" disabled={busy}>
          {submitLabel}
        </button>
      </div>
    </form>
  )
}

// ---------- 방장: 모아보기 ----------

function HostReview({
  review,
  editable,
  busy,
  onUpdate,
}: {
  review: ReviewList
  editable: boolean
  busy: boolean
  onUpdate: (itemId: number, input: ItemInput) => Promise<boolean>
}) {
  const [editing, setEditing] = useState<number | null>(null)
  return (
    <Card title="전체 주문 모아보기" aside={<span className="num">{won(review.groupOrderTotal)}원</span>}>
      {editable && <p className="sub small">배달앱 장바구니랑 맞춰 보세요. 틀린 건 여기서 고칠 수 있어요.</p>}
      <div className="people">
        {review.participants.map((p) => (
          <div key={p.participationId} className="person">
            <div className="person__head">
              <span className="strong">
                {p.nickname}
                {p.host && <span className="badge">방장</span>}
              </span>
              <span className="num sub">{won(p.participationTotal)}원</span>
            </div>
            {p.items.length === 0 ? (
              <p className="sub small">아직 메뉴 없음</p>
            ) : (
              <ul className="items">
                {p.items.map((item) =>
                  editing === item.id ? (
                    <li key={item.id}>
                      <ItemForm
                        initial={item}
                        submitLabel="저장"
                        busy={busy}
                        onSubmit={async (input) => {
                          const ok = await onUpdate(item.id, input)
                          if (ok) setEditing(null)
                          return ok
                        }}
                        onCancel={() => setEditing(null)}
                      />
                    </li>
                  ) : (
                    <ItemRow
                      key={item.id}
                      item={item}
                      actions={
                        editable && (
                          <button className="textbtn" onClick={() => setEditing(item.id)}>
                            수정
                          </button>
                        )
                      }
                    />
                  ),
                )}
              </ul>
            )}
          </div>
        ))}
      </div>
    </Card>
  )
}

// ---------- 방장: 입금/환불 현황 ----------

function lineState(line: SettlementLine, canceled: boolean): { text: string; tone: 'done' | 'wait' | 'todo' } {
  if (canceled) {
    if (!line.refundStatus) return { text: '보낸 돈 없음', tone: 'done' }
    return { text: REFUND_LABEL[line.refundStatus], tone: line.refundStatus === 'REFUNDED' ? 'done' : 'todo' }
  }
  if (line.confirmedPaidAt) return { text: '확인 완료', tone: 'done' }
  if (line.markedPaidAt) return { text: '보냈대요 · 확인해 주세요', tone: 'todo' }
  return { text: line.confirmRevokedAt ? '확인 취소됨' : '아직 안 보냄', tone: 'wait' }
}

function SettlementBoard({
  settlement,
  busy,
  onLine,
}: {
  settlement: Settlement
  busy: boolean
  onLine: (lineId: number, action: LineAction) => void
}) {
  const canceled = settlement.status === 'CANCELED'
  const members = settlement.lines.filter((l) => !l.host)
  const hostLine = settlement.lines.find((l) => l.host)
  const doneCount = members.filter((l) => (canceled ? !l.refundStatus || l.refundStatus === 'REFUNDED' : l.confirmedPaidAt)).length

  return (
    <Card
      title={canceled ? '환불 현황' : '입금 현황'}
      aside={
        <span className="num">
          {doneCount}/{members.length} 완료
        </span>
      }
    >
      <ul className="payees">
        {members.map((line) => {
          const state = lineState(line, canceled)
          const buttons: { label: string; action: LineAction; danger?: boolean }[] = []
          if (!canceled) {
            if (!line.confirmedPaidAt) buttons.push({ label: '받았어요', action: 'confirm' })
            else buttons.push({ label: '확인 취소', action: 'unconfirm', danger: true })
          } else if (line.refundStatus && line.refundStatus !== 'REFUNDED') {
            if (line.refundStatus !== 'REFUND_SENT') buttons.push({ label: '돌려줬어요', action: 'send-refund' })
            if (line.refundStatus === 'REFUND_PENDING' && !line.confirmedPaidAt)
              buttons.push({ label: '입금 안 됐어요', action: 'deny-payment', danger: true })
          }
          return (
            <li key={line.id} className="payee">
              <div className="payee__info">
                <span className="strong">{line.nickname}</span>
                <span className={`state state--${state.tone}`}>{state.text}</span>
              </div>
              <span className="payee__amount num">{won(line.totalAmount)}</span>
              {buttons.length > 0 && (
                <div className="payee__actions">
                  {buttons.map((b) => (
                    <button
                      key={b.action}
                      className={`btn btn--sm ${b.danger ? 'btn--ghost btn--danger-text' : 'btn--dark'}`}
                      disabled={busy}
                      onClick={() => onLine(line.id, b.action)}
                    >
                      {b.label}
                    </button>
                  ))}
                </div>
              )}
            </li>
          )
        })}
      </ul>
      <div className="totals">
        {hostLine && <Row label="내 몫" value={`${won(hostLine.totalAmount)}원`} />}
        <Row label="메뉴 합계" value={`${won(settlement.menuTotalAmount)}원`} />
        <Row label="배달비" value={`${won(settlement.deliveryFee)}원`} />
        <Row label="총 결제할 돈" value={`${won(settlement.menuTotalAmount + settlement.deliveryFee)}원`} strong />
      </div>
    </Card>
  )
}

// ---------- 방장: 다음 단계 ----------

function HostPanel({
  room,
  busy,
  onClose,
  onCancel,
  onStart,
  onChangeFee,
  onOrder,
  onDeliver,
}: {
  room: Room
  busy: boolean
  onClose: () => Promise<boolean>
  onCancel: (reason: string | null) => Promise<boolean>
  onStart: (fee: number) => Promise<boolean>
  onChangeFee: (fee: number) => Promise<boolean>
  onOrder: (paid: number | null) => Promise<boolean>
  onDeliver: () => Promise<boolean>
}) {
  const [fee, setFee] = useState('')
  const [paid, setPaid] = useState('')
  const [feeOpen, setFeeOpen] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [reason, setReason] = useState('')

  const cancellable = ['RECRUITING', 'CLOSED', 'SETTLING'].includes(room.status)
  const reasonRequired = room.status !== 'RECRUITING'
  if (!['RECRUITING', 'CLOSED', 'SETTLING', 'ORDERED'].includes(room.status)) return null

  return (
    <Card title="방장 메뉴">
      <div className="stack stack--tight">
        {room.status === 'RECRUITING' && (
          <>
            <p className="sub small">나 포함 2명 이상, 최소금액을 채우면 마감 시간 전에도 닫을 수 있어요.</p>
            <button className="btn btn--dark btn--block btn--lg" disabled={busy} onClick={onClose}>
              지금 마감하기
            </button>
          </>
        )}

        {room.status === 'CLOSED' && (
          <form
            className="stack stack--tight"
            onSubmit={(e) => {
              e.preventDefault()
              void onStart(Number(fee)).then((ok) => ok && setFee(''))
            }}
          >
            <label className="field">
              <span className="field__label">배달앱에 뜬 배달비</span>
              <div className="suffix">
                <input className="num" inputMode="numeric" value={fee} onChange={(e) => setFee(e.target.value.replace(/\D/g, ''))} placeholder="3000" required />
                <span>원</span>
              </div>
            </label>
            <button className="btn btn--dark btn--block btn--lg" disabled={busy}>
              정산 시작하기
            </button>
          </form>
        )}

        {room.status === 'SETTLING' && (
          <>
            <form
              className="stack stack--tight"
              onSubmit={(e) => {
                e.preventDefault()
                void onOrder(paid ? Number(paid) : null)
              }}
            >
              <label className="field">
                <span className="field__label">실제 결제한 금액 · 선택</span>
                <div className="suffix">
                  <input className="num" inputMode="numeric" value={paid} onChange={(e) => setPaid(e.target.value.replace(/\D/g, ''))} placeholder="쿠폰 쓴 금액 그대로" />
                  <span>원</span>
                </div>
              </label>
              <button className="btn btn--dark btn--block btn--lg" disabled={busy}>
                주문했어요
              </button>
            </form>
            {!feeOpen ? (
              <button className="textbtn" onClick={() => setFeeOpen(true)}>
                배달비가 바뀌었나요?
              </button>
            ) : (
              <form
                className="spotpick"
                onSubmit={(e) => {
                  e.preventDefault()
                  void onChangeFee(Number(fee)).then((ok) => {
                    if (ok) {
                      setFee('')
                      setFeeOpen(false)
                    }
                  })
                }}
              >
                <input className="num" inputMode="numeric" value={fee} onChange={(e) => setFee(e.target.value.replace(/\D/g, ''))} placeholder="바뀐 배달비" required />
                <button className="btn btn--primary btn--sm" disabled={busy}>
                  고치기
                </button>
              </form>
            )}
          </>
        )}

        {room.status === 'ORDERED' && (
          <button className="btn btn--dark btn--block btn--lg" disabled={busy} onClick={onDeliver}>
            배달 왔어요
          </button>
        )}

        {cancellable && !cancelOpen && (
          <button className="textbtn textbtn--danger" onClick={() => setCancelOpen(true)}>
            방 취소하기
          </button>
        )}
        {cancellable && cancelOpen && (
          <form
            className="cancelbox"
            onSubmit={(e) => {
              e.preventDefault()
              void onCancel(reason.trim() || null)
            }}
          >
            <label className="field">
              <span className="field__label">{reasonRequired ? '취소 사유 · 참여자에게 보여요' : '취소 사유 · 선택'}</span>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} required={reasonRequired} />
            </label>
            <div className="btnrow">
              <button type="button" className="btn btn--ghost" onClick={() => setCancelOpen(false)}>
                그만두기
              </button>
              <button className="btn btn--danger" disabled={busy}>
                취소 확정
              </button>
            </div>
          </form>
        )}
      </div>
    </Card>
  )
}
