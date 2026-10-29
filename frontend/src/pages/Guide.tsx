import { Link } from 'react-router-dom'
import { Card } from '../components'

// 이용 유의사항. 숫자(3시간, 24시간, 6시간)는 서버 규칙(GroupOrder의 *_EXPIRY)과 같아야 한다
// 규칙을 바꾸면 여기도 같이 고친다
export default function Guide() {
  return (
    <div className="stack">
      <div className="guidehead">
        <h1 className="guidehead__title">이용 유의사항</h1>
        <p className="sub">반달은 돈을 맡아 두지 않아요. 같은 학교 사람끼리 직접 보내고 받는 대신, 아래 규칙으로 서로를 지켜요.</p>
      </div>

      <Card title="방이 흘러가는 순서">
        <ol className="steps">
          <li>
            <b>모집중</b> 메뉴를 담고 빼거나 방에서 나갈 수 있어요.
          </li>
          <li>
            <b>마감</b> 마감 시각이 되면 저절로 닫혀요. 방장 포함 2명이 안 되거나 최소주문금액을 못 채우면 그대로 취소돼요.
            조건을 채웠다면 방장이 마감 시각 전에 직접 닫을 수도 있어요.
          </li>
          <li>
            <b>정산중</b> 방장이 배달앱에 뜬 배달비를 넣으면 시작돼요. 내 몫은 <b>내 메뉴값 + 배달비를 인원수로 나눈 값</b>이에요.
            나누어떨어지지 않는 몇 원은 방장이 내요.
          </li>
          <li>
            <b>주문완료</b> 모두의 입금을 방장이 확인한 뒤에만 주문할 수 있어요. 방장이 배달앱의 도착 예정 시각을 같이 알려 줘요.
          </li>
          <li>
            <b>배달완료</b> 음식이 오면 방장이 눌러요.
          </li>
        </ol>
      </Card>

      <Card title="왜 송금이 주문보다 먼저인가요">
        <p>
          주문부터 하면 음식을 받고도 돈을 안 보내는 사람이 생겨도 방장이 막을 길이 없어요. 그래서 모두 보낸 것을 방장이 확인해야 주문 버튼이 열려요.
        </p>
        <p className="sub small">참여자의 "보냈어요"는 알림일 뿐이에요. 돈이 들어온 걸 아는 건 방장뿐이라, 방장의 "받았어요"가 기준이에요.</p>
      </Card>

      <Card title="저절로 넘어가는 경우">
        <ul className="rules">
          <li>
            <span className="rules__when num">마감 + 3시간</span>
            <span>방장이 배달비를 넣지 않으면 방이 취소돼요. 아직 아무도 돈을 보내지 않은 때라 환불할 것이 없어요.</span>
          </li>
          <li>
            <span className="rules__when num">정산 시작 + 24시간</span>
            <span>방장이 주문하지 않으면 방이 취소되고, 보낸 돈은 방장이 돌려줘야 해요.</span>
          </li>
          <li>
            <span className="rules__when num">주문 + 6시간</span>
            <span>방장이 배달완료를 누르지 않아도 배달완료로 넘어가요.</span>
          </li>
        </ul>
      </Card>

      <Card title="취소와 환불">
        <ul className="dots">
          <li>방장은 주문하기 전(모집중, 마감, 정산중)에만 방을 취소할 수 있어요. 주문한 뒤에는 취소할 수 없어요.</li>
          <li>마감 뒤에 취소할 때는 방장이 사유를 꼭 적어야 하고, 참여자 모두에게 보여요.</li>
          <li>정산중에 취소되면 참여자가 보낸 돈을 방장이 돌려줘요. 방장이 "돌려줬어요"를 누르면, 받은 사람이 "돌려받았어요"를 눌러야 환불이 끝나요.</li>
          <li>배달비는 아무도 "보냈어요"를 누르기 전에만 고칠 수 있어요. 은행 앱으로 먼저 보냈다면 차액은 방장과 직접 맞춰 주세요.</li>
        </ul>
      </Card>

      <Card title="방장이 연락이 안 될 때" tone="accent">
        <ul className="dots">
          <li>
            <b>보내기 전에 예금주를 꼭 맞춰 보세요.</b> 은행 앱이 보여 주는 예금주와 반달의 이름(<span className="num">박*수</span>처럼 가운데를 가림)이 같아야 해요.
          </li>
          <li>반달의 모든 사용자는 학교 메일로 인증했어요. 돈을 받고 사라진 사람도 학교가 학적으로 찾을 수 있어요.</li>
          <li>송금 화면과 대화 기록을 남겨 두세요. 문제가 생기면 그 기록으로 학교나 경찰에 알릴 수 있어요.</li>
          <li>정산 시작 24시간 안에 주문이 없으면 방은 저절로 취소돼요. 돌려받지 못했다면 "돌려받았어요"를 누르지 마세요.</li>
        </ul>
      </Card>

      <Link to="/" className="btn btn--ghost btn--block">
        모집 중인 방 보러 가기
      </Link>
    </div>
  )
}
