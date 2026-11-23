-- ===========================================================================
-- 상품 일괄 공개 소요 시간 측정용 Tier 시드 (상품 테이블만)
--
-- 목적:
--   오후 8시 일괄 공개(ProductScheduler.publishDailyProducts)가 데이터 누적량별로
--   얼마나 걸리는지 재기 위한 데이터를 만든다.
--   사용처: single/product/publish/publish-duration.js
--           single/product/publish/publish-with-list-traffic.js (목록 조회 트래픽과 함께 측정)
--
-- 만드는 것:
--   배경 상품  PUBTIER-B-{i}  ON_SALE   inserted_at 이 공개 윈도우보다 과거 (이미 공개된 누적분)
--   당일 상품  PUBTIER-D-{i}  PREPARING 공개 대상. (inserted_at 은 측정 API의 /rearm 이 매번 윈도우 안으로 다시 맞춘다)
--   상품 이미지 상품마다 1건
--
-- category / view_count 는 목록 조회(GET /api/products)가 실사용에 가깝게 필터·정렬되도록 분산한다.
--   category   6종(SNEAKERS 등)을 번호 순환으로 균등 분배
--   view_count 배경 상품은 0~999 무작위, 당일 상품은 0 (공개 쿼리는 이 컬럼을 쓰지 않아 공개 시간 측정에는 영향 없음)
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
\echo '[1/4] product 비우기...'
TRUNCATE TABLE product RESTART IDENTITY CASCADE;

-- ── 2. 배경 상품 (이미 공개된 누적분) ────────────────────────────────
-- generate_series(1, N): 1..N 번호 행을 DB 안에서 만들어 주는 함수. 반복문 없이 한 문장으로 대량 INSERT 한다.
-- inserted_at 은 오늘 0시보다 2일 이상 이전으로 분산해 공개 윈도우(어제 20:00~오늘 20:00)에 걸리지 않게 한다.
\echo '[2/4] 배경 상품 생성 (ON_SALE)...'
INSERT INTO product (
    seller_id, name, brand, model_number, category, release_date,
    price, sale_type, status, view_count, description, inserted_at, updated_at
)
SELECT
    1,
    '공개 측정 배경 상품 ' || i,
    '나이키',
    'PUBTIER-B-' || i,
    (ARRAY['SNEAKERS','SPORTS_SHOES','DRESS_SHOES','BOOTS','SANDALS_SLIDES','WINTER_SHOES'])[i % 6 + 1],
    DATE '2024-01-01',
    100000,
    'OFFER',
    'ON_SALE',
    (random() * 1000)::int,
    '공개 소요 시간 측정용 배경 상품',
    date_trunc('day', now()) - (((i % 1095) + 2) || ' days')::interval,
    now()
FROM generate_series(1, :background_count) AS i;

-- ── 3. 당일 상품 (공개 대상) ─────────────────────────────────────────
\echo '[3/4] 당일 상품 생성 (PREPARING)...'
INSERT INTO product (
    seller_id, name, brand, model_number, category, release_date,
    price, sale_type, status, view_count, description, inserted_at, updated_at
)
SELECT
    1,
    '공개 측정 당일 상품 ' || i,
    '나이키',
    'PUBTIER-D-' || i,
    (ARRAY['SNEAKERS','SPORTS_SHOES','DRESS_SHOES','BOOTS','SANDALS_SLIDES','WINTER_SHOES'])[i % 6 + 1],
    DATE '2024-01-01',
    100000,
    'OFFER',
    'PREPARING',
    0,
    '공개 소요 시간 측정용 당일 상품',
    date_trunc('day', now()) - interval '4 hours' + (i * interval '1 second'),
    now()
FROM generate_series(1, :daily_count) AS i;

-- ── 4. 상품 이미지 (상품마다 1건) ────────────────────────────────────
-- 목록 응답(GetProductDto)이 대표 이미지(images.getFirst())를 읽는다. 이미지가 없으면 NoSuchElementException 으로 500.
-- product_image 에는 product_id 인덱스가 없어 목록 요청마다 이 테이블도 함께 스캔된다 (실제 앱과 같은 조건).
\echo '[4/4] 상품 이미지 생성...'
INSERT INTO product_image (product_id, url, sort_order, inserted_at, updated_at)
SELECT id, 'https://example.com/pubtier/' || id || '.jpg', 1, now(), now()
FROM product;

\echo '통계 갱신(ANALYZE)...'
ANALYZE product;
ANALYZE product_image;

\timing off

-- ── 검증: Tier 표와 맞는지 확인 ──────────────────────────────────────
\echo '=== 결과 (Tier 표의 background/daily/누적과 비교) ==='
SELECT COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-B-%') AS background,
       COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-D-%') AS daily,
       COUNT(*)                                                AS total
FROM product;

SELECT status, COUNT(*) AS count FROM product GROUP BY status ORDER BY status;

SELECT (SELECT COUNT(*) FROM product_image) AS images,
       (SELECT COUNT(*) FROM product p WHERE NOT EXISTS (SELECT 1 FROM product_image i WHERE i.product_id = p.id)) AS products_without_image;

\echo '=== 카테고리 분포 (목록 조회용, 6종이 균등해야 함) ==='
SELECT category, COUNT(*) AS count FROM product GROUP BY category ORDER BY category;
