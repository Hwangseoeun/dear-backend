# 단일 엔드포인트 부하 테스트

`k6/scenarios/`의 시나리오 테스트와 목적이 다릅니다.

| | 시나리오 (`scenarios/`) | 단일 (`single/`) |
|---|---|---|
| 재현하는 것 | 실제 사용자 행동 흐름 | 특정 엔드포인트의 한계 |
| VU 50일 때 | 여러 API에 분산 | **한 API에 집중** |
| 알 수 있는 것 | 서비스가 실사용 패턴을 견디는가 | 이 API가 초당 몇 건을 처리하는가 |

## 왜 분리했나

시나리오 방식은 VU 50이어도 개별 API가 받는 동시 요청이 적습니다.
`auth-flow` 실측 기준으로 계산하면 이렇습니다.

```
반복 1회 = 4.91초 (요청 4개 + 대기 4초)
VU 50 → 초당 반복 10.2회 → 각 API가 받는 요청 초당 10.2건
      → 평균 응답 225ms → 동시 처리 요청 약 2.3건
```

**VU가 50인데 개별 API는 동시에 2~3건만 받습니다.** 나머지 시간은 다른 API를 치거나
대기 중입니다. 이 폴더의 스크립트는 반대로 모든 VU를 하나의 엔드포인트에 몰아넣습니다.

## 실행

```bash
cd <레포>/k6
```

기본 실행 (10분 10초)

```bash
docker run --rm -v "$PWD:/scripts" -w /scripts --cpus="2" --memory="8g" -e GATEWAY_URL=http://54.180.71.120:8080 grafana/k6 run single/search/products.js
```

짧게 (2분 40초) — 개선 전후를 여러 번 비교할 때

```bash
docker run --rm -v "$PWD:/scripts" -w /scripts --cpus="2" --memory="8g" -e TEST_TYPE=focus -e GATEWAY_URL=http://54.180.71.120:8080 grafana/k6 run single/search/products.js
```

최대 처리량 (대기 제거)

```bash
docker run --rm -v "$PWD:/scripts" -w /scripts --cpus="2" --memory="8g" -e THINK_TIME=0 -e GATEWAY_URL=http://54.180.71.120:8080 grafana/k6 run single/search/products.js
```

상품 ID가 필요한 경우

```bash
docker run --rm -v "$PWD:/scripts" -w /scripts --cpus="2" --memory="8g" -e GATEWAY_URL=http://54.180.71.120:8080 -e PRODUCT_IDS="$PIDS_READ" grafana/k6 run single/cart/list.js
```

## 결과 읽는 법

모든 스크립트는 **측정 대상 요청의 p95를 따로 집계**합니다.
준비 단계 요청은 `PREP` 태그가 붙어 측정값에 섞이지 않습니다.

```
http_req_duration{name:GET /api/carts}...: p(95)=890ms  ✗   ← 이 값을 보면 됩니다
http_req_duration.......................: p(95)=650ms       ← PREP 포함 전체 (참고용)
```

## 파일 목록

### auth (5)
| 파일 | 대상 | 비고 |
|---|---|---|
| `signup.js` | `POST /api/auth/signup` | BCrypt 포함, 목표 500ms |
| `login.js` | `POST /api/auth/login` | BCrypt 포함, 목표 500ms |
| `reissue.js` | `POST /api/auth/reissue` | 토큰 교체를 이어받아 반복 |
| `logout.js` | `POST /api/auth/logout` | |
| `withdraw.js` | `DELETE /api/auth/withdraw` | 계정 소모 |

### member (6)
| 파일 | 대상 | 비고 |
|---|---|---|
| `profile-get.js` | `GET /api/members/profile` | |
| `profile-update.js` | `PATCH /api/members/profile/me` | 닉네임 중복검사 비용 |
| `seller-register.js` | `POST /api/members/me/seller` | 계정 소모, 계좌 암호화 |
| `seller-update.js` | `PATCH /api/members/me/seller` | 계좌 암호화 |
| `seller-get.js` | `GET /api/members/me/seller` | 계좌 마스킹 |
| `seller-unregister.js` | `DELETE /api/members/me/seller` | 계정 소모 |

