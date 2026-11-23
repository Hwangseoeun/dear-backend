#!/bin/bash
# 목록 조회 트래픽 중 공개를 RUNS 번 반복해서 재면서, 반복 사이의 rearm 은 앱을 거치지 않고 DB 에 직접 실행한다.
#
# 왜: 포화 상태(목록 요청이 DB 커넥션 풀을 모두 점유)에서는 POST /rearm 도 커넥션을 못 얻어 HTTP 500 으로 실패한다.
#     그래서 rearm 을 sql/rearm-publish-tier.sql 로 DB 에 직접 실행하는 별도 작업으로 뺀다.
#     k6 는 한 번만 실행되므로 k6 요약이 곧 RUNS 회 전체의 집계다.
#
# 동작
#   1) 트래픽 시작 전에 rearm (DB 직접) → 건수가 DAILY_COUNT 와 같은지 확인
#   2) k6(publish-with-list-traffic.js)를 백그라운드로 실행. 출력은 로그 파일에 기록. 이 스크립트가 k6 를 대신 실행하므로
#      k6 를 따로 또 실행하면 안 된다. (k6 스크립트는 rearm 을 하지 않으며, 그 역할을 이 스크립트가 맡는다)
#   3) 로그에 [PUBLISH+LIST] 줄이 새로 찍힐 때마다(= 공개 1회 종료) 다음 공개를 위해 rearm (DB 직접)
#      rearm 이 다음 공개 시작 전에 끝나도록 GAP_SEC 는 rearm 소요보다 충분히 길어야 한다. (rearm 소요는 <로그>.rearm 에 기록)
#   4) k6 종료까지 기다리고 k6 의 종료 코드를 그대로 반환
#
# 이 스크립트가 목록 트래픽 + 공개 본 측정의 표준 실행 방식이다 (모든 Tier). 공개 없는 기준선(PUBLISH=false)은 k6 로 직접 실행한다.
#
# 사용 (레포 루트에서)
#   LIST_RPS=500 BACKGROUND_COUNT=699000 DAILY_COUNT=1000 RUNS=10 GAP_SEC=15 ITER_BUDGET_SEC=100 \
#     k6/single/product/publish/run-with-external-rearm.sh /tmp/tier3.log
#
# 환경변수
#   LIST_RPS, BACKGROUND_COUNT, DAILY_COUNT  (필수) Tier 표 값. DAILY_COUNT 는 k6 의 EXPECTED_DAILY 로도 쓴다
#   RUNS             공개 반복 횟수. 기본 10
#   GAP_SEC          공개 사이 대기(초). 기본 15
#   ITER_BUDGET_SEC  공개 1회가 최대 걸린다고 보는 시간(초). 기본 70 (포화 Tier 는 /run 이 서버 대기열에 밀려 반복할수록 길어진다)
#   RUN_TIMEOUT_SEC  /run 응답을 기다리는 최대 시간(초). 기본 600. 포화가 심한 Tier 는 짧게(예: 120) 줘서 무한정 밀리는 것을 막는다
#   PSQL             DB 접속 명령. 기본 "docker exec -i postgres-local psql -U root -d dear_bench"
#
# ⚠️ 측정 전용 DB(dear_bench)와 bootRunBench 로 띄운 commerce-service 가 필요하다. (README 참고)

set -u

LOG="${1:?첫 번째 인자로 k6 로그를 쓸 파일 경로를 주세요}"
: "${LIST_RPS:?LIST_RPS 가 필요합니다}"
: "${BACKGROUND_COUNT:?BACKGROUND_COUNT 가 필요합니다}"
: "${DAILY_COUNT:?DAILY_COUNT 가 필요합니다}"
RUNS="${RUNS:-10}"
GAP_SEC="${GAP_SEC:-15}"
ITER_BUDGET_SEC="${ITER_BUDGET_SEC:-70}"
RUN_TIMEOUT_SEC="${RUN_TIMEOUT_SEC:-600}"
PSQL="${PSQL:-docker exec -i postgres-local psql -U root -d dear_bench}"

K6_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$K6_DIR/.." || exit 1   # 레포 루트
REARM_SQL="k6/single/product/publish/sql/rearm-publish-tier.sql"
SCRIPT="k6/single/product/publish/publish-with-list-traffic.js"

# 공개 윈도우 시작 = 어제 20:00 (앱 JVM 기본 시간대 = 이 머신의 로컬 시간대)
window_start() { date -v-1d '+%Y-%m-%d 20:00:00' 2>/dev/null || date -d yesterday '+%Y-%m-%d 20:00:00'; }

# DB 직접 rearm. 갱신 건수를 출력한다.
rearm() {
    $PSQL -q -tA -v background_count="$BACKGROUND_COUNT" -v window_start="$(window_start)" < "$REARM_SQL" | tr -d ' \r'
}

: > "$LOG"
: > "$LOG.rearm"

n=$(rearm)
if [ "$n" != "$DAILY_COUNT" ]; then
    echo "초기 rearm 건수가 맞지 않습니다: $n (기대 $DAILY_COUNT)" >&2
    exit 2
fi
echo "초기 rearm: $n 건" >> "$LOG.rearm"

k6 run -e LIST_RPS="$LIST_RPS" -e EXTERNAL_REARM=true -e RUNS="$RUNS" -e GAP_SEC="$GAP_SEC" \
    -e ITER_BUDGET_SEC="$ITER_BUDGET_SEC" -e RUN_TIMEOUT_SEC="$RUN_TIMEOUT_SEC" \
    -e EXPECTED_DAILY="$DAILY_COUNT" "$SCRIPT" > "$LOG" 2>&1 &
K6_PID=$!

done_runs=0
while kill -0 "$K6_PID" 2>/dev/null; do
    seen=$(grep -c '\[PUBLISH+LIST\]' "$LOG")
    while [ "$done_runs" -lt "$seen" ]; do
        done_runs=$((done_runs + 1))
        if [ "$done_runs" -lt "$RUNS" ]; then
            t0=$(date +%s)
            n=$(rearm)
            echo "공개 ${done_runs}회 후 rearm: ${n}건, $(( $(date +%s) - t0 ))초" >> "$LOG.rearm"
        fi
    done
    sleep 0.5
done

wait "$K6_PID"
exit $?
