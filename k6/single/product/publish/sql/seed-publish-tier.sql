-- ===========================================================================
-- 상품 일괄 공개 소요 시간 측정용 Tier 시드 (상품 테이블만)
--
-- 목적:
--   오후 8시 일괄 공개(ProductScheduler.publishDailyProducts)가 데이터 누적량별로
--   얼마나 걸리는지 재기 위한 데이터를 만든다. 사용처: single/product/publish/publish-duration.js
--
-- 만드는 것:
--   배경 상품  PUBTIER-B-{i}  ON_SALE   inserted_at 이 공개 윈도우보다 과거 (이미 공개된 누적분)
--   당일 상품  PUBTIER-D-{i}  PREPARING 공개 대상. (inserted_at 은 측정 API의 /rearm 이 매번 윈도우 안으로 다시 맞춘다)
--
-- Tier 표 (누적 = 배경 + 당일)
--   Tier | 전체 가입자 | DAU     | 운영 기간 | 일일 등록 | 누적        | background_count | daily_count
--   -----|-------------|---------|-----------|-----------|-------------|------------------|------------
--   1    | 1,000       | 100     | 6개월     | 10        | 2,000       | 1990             | 10
--   2    | 10,000      | 1,000   | 1년       | 100       | 40,000      | 39900            | 100
--   3    | 100,000     | 10,000  | 2년       | 1,000     | 700,000     | 699000           | 1000
--   4    | 1,000,000   | 100,000 | 3년       | 10,000    | 10,000,000  | 9990000          | 10000
--
-- 실행:
--   psql -h localhost -U root -d dear_bench \
--        -v background_count=39900 -v daily_count=100 \
--        -f k6/single/product/publish/sql/seed-publish-tier.sql
--
-- ⚠️ 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행된다. 그 외에는 즉시 중단한다.
-- ⚠️ 실행 시 product 테이블을 TRUNCATE(CASCADE) 한다. Tier 를 바꿔 다시 시드할 때 깨끗한 상태로 시작하기 위해서다.
-- ⚠️ 정리는 single/product/publish/sql/cleanup-publish-tier.sql 로 한다.
-- ===========================================================================

-- ── 변수 (psql -v 로 덮어쓸 수 있다) ──────────────────────────────────
\if :{?background_count}
\else
\set background_count 39900
\endif

\if :{?daily_count}
\else
\set daily_count 100
\endif

-- ── 안전장치: 측정 전용 DB가 아니면 중단 ────────────────────────────────
SELECT current_database() LIKE '%\_bench' AS is_bench \gset
\if :is_bench
\else
\echo '중단: 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행할 수 있습니다.'
\quit
\endif

\timing on

\echo '=== 시드 설정 ==='
SELECT current_database() AS database,
       :background_count AS background_count,
       :daily_count      AS daily_count;

-- ── 1. 기존 데이터 비우기 ───────────────────────────────────────────
\echo '[1/3] product 비우기...'
TRUNCATE TABLE product RESTART IDENTITY CASCADE;

-- ── 2. 배경 상품 (이미 공개된 누적분) ────────────────────────────────
-- generate_series(1, N): 1..N 번호 행을 DB 안에서 만들어 주는 함수. 반복문 없이 한 문장으로 대량 INSERT 한다.
-- inserted_at 은 오늘 0시보다 2일 이상 이전으로 분산해 공개 윈도우(어제 20:00~오늘 20:00)에 걸리지 않게 한다.
\echo '[2/3] 배경 상품 생성 (ON_SALE)...'
INSERT INTO product (
    seller_id, name, brand, model_number, category, release_date,
    price, sale_type, status, view_count, description, inserted_at, updated_at
)
SELECT
    1,
    '공개 측정 배경 상품 ' || i,
    '나이키',
    'PUBTIER-B-' || i,
    'SNEAKERS',
    DATE '2024-01-01',
    100000,
    'OFFER',
    'ON_SALE',
    0,
    '공개 소요 시간 측정용 배경 상품',
    date_trunc('day', now()) - (((i % 1095) + 2) || ' days')::interval,
    now()
FROM generate_series(1, :background_count) AS i;

-- ── 3. 당일 상품 (공개 대상) ─────────────────────────────────────────
\echo '[3/3] 당일 상품 생성 (PREPARING)...'
INSERT INTO product (
    seller_id, name, brand, model_number, category, release_date,
    price, sale_type, status, view_count, description, inserted_at, updated_at
)
SELECT
    1,
    '공개 측정 당일 상품 ' || i,
    '나이키',
    'PUBTIER-D-' || i,
    'SNEAKERS',
    DATE '2024-01-01',
    100000,
    'OFFER',
    'PREPARING',
    0,
    '공개 소요 시간 측정용 당일 상품',
    date_trunc('day', now()) - interval '4 hours' + (i * interval '1 second'),
    now()
FROM generate_series(1, :daily_count) AS i;

\echo '통계 갱신(ANALYZE)...'
ANALYZE product;

\timing off

-- ── 검증: Tier 표와 맞는지 확인 ──────────────────────────────────────
\echo '=== 결과 (Tier 표의 background/daily/누적과 비교) ==='
SELECT COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-B-%') AS background,
       COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-D-%') AS daily,
       COUNT(*)                                                AS total
FROM product;

SELECT status, COUNT(*) AS count FROM product GROUP BY status ORDER BY status;