### scrap (3)
| 파일 | 대상 | 비고 |
|---|---|---|
| `add.js` | `POST /api/scraps/:productId` | `PRODUCT_IDS` 필요 |
| `list.js` | `GET /api/scraps` | `PRODUCT_IDS` 필요 |
| `delete.js` | `DELETE /api/scraps/:productId` | `PRODUCT_IDS` 필요 |

### product (7)
| 파일 | 대상 | 비고 |
|---|---|---|
| `list.js` | `GET /api/products` | ⚠️ 페이지네이션 없음, 968KB/6초 |
| `detail.js` | `GET /api/products/:id` | N+1 확인, `PRODUCT_IDS` 필요 |
| `my-products.js` | `GET /api/products/me` | |
| `create.js` | `POST /api/products` | 데이터 누적 |
| `update.js` | `PATCH /api/products/:id` | |
| `delete.js` | `DELETE /api/products/:id` | 생성/삭제 짝 |
| `presigned-urls.js` | `POST /api/products/images/presigned-urls` | AWS 호출 없음 |
| `publish/publish-duration.js` | `POST /internal/dev/product-publish/run` | 오후 8시 일괄 공개 소요 시간 (테스트 전용 API, 아래 참고) |
| `publish/publish-with-list-traffic.js` | `POST /internal/dev/product-publish/run` + `GET /api/products` | 목록 조회 트래픽이 흐르는 중의 공개 소요 시간·목록 응답시간. 본 측정은 `publish/run-with-external-rearm.sh`로 실행 (아래 참고) |

#### 상품 일괄 공개 소요 시간 측정 (`product/publish/publish-duration.js`)

오후 8시 일괄 공개(`ProductScheduler.publishDailyProducts`)는 크론에서만 돌아 HTTP로 부를 수 없어서,
`commerce-service/src/test`의 측정 전용 API(`PublishProductController`)를 호출해 서버가 잰 소요 시간을 측정합니다.
이 API는 운영 jar에 포함되지 않고, 로컬 전용 태스크로 띄울 때만 로드됩니다. 측정 전용 DB(이름에 `_bench`)가 아니면 403으로 거부합니다.
VU 단계는 사용하지 않고 1 VU가 `RUNS`번 반복합니다.

```bash
# 1) 최초 1회: 측정 전용 DB
docker exec postgres-local psql -U root -d postgres -c "CREATE DATABASE dear_bench"

# 2) 측정 전용 태스크로 commerce-service 기동
DB_URL=jdbc:postgresql://localhost:5432/dear_bench ./gradlew :commerce-service:bootRunBench

# 3) Tier 시드 (Tier 표는 sql/seed-publish-tier.sql 헤더 참고) — 예: Tier 2
docker exec -i postgres-local psql -U root -d dear_bench -v background_count=39900 -v daily_count=100 < k6/single/product/publish/sql/seed-publish-tier.sql

# 4) 측정 (콜드 측정은 `docker restart postgres-local` 직후 COLD_FIRST=true)
k6 run -e RUNS=6 -e COLD_FIRST=true -e EXPECTED_DAILY=100 k6/single/product/publish/publish-duration.js

# 5) 정리
docker exec -i postgres-local psql -U root -d dear_bench < k6/single/product/publish/sql/cleanup-publish-tier.sql
```

결과의 `publish_elapsed_ms`(min/med/p(95)/max, `phase:cold|warm`)가 공개 작업이 커밋까지 끝나는 데 걸린 시간입니다.
`EXPECTED_DAILY`와 개수가 어긋나면(시드 누락·오염) 종료 코드가 실패(99)로 나옵니다.

#### 목록 조회 트래픽과 함께 상품 일괄 공개 소요 시간 측정 (`product/publish/publish-with-list-traffic.js`)

오후 8시에 상품이 공개되면 사용자가 새 상품을 보러 몰려 목록 조회(`GET /api/products`)가 동시에 들어옵니다.
이 스크립트는 목록 조회를 일정한 초당 요청 수(`LIST_RPS`)로 계속 쏘는 중에 공개 작업을 `RUNS`번 실행해서 다음을 잽니다.

