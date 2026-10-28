#!/bin/bash
# 처리하지 못해 DLT(group-order-status-dlt)로 옮겨진 글을 보고, 원인을 고친 뒤 원래 토픽에 다시 넣는다
#   ./dlt-replay.sh          남은 글 목록. 본문은 찍지 않고 칸, 오프셋, 사건 id, 실패한 그룹, 예외 종류만 본다
#   ./dlt-replay.sh replay   아직 다시 넣지 않은 글을 전부 원래 토픽에 다시 넣는다
# 순서: 목록으로 예외 종류를 보고 → 원인을 고치고(DB 복구, 코드 수정 배포) → replay
# 원래 토픽에 넣으므로 알림과 통계 두 그룹이 모두 다시 읽는다. 둘 다 사건 id로 중복을 막아 이미 처리한 쪽은 그대로다
# 한 글이 두 그룹에서 다 실패했으면 DLT에 두 번 있다. 둘 다 넣어도 위와 같은 이유로 안전하다
# 어디까지 넣었는지는 컨슈머 그룹 dlt-replay의 책갈피로 남아, 다음 replay는 그 뒤부터 넣는다
# 사건 id 헤더가 없는 글은 다시 넣어도 또 실패하니 건너뛴다. 본문이 깨진 글도 다시 DLT로 온다. 목록에서 먼저 거른다
set -Eeuo pipefail
cd "$(dirname "$0")"

read -ra COMPOSE <<< "${COMPOSE:-docker compose}"
TOPIC=group-order-status
DLT=$TOPIC-dlt
BOOTSTRAP=kafka:19092
kafka() { "${COMPOSE[@]}" exec -T kafka "/opt/kafka/bin/$1" --bootstrap-server "$BOOTSTRAP" "${@:2}"; }

# 글 하나를 한 줄로 받으려고 구분자를 길게 정한다. DLT 헤더에는 여러 줄짜리 예외 스택과 이진값(원래 칸, 오프셋)이 들어 있어
# 기본 구분자(줄바꿈, 탭, 쉼표)로는 글과 글, 헤더와 헤더의 경계가 깨진다
REC='#~REC~#'
FLD='#~FLD~#'
HDR='#~HDR~#'
read_dlt() {
  kafka kafka-console-consumer.sh --topic "$DLT" --timeout-ms 10000 "$@" \
    --property print.partition=true --property print.offset=true \
    --property print.headers=true --property print.key=true \
    --property line.separator="$REC" --property key.separator="$FLD" --property headers.separator="$HDR" \
    2> >(grep -v -e 'TimeoutException' -e 'terminating consumer process' -e 'Processed a total' >&2) || true
}
# 10초 동안 새 글이 없으면 끝내도록 했다. 끝날 때 찍는 "terminating consumer process"와 TimeoutException은 정상이라 거른다

# 칸, 오프셋, 헤더, 키, 본문을 나눠 셸에 넘긴다. 헤더는 "이름:값"이고 첫 콜론에서 자른다
# 출력 한 줄: 칸 TAB 오프셋 TAB 사건id TAB 실패한그룹 TAB 예외종류 TAB 키 TAB 본문
# LC_ALL=C: 이진값 헤더를 글자로 읽으려다 "Invalid multibyte data" 경고가 난다. 바이트로 읽어도 본문의 한글은 그대로 지나간다
split_records() {
  LC_ALL=C awk -v RS="$REC" -v FS="$FLD" -v H="$HDR" '
    NF < 5 { next }
    {
      eventId = ""; group = ""; ex = ""
      n = split($3, hs, H)
      for (i = 1; i <= n; i++) {
        c = index(hs[i], ":"); name = substr(hs[i], 1, c - 1); value = substr(hs[i], c + 1)
        if (name == "eventId") eventId = value
        else if (name == "kafka_dlt-original-consumer-group") group = value
        else if (name == "kafka_dlt-exception-cause-fqcn") ex = value
        else if (name == "kafka_dlt-exception-fqcn" && ex == "") ex = value
      }
      sub(/^Partition:/, "", $1); sub(/^Offset:/, "", $2); gsub(/\n/, "", $1)
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", $1, $2, eventId, group, ex, $4, $5
    }'
}

case "${1:-list}" in
  list)
    # 그룹 없이 처음부터 읽는다. 책갈피를 남기지 않아 몇 번을 봐도 replay에 영향이 없다
    echo -e "칸\t오프셋\t사건id\t실패한 그룹\t예외"
    read_dlt --from-beginning | split_records | cut -f1-5
    ;;
  replay)
    # dlt-replay 그룹으로 읽어 책갈피를 남긴다. 처음 한 번은 맨 앞부터, 그 뒤로는 지난 replay 다음부터 읽는다
    LINES=$(mktemp)
    trap 'rm -f "$LINES"' EXIT
    read_dlt --group dlt-replay --from-beginning | split_records > "$LINES"
    if [ ! -s "$LINES" ]; then echo "다시 넣을 글이 없다"; exit 0; fi

    # 원래 토픽에 실을 모양으로: "eventId:번호 TAB 키 TAB 본문". 키가 같으니 원래와 같은 칸에 같은 순서로 들어간다
    # DLT가 붙인 헤더(예외, 원래 위치)는 싣지 않는다. 받는 쪽이 보는 헤더는 사건 id 하나다
    awk -F'\t' '$3 == "" { printf "건너뜀: 칸 %s 오프셋 %s(사건 id 헤더 없음)\n", $1, $2 > "/dev/stderr"; next }
                { printf "eventId:%s\t%s\t%s\n", $3, $6, $7 }' "$LINES" \
      | kafka kafka-console-producer.sh --topic "$TOPIC" \
          --property parse.key=true --property parse.headers=true \
      || { echo "넣다가 실패했다. 읽을 때 책갈피가 이미 넘어갔으니, 이번에 읽은 첫 오프셋으로 칸마다 되돌린 뒤 다시 replay 한다"
           awk -F'\t' '!($1 in first) { first[$1] = $2 } END { for (p in first) printf "  칸 %s: 오프셋 %s\n", p, first[p] }' "$LINES"
           echo "  되돌리기: kafka-consumer-groups.sh --bootstrap-server $BOOTSTRAP --group dlt-replay --topic $DLT:칸 --reset-offsets --to-offset 오프셋 --execute"
           exit 1; }
    echo "다시 넣은 글:"
    awk -F'\t' '$3 != "" { printf "  칸 %s 오프셋 %s → 사건 %s\n", $1, $2, $3 }' "$LINES"
    ;;
  *)
    echo "쓰는 법: $0 [list|replay]"; exit 1
    ;;
esac
