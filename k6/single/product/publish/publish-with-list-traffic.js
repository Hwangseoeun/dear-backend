import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 측정 대상: 오후 8시 상품 일괄 공개가 "상품 목록 조회 트래픽이 흐르는 중에" 끝나기까지 걸리는 시간
//           + 공개 순간 목록 조회(GET /api/products)의 응답시간
//
// publish-duration.js 는 트래픽 없이 공개 작업만 잰다. 이 스크립트는 거기에 목록 조회 트래픽을 겹친다.
//
// 시나리오 2개
//   list_traffic  목록 조회를 LIST_RPS 로 일정하게 쏜다 (constant-arrival-rate = 응답이 느려져도 도착률 유지)
//   publish       트래픽이 안정된 뒤(WARMUP_SEC 후) RUNS 번, 아래를 반복한다
//                   POST run + 목록 조회 REQUESTS 개를 "동시에" 전송 (http.batch)
//                   → run 의 elapsedMs = 트래픽 중 공개 소요 시간, 같이 보낸 목록 요청 = 공개 순간의 목록 응답시간
//
// ⚠️ rearm(당일 상품을 공개 전 상태로 되돌리는 일)은 이 스크립트가 하지 않는다. 공개는 대상 상품을 모두 공개 상태로 바꾸므로
//    트래픽 시작 전과 공개가 끝날 때마다 rearm 이 필요하다. rearm 은 실제 서비스에 없는 측정용 준비 단계이고,
//    앱의 /rearm API 는 목록 트래픽이 DB 커넥션 풀을 점유하면 커넥션을 못 얻어 실패하므로 앱을 거치지 않고 DB 에 직접 한다.
//    → 본 측정(공개 포함)은 run-with-external-rearm.sh 로 실행한다. 그 스크립트가 이 k6 스크립트를 대신 실행하고
//      반복 사이에 sql/rearm-publish-tier.sql 로 rearm 해 준다. (PUBLISH=false 기준선은 rearm 이 필요 없어 k6 로 직접 실행)
//
// phase 태그 (목록 조회 요청)
//   warmup  트래픽 시작 직후 WARMUP_SEC 동안, JVM 워밍업, DB 커넥션·캐시 안정화 같은 초반 불안정 구간을 집계에서 빼기 위함
//   steady  워밍업이 끝난 뒤부터 트래픽이 끝날 때까지, 일정한 RPS로 계속 쏘는 요청
//   during  공개 요청과 같은 순간에 보낸 요청
//
// 비교 방법: PUBLISH=false 로 먼저 돌려 "공개 없는 목록 기준선"을 얻고, 본 측정과 비교한다.
//   본 측정의 steady 는 공개 중에 겹친 배경 요청도 포함하므로, 공개 순간의 영향은 during 이 가장 정확하다.
//
// 전제
//   1) 측정 전용 DB(dear_bench)에 sql/seed-publish-tier.sql 로 Tier 데이터를 넣어 둔다.
//   2) commerce-service 를 측정 전용 태스크로 띄운다 (일반 bootRun 에는 run API가 없다).
//        DB_URL=jdbc:postgresql://localhost:5432/dear_bench ./gradlew :commerce-service:bootRunBench
//   3) 내부 API(/internal/...)는 게이트웨이를 거치지 않으므로 commerce 를 직접 호출한다. 목록 조회도 같은 곳으로 보낸다.
//
// ⚠️ 목표 RPS 를 못 채우면(서버 포화) k6 가 dropped_iterations 로 알려 준다. 중단되지 않고 끝까지 돌며, 그 자체가 결과다.
// ⚠️ 이 스크립트의 목록 응답시간 임계값(p95<100ms)은 목표치다. Tier 가 커지면 깨지는 것이 정상일 수 있고, 그 경우 종료 코드는 99.
//
// 환경변수
//   LIST_RPS        (필수) 목록 조회 초당 요청 수. Tier 별 값은 README 참고
//   COMMERCE_URL    기본 http://localhost:8082
//   PUBLISH         false 면 공개 없이 목록만 돈다(기준선). 기본 true
//   RUNS            공개 반복 횟수. 기본 10
//   GAP_SEC         반복 사이 대기(초). 기본 5
//   WARMUP_SEC      공개 시작 전 트래픽만 흘리는 시간(초). 기본 30
//   BASELINE_SEC    PUBLISH=false 일 때 워밍업 이후 측정 시간(초). 기본 60
//   REQUESTS        공개와 동시에 보내는 목록 요청 수. 기본 5
//   MAX_VUS         목록 트래픽 VU 상한. 기본 500
//   EXPECTED_DAILY  당일 상품 개수(Tier 표의 "일일 등록"). 주면 공개된 건수가 일치하는지 검사 (rearm 누락도 여기서 드러남)
//   EXTERNAL_REARM  true 면 "반복 사이 rearm 을 밖에서 해 준다"는 표식. run-with-external-rearm.sh 가 설정하며 직접 지정할 일은 없다
//   ITER_BUDGET_SEC 공개 1회(run + 대기)가 최대 걸린다고 보는 시간(초). 기본 10
//                   목록 트래픽이 /run 이 끝날 때까지 계속 흐르도록, 포화 Tier 에서는 넉넉히(예: 90) 준다.
//   RUN_TIMEOUT_SEC /run 응답을 기다리는 최대 시간(초). 기본 600
//                   포화되면 버려진 목록 요청이 서버에 계속 쌓여 /run 이 몇 분~몇 시간씩 밀릴 수 있다.
//                   그 경우 이 시간 안에 응답이 없으면 실패(status=0)로 기록하고 다음 반복으로 넘어가게 한다.