트래픽 양은 DAU에서 환산합니다. 피크 동시접속 = DAU × 10%, 동시접속자 1명이 2초에 1번 목록을 본다고 가정합니다.

| Tier | DAU | 피크 동시접속 | `LIST_RPS` | `BACKGROUND_COUNT` / `DAILY_COUNT` | `RUNS` | `GAP_SEC` | `ITER_BUDGET_SEC` | `RUN_TIMEOUT_SEC` |
|---|---|---|---|---|---|---|---|---|
| 1 | 100 | 10 | 5 | 1990 / 10 | 10 | 5 | 10 | 기본 |
| 2 | 1,000 | 100 | 50 | 39900 / 100 | 10 | 5 | 10 | 기본 |
| 3 | 10,000 | 1,000 | 500 | 699000 / 1000 | 10 | 15 | 100 | 기본 |
| 4 | 100,000 | 10,000 | 5000 | 9990000 / 10000 | 5 | 15 | 135 | 120 |

**실행 방식**

| 단계 | 실행할 것 | 비고 |
|---|---|---|
| 기준선 (공개 없이 목록만) | `k6 run -e PUBLISH=false …`를 **직접** 실행 | 공개가 없어 rearm이 필요 없습니다. |
| 본 측정 (목록 트래픽 + 공개 반복) | `run-with-external-rearm.sh`**만** 실행 | 이 스크립트가 k6를 대신 실행하고 반복 사이 rearm을 DB에 직접 합니다. **k6를 따로 또 실행하지 마세요.** 모든 Tier에서 같은 방식입니다. |

`publish-with-list-traffic.js`는 셸 스크립트를 부르지 않고, 셸 스크립트가 이 k6 스크립트를 실행합니다.
k6 스크립트는 rearm을 하지 않으므로 공개를 여러 번 재려면 반드시 셸 스크립트로 실행해야 합니다. (k6를 직접 실행하면 2번째 공개부터 대상이 0건이 되어 `공개 건수 일치(run)` 체크가 실패합니다.)

```bash
# 1) 최초 1회: 측정 전용 DB
docker exec postgres-local psql -U root -d postgres -c "CREATE DATABASE dear_bench"

# 2) 측정 전용 태스크로 commerce-service 기동
DB_URL=jdbc:postgresql://localhost:5432/dear_bench ./gradlew :commerce-service:bootRunBench

# 3) Tier 시드 (위 표의 BACKGROUND_COUNT / DAILY_COUNT) — 예: Tier 3
docker exec -i postgres-local psql -U root -d dear_bench -v background_count=699000 -v daily_count=1000 < k6/single/product/publish/sql/seed-publish-tier.sql

# 4) 기준선: 공개 없이 목록만 (PUBLISH=false) — 예: Tier 3
k6 run -e PUBLISH=false -e LIST_RPS=500 k6/single/product/publish/publish-with-list-traffic.js

# 5) 본 측정: 목록 트래픽 + 공개 반복 — 예: Tier 3. 인자는 k6 로그를 쓸 경로입니다.
#    반복 사이 rearm 소요는 <로그>.rearm 에 기록됩니다.
LIST_RPS=500 BACKGROUND_COUNT=699000 DAILY_COUNT=1000 RUNS=10 GAP_SEC=15 ITER_BUDGET_SEC=100 \
  k6/single/product/publish/run-with-external-rearm.sh /tmp/tier3.log

# 6) 정리
docker exec -i postgres-local psql -U root -d dear_bench < k6/single/product/publish/sql/cleanup-publish-tier.sql
```

**왜 rearm을 DB에 직접 하나**

공개는 대상 상품을 모두 공개 상태로 바꾸므로, 여러 번 재려면 매번 공개 전 상태로 되돌리는 준비(rearm)가 필요합니다.
rearm은 실제 서비스에는 없는 측정용 단계입니다(실제로는 그날 등록된 상품이 공개 전 상태로 쌓여 있습니다).

