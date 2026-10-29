import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 측정 대상: 오후 8시 상품 일괄 공개(ProductScheduler.publishDailyProducts)가 끝나기까지 걸리는 시간
//
// 공개 작업은 크론(@Scheduled)에서만 돌아 HTTP로 부를 수 없다.
// 그래서 로컬 측정 전용 API(commerce-service/src/test/.../PublishProductController)를 호출한다.
//   POST /internal/dev/product-publish/rearm  당일 상품을 공개 전(PREPARING)으로 되돌림  (SETUP, 측정 제외)
//   POST /internal/dev/product-publish/run    공개 작업 실행 + 서버가 잰 소요 시간(elapsedMs) 반환
//
// 전제
//   1) 측정 전용 DB(dear_bench)에 sql/seed-publish-tier.sql 로 Tier 데이터를 넣어 둔다.
//   2) commerce-service 를 측정 전용 태스크로 띄운다 (일반 bootRun 에는 이 API가 없다).
//        DB_URL=jdbc:postgresql://localhost:5432/dear_bench ./gradlew :commerce-service:bootRunBench
//   3) 내부 API라 게이트웨이를 거치지 않는다. commerce 를 직접 호출한다.
//
// ⚠️ 매 반복마다 당일 상품이 ON_SALE 로 바뀌었다가 rearm 으로 되돌아온다. (데이터는 소모되지 않는다)
// ⚠️ VU 단계(stages)는 쓰지 않는다. 1 VU 가 RUNS 번 순서대로 반복한다.
//
// 환경변수
//   COMMERCE_URL   기본 http://localhost:8082
//   RUNS           반복 횟수. 기본 6
//   GAP_SEC        반복 사이 대기(초). 기본 3
//   COLD_FIRST     true 면 첫 반복을 콜드로 집계한다. DB 컨테이너를 재시작한 직후 실행할 때 사용.
//   EXPECTED_DAILY 당일 상품 개수(Tier 표의 "일일 등록"). 주면 rearm/run 결과와 일치하는지 검사한다.

const COMMERCE_URL = __ENV.COMMERCE_URL || 'http://localhost:8082';
const BASE_URL = `${COMMERCE_URL}/internal/dev/product-publish`;

const RUNS = Number(__ENV.RUNS || 6);
const GAP_SEC = Number(__ENV.GAP_SEC || 3);
const COLD_FIRST = __ENV.COLD_FIRST === 'true';
const EXPECTED_DAILY =
    __ENV.EXPECTED_DAILY === undefined ? null : Number(__ENV.EXPECTED_DAILY);

// 서버가 응답 본문에 담아 주는 공개 작업 소요 시간(ms).
// k6 기본 메트릭(http_req_duration)은 HTTP 왕복 시간이라, 서버가 잰 값은 별도 메트릭으로 모아야
// 콜드/웜 태그별 집계와 임계값 판정을 할 수 있다.
const publishElapsed = new Trend('publish_elapsed_ms');

export const options = {
    scenarios: {
        measure: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: RUNS,
            maxDuration: __ENV.MAX_DURATION || '30m',
        },
    },
    thresholds: {
        // 요구사항: 20:01:00 이전에 공개 완료 → 공개 작업은 1분 이내에 끝나야 한다.
        'publish_elapsed_ms{phase:cold}': ['max<60000'],
        'publish_elapsed_ms{phase:warm}': ['max<60000'],
        http_req_failed: ['rate<0.01'],
        // EXPECTED_DAILY 와 어긋나면(시드 누락·오염) 종료 코드도 실패로 만든다.
        checks: ['rate==1'],
    },
    // 반복이 몇 번 안 되므로 평균·p95 대신 최소/중앙값/최대만 본다.
    summaryTrendStats: ['min', 'med', 'max'],
};

export default function () {
    const run = exec.vu.iterationInScenario;
    const phase = COLD_FIRST && run === 0 ? 'cold' : 'warm';

    // 1. 당일 상품을 공개 전 상태로 되돌린다 (측정 대상 아님)
    const rearmRes = http.post(`${BASE_URL}/rearm`, null, {
        tags: { name: 'SETUP POST /internal/dev/product-publish/rearm' },
        timeout: '10m',
    });

    if (rearmRes.status !== 200) {
        exec.test.abort(
            `rearm 실패 (HTTP ${rearmRes.status}). bootRunBench 로 띄웠는지, ` +
            `DB 이름에 _bench 가 포함되는지 확인하세요. 응답: ${rearmRes.body}`
        );
    }

    const rearmed = rearmRes.json('rearmed');
    check(rearmRes, {
        '당일 상품 개수 일치(rearm)': () =>
            EXPECTED_DAILY === null || rearmed === EXPECTED_DAILY,
    });

    // 2. 공개 작업 실행 (측정 대상)
    const runRes = http.post(`${BASE_URL}/run`, null, {
        tags: { name: 'POST /internal/dev/product-publish/run', phase },
        timeout: '10m',
    });

    const ok = check(runRes, { '공개 작업 200': (r) => r.status === 200 });

    if (ok) {
        const elapsedMs = runRes.json('elapsedMs');
        const count = runRes.json('count');

        publishElapsed.add(elapsedMs, { phase });
        check(runRes, {
            '공개 건수 일치(run)': () =>
                EXPECTED_DAILY === null || count === EXPECTED_DAILY,
        });

        console.log(
            `[PUBLISH] run=${run} phase=${phase} elapsedMs=${elapsedMs} count=${count} ` +
            `(HTTP ${Math.round(runRes.timings.duration)}ms)`
        );
    }

    sleep(GAP_SEC);
}