const COMMERCE_URL = __ENV.COMMERCE_URL || 'http://localhost:8082';
const PUBLISH_URL = `${COMMERCE_URL}/internal/dev/product-publish`;
const LIST_URL = `${COMMERCE_URL}/api/products`;
const LIST_NAME = 'GET /api/products';

const LIST_RPS = Number(__ENV.LIST_RPS);
if (!(LIST_RPS > 0)) {
    throw new Error('LIST_RPS 가 필요합니다. 예: -e LIST_RPS=50 (Tier 별 값은 k6/single/README.md 참고)');
}

const PUBLISH = __ENV.PUBLISH !== 'false';
const RUNS = Number(__ENV.RUNS || 10);
const GAP_SEC = Number(__ENV.GAP_SEC || 5);
const WARMUP_SEC = Number(__ENV.WARMUP_SEC || 30);
const BASELINE_SEC = Number(__ENV.BASELINE_SEC || 60);
const REQUESTS = Number(__ENV.REQUESTS || 5);
const MAX_VUS = Number(__ENV.MAX_VUS || 500);
const EXPECTED_DAILY =
    __ENV.EXPECTED_DAILY === undefined ? null : Number(__ENV.EXPECTED_DAILY);
const RUN_TIMEOUT_SEC = Number(__ENV.RUN_TIMEOUT_SEC || 600);
// run-with-external-rearm.sh 가 넘기는 표식. 반복 사이 rearm 을 그 스크립트가 해 준다는 뜻이다.
const EXTERNAL_REARM = __ENV.EXTERNAL_REARM === 'true';

// 공개 한 번(run + 대기)이 최대 이만큼 걸린다고 보고 목록 트래픽이 공개 시나리오를 끝까지 덮게 한다.
const ITER_BUDGET_SEC = Number(__ENV.ITER_BUDGET_SEC || 10);
const COOLDOWN_SEC = 10;

const CATEGORIES = ['SNEAKERS', 'SPORTS_SHOES', 'DRESS_SHOES', 'BOOTS', 'SANDALS_SLIDES', 'WINTER_SHOES'];

// 서버가 응답 본문에 담아 주는 공개 작업 소요 시간(ms). 서비스 메서드 바깥에서 재므로 커넥션 풀 대기도 포함된다.
const publishElapsed = new Trend('publish_elapsed_ms');

const listDurationSec = PUBLISH
    ? WARMUP_SEC + RUNS * (GAP_SEC + ITER_BUDGET_SEC) + COOLDOWN_SEC
    : WARMUP_SEC + BASELINE_SEC;

const scenarios = {
    list_traffic: {
        executor: 'constant-arrival-rate',
        exec: 'listTraffic',
        rate: LIST_RPS,
        timeUnit: '1s',
        duration: `${listDurationSec}s`,
        preAllocatedVUs: Math.min(MAX_VUS, Math.max(10, Math.ceil(LIST_RPS / 5))),
        maxVUs: MAX_VUS,
    },
};

if (PUBLISH) {
    scenarios.publish = {
        executor: 'per-vu-iterations',
        exec: 'publishUnderLoad',
        vus: 1,
        iterations: RUNS,
        startTime: `${WARMUP_SEC}s`,
        maxDuration: __ENV.MAX_DURATION || '30m',
    };
}

const thresholds = {
    http_req_failed: ['rate<0.01'],
    // 목록 조회 목표 응답시간 100ms
    'http_req_duration{phase:steady}': ['p(95)<100'],
    // 항상 통과. 요약에 phase:steady 요청 수(달성 RPS 계산용)를 보이게 한다.
    'http_reqs{phase:steady}': ['count>=0'],
};

if (PUBLISH) {
    // 요구사항: 20:01:00 이전에 공개 완료 → 공개 작업은 1분 이내에 끝나야 한다.
    thresholds.publish_elapsed_ms = ['max<60000'];
    thresholds['http_req_duration{phase:during}'] = ['p(95)<100'];
    // EXPECTED_DAILY 와 어긋나면(시드 누락·오염) 종료 코드도 실패로 만든다.
    thresholds.checks = ['rate==1'];
}