앱의 `/rearm` API로 하면 목록 요청이 DB 커넥션 풀(기본 10개)을 모두 점유한 포화 상태에서 커넥션을 못 얻어 HTTP 500으로 실패하고(Tier 4, 5,000 RPS) 공개를 시도하지도 못합니다.
그래서 모든 Tier에서 rearm을 앱을 거치지 않고 DB에 직접(`sql/rearm-publish-tier.sql`) 실행해 Tier 간, 개선 전후 비교의 조건을 같게 맞춥니다.

공개(`/run`)는 그대로 앱의 같은 커넥션 풀을 거치므로 "포화 시 공개가 커넥션을 기다리는 현상"은 그대로 측정됩니다.

결과 읽는 법
- `publish_elapsed_ms`: 목록 트래픽이 있을 때의 공개 소요 시간(커넥션 풀 대기 포함). `publish-duration.js` 결과와 비교하면 트래픽의 영향이 보입니다.
- `http_req_duration{phase:steady}`: 공개와 무관하게 흐르는 배경 목록 요청의 응답시간. `PUBLISH=false`(기준선)에서는 평소 응답시간입니다.
- `http_req_duration{phase:during}`: 공개와 같은 순간에 보낸 목록 요청의 응답시간. **기준선의 `steady`와 비교**하면 공개 순간의 영향이 보입니다.
- 달성 RPS = `http_reqs{phase:steady}` 건수 ÷ 측정 시간(워밍업 제외, 시작 시 `[SETUP]` 로그에 출력). 목표 `LIST_RPS`에 못 미치거나 `dropped_iterations`가 나오면 서버가 그 트래픽을 감당하지 못한 것입니다.
- 목록 p95 100ms 임계값은 목표치라 Tier가 커지면 깨져서 종료 코드가 99가 될 수 있습니다. 오류가 아니라 결과입니다.
- `/run`이 실패하면(커넥션 풀 대기 타임아웃 등) `[PUBLISH+LIST] run=N FAILED status=…` 로그로 상태와 소요 시간이 남습니다.

주의사항
- 트래픽 길이 = 워밍업 30초 + `RUNS` × (`GAP_SEC` + `ITER_BUDGET_SEC`) + 10초.
- **포화 Tier에서는 `/run`이 반복할수록 오래 밀립니다.** 목록 요청이 서버 처리 속도보다 빨리 들어오고, k6가 포기한 요청도 서버가 계속 처리하기 때문에 대기열이 쌓입니다(Tier 3 예: `/run` HTTP 응답이 47초 → 412초). 그래서 뒤쪽 공개는 트래픽이 끝난 뒤에 실행될 수 있으니, 결과에서 각 공개가 트래픽 구간 안이었는지 확인하세요. 서비스 내부 소요(`elapsedMs`, 커넥션 풀 대기 포함)는 이 대기열 시간과 별개입니다.
- 포화가 극심하면(Tier 4) `/run` 응답이 수 분~수 시간 밀려 반복이 끝나지 않으므로 `RUN_TIMEOUT_SEC`로 응답 대기 시간을 제한합니다(초과하면 `FAILED status=0`).
- 포화 측정이 끝난 뒤 다음 측정 전에는 앱을 재시작하거나 대기열이 비워질 때까지 기다리세요. 버려진 요청이 한동안 서버를 점유합니다.
- `GAP_SEC`는 rearm 소요보다 길어야 합니다. 짧으면 다음 공개 때 대상이 아직 공개 전 상태가 아니라 `공개 건수 일치(run)` 체크가 실패합니다.
- **오후 8시(스케줄러 시각)를 걸치지 않게 측정하세요.** 앱에는 실제 공개 스케줄러(`@Scheduled(cron = "0 0 20 * * *")`)가 돌고 있어서, 측정 중 20:00가 지나면 스케줄러가 준비해 둔 대상 상품을 먼저 공개해 가고 이후 `/run`은 0건이 됩니다(`공개 건수 일치(run)` 실패).
- rearm은 `window_start`(어제 20:00)를 셸에서 계산해 넘기므로 앱과 같은 머신·시간대에서 실행해야 합니다(macOS는 `date -v`, Linux는 `date -d`로 자동 선택). DB 접속 명령은 기본 `docker exec -i postgres-local psql -U root -d dear_bench`이고 `PSQL` 환경변수로 바꿀 수 있습니다.
- 측정 전용 DB(이름이 `_bench`로 끝나는 DB)가 아니면 rearm SQL과 시드·정리 SQL이 즉시 중단됩니다.

