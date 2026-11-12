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

결과의 `publish_elapsed_ms`(min/med/max, `phase:cold|warm`)가 공개 작업이 커밋까지 끝나는 데 걸린 시간입니다.
`EXPECTED_DAILY`와 개수가 어긋나면(시드 누락·오염) 종료 코드가 실패(99)로 나옵니다.

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