export const options = {
    scenarios,
    thresholds,
    // 같은 순간에 보낼 요청(run 1 + 요청 REQUESTS)이 직렬화되지 않게 호스트당 동시 전송 수를 맞춘다.
    batch: Math.max(20, REQUESTS + 1),
    batchPerHost: Math.max(6, REQUESTS + 1),
    summaryTrendStats: ['min', 'med', 'p(95)', 'max'],
};

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

// 오후 8시 직후 사용자는 새 상품 첫 페이지에 몰리므로 page=1 고정
function listUrl() {
    return `${LIST_URL}?status=ON_SALE&category=${pick(CATEGORIES)}&page=1&size=20`;
}

export function setup() {
    const res = http.get(listUrl(), { tags: { name: 'SETUP GET /api/products' }, timeout: '60s' });
    const size = res.status === 200 ? (res.json('data.content') || []).length : -1;

    if (size < 0) {
        exec.test.abort(`목록 조회 사전 확인 실패 (HTTP ${res.status}). commerce-service 가 떠 있는지 확인하세요: ${COMMERCE_URL}`);
    }
    if (size === 0) {
        exec.test.abort('목록 조회 결과가 비어 있습니다. sql/seed-publish-tier.sql 로 시드를 넣었는지 확인하세요.');
    }

    // 공개를 반복하는데 반복 사이 rearm 이 없으면 두 번째 공개부터 대상이 0건이 된다.
    // run-with-external-rearm.sh 로 실행하면 EXTERNAL_REARM=true 가 넘어와 이 경고는 나오지 않는다.
    if (PUBLISH && RUNS > 1 && !EXTERNAL_REARM) {
        console.warn(
            '[SETUP] 공개를 여러 번 반복합니다. 이 스크립트는 rearm 을 하지 않으므로, 반복 사이 rearm 을 해 주는 ' +
            'run-with-external-rearm.sh 로 실행하세요. (k6 를 직접 실행하면 2번째 공개부터 건수 체크가 실패합니다)'
        );
    }

    const measureSec = listDurationSec - WARMUP_SEC;
    console.log(
        `[SETUP] 목표 ${LIST_RPS} RPS, 목록 트래픽 ${listDurationSec}s (워밍업 ${WARMUP_SEC}s + 측정 ${measureSec}s), ` +
        `공개 ${PUBLISH ? `${RUNS}회 (요청 ${REQUESTS}개/회)` : '없음(기준선)'}. ` +
        `달성 RPS ≈ http_reqs{phase:steady} 건수 ÷ ${measureSec}s`
    );
}

// 배경 목록 조회 트래픽
export function listTraffic() {
    const warm = Date.now() - exec.scenario.startTime < WARMUP_SEC * 1000;

    http.get(listUrl(), {
        tags: { name: LIST_NAME, phase: warm ? 'warmup' : 'steady' },
        timeout: '30s',
    });
}

// 트래픽 중 공개 실행
export function publishUnderLoad() {
    const run = exec.vu.iterationInScenario;

    // 공개 대상은 이 스크립트 밖에서(트래픽 시작 전, 공개 사이마다) 이미 공개 전 상태로 준비돼 있다고 가정한다.
    // 공개(run)와 목록 조회 요청을 같은 순간에 전송한다
    const requests = [
        {
            method: 'POST',
            url: `${PUBLISH_URL}/run`,
            params: {
                tags: { name: 'POST /internal/dev/product-publish/run', phase: 'run' },
                timeout: `${RUN_TIMEOUT_SEC}s`,
            },
        },
    ];

    for (let i = 0; i < REQUESTS; i++) {
        requests.push({
            method: 'GET',
            url: listUrl(),
            params: { tags: { name: LIST_NAME, phase: 'during' }, timeout: '30s' },
        });
    }

    const responses = http.batch(requests);
    const runRes = responses[0];
    const requestMs = responses.slice(1).map((r) => Math.round(r.timings.duration));

    const ok = check(runRes, { '공개 작업 200': (r) => r.status === 200 });

    if (ok) {
        const elapsedMs = runRes.json('elapsedMs');
        const count = runRes.json('count');

        publishElapsed.add(elapsedMs);
        check(runRes, {
            '공개 건수 일치(run)': () =>
                EXPECTED_DAILY === null || count === EXPECTED_DAILY,
        });

        console.log(
            `[PUBLISH+LIST] run=${run} elapsedMs=${elapsedMs} count=${count} ` +
            `(HTTP ${Math.round(runRes.timings.duration)}ms) requests(ms)=[${requestMs.join(', ')}]`
        );
    } else {
        // 포화로 공개가 실패한 경우(커넥션 풀 대기 타임아웃 등)도 상태와 소요를 남긴다.
        console.log(
            `[PUBLISH+LIST] run=${run} FAILED status=${runRes.status} ` +
            `(HTTP ${Math.round(runRes.timings.duration)}ms) requests(ms)=[${requestMs.join(', ')}] ` +
            `body=${String(runRes.body).slice(0, 200)}`
        );
    }

    sleep(GAP_SEC);
}