### cart (4)
| 파일 | 대상 | 비고 |
|---|---|---|
| `add-item.js` | `POST /api/carts/items` | `PRODUCT_IDS` 필요 |
| `list.js` | `GET /api/carts` | N+1 확인, `PREP_COUNT`로 항목 수 조절 |
| `delete-selected.js` | `DELETE /api/carts/items` | `PRODUCT_IDS` 필요 |
| `delete-all.js` | `DELETE /api/carts/items/all` | `PRODUCT_IDS` 필요 |

### offer (6)
| 파일 | 대상 | 비고 |
|---|---|---|
| `snapshot.js` | `POST /api/offers/snapshot` | 갱신 구조라 반복 가능 |
| `create.js` | `POST /api/offers` | 상품당 1회, OFFER+ON_SALE 필요 |
| `detail.js` | `GET /api/offers/:id` | |
| `list-by-product.js` | `GET /api/offers/products/:productId` | ⚠️ 판매자 로그인 필요 — 현재 실행 불가 |
| `accept.js` | `PATCH /api/offers/:id/accept` | ⚠️ 동일 + 오퍼·상품 소모 |
| `reject.js` | `PATCH /api/offers/:id/reject` | ⚠️ 동일 + 오퍼 소모 |

### purchase (4)
| 파일 | 대상 | 비고 |
|---|---|---|
| `create.js` | `POST /api/purchases` | ⚠️ 상품 소모, `focus` 권장 |
| `detail.js` | `GET /api/purchases/:id` | VU당 상품 1개만 소모 |
| `my-purchases.js` | `GET /api/purchases/me` | |
| `cancel.js` | `DELETE /api/purchases/:id/cancel` | ⚠️ 상품 소모 |

### financial (5)
| 파일 | 대상 | 비고 |
|---|---|---|
| `wallet.js` | `GET /api/deposits/me` | |
| `settlement.js` | `GET /api/settlements/me` | 월별 집계 |
| `settlement-history.js` | `GET /api/settlements/me/history` | 기간 조회, `MONTHS`로 범위 조절 |
| `payment-charge.js` | `POST /api/payments/charge` | 결제 레코드 누적 |
| `payment-detail.js` | `GET /api/payments/:id` | |

### search (1)
| 파일 | 대상 | 비고 |
|---|---|---|
| `products.js` | `GET /api/search/products` | 측정 시점 p95 3.61초 |

## 제외된 엔드포인트

**`POST /api/payments/confirm`** — `TossPaymentApprovalAdapter`를 통해 실제 토스 API를
호출합니다. 부하 테스트에 넣으면 외부 PG사에 트래픽을 보내는 행위가 됩니다.
**어떤 경우에도 스크립트를 만들지 마세요.** (CAUTION.md 1번)

`/internal/*` 엔드포인트는 서비스 간 내부 통신용이라 게이트웨이에 노출되지 않습니다.

## 데이터를 소모하는 스크립트

아래는 실행할수록 자원이 줄어듭니다. 먼저 `TEST_TYPE=focus`로 짧게 확인하세요.

| 스크립트 | 소모 대상 | 현재 보유 |
|---|---|---|
| `purchase/create.js`, `purchase/cancel.js` | IMMEDIATE ON_SALE 상품 | 약 10,000건 |
| `offer/create.js` | (구매자, 상품) 조합 | OFFER 상품 약 15,000건 |
| `auth/withdraw.js`, `member/seller-*.js` | 계정 (매 반복 생성) | 무제한 (정리 필요) |

테스트 후 정리:

```bash
docker exec -i postgres psql -U dev2 -d dear < ~/loadtest-sql/cleanup-runtime.sql
```
