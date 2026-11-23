-- ===========================================================================
-- 당일 상품을 공개 전(PREPARING)으로 되돌린다 (DB 직접 실행 버전)
--
-- 목적:
--   측정 전용 API(PublishProductController)의 POST /rearm 과 같은 작업을 api를 거치지 않고 DB에서 직접 한다.
--   목록 조회 트래픽이 DB 커넥션 풀(기본 10개)을 모두 점유한 상태에서는 /rearm 도 커넥션을 못 얻어 실패하므로,
--   포화 상태에서 공개를 여러 번 측정해야 할 때 반복 사이의 rearm 을 이 SQL 로 대신한다.
--   사용처: single/product/publish/run-with-external-rearm.sh
--
-- 하는 일:
--   당일 상품(PUBTIER-D-*)의 상태를 PREPARING 으로, inserted_at 을 공개 윈도우(어제 20:00 ~ 오늘 20:00) 안의
--   임의 시각으로 되돌린다. (API 의 /rearm 과 같은 계산: window_start + random() * 1일)
--
-- 변수 (psql -v 로 전달, 둘 다 필수)
--   background_count  시드의 background_count. 시드가 배경 상품을 먼저 넣고(id 1..N) 당일 상품을 뒤에 넣으므로
--                     id > background_count 로 PK 범위 조건을 걸어 1천만 행에서도 전체 스캔 없이 빠르다.
--   window_start      공개 윈도우 시작 = 어제 20:00 (앱 JVM 기본 시간대 기준). 예: '2026-11-22 20:00:00'
--                     macOS: date -v-1d '+%Y-%m-%d 20:00:00'   Linux: date -d yesterday '+%Y-%m-%d 20:00:00'
--
-- 실행:
--   psql -h localhost -U root -d dear_bench \
--        -v background_count=39900 -v window_start="$(date -v-1d '+%Y-%m-%d 20:00:00')" \
--        -f k6/single/product/publish/sql/rearm-publish-tier.sql
--
-- ⚠️ 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행된다. 그 외에는 즉시 중단한다.
-- ===========================================================================

\if :{?background_count}
\else
\echo '중단: -v background_count=<시드의 background_count> 가 필요합니다.'
\quit
\endif

\if :{?window_start}
\else
\echo '중단: -v window_start=<어제 20:00:00> 이 필요합니다.'
\quit
\endif

SELECT current_database() LIKE '%\_bench' AS is_bench \gset
\if :is_bench
\else
\echo '중단: 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행할 수 있습니다.'
\quit
\endif

WITH u AS (
    UPDATE product
       SET status = 'PREPARING',
           inserted_at = CAST(:'window_start' AS timestamp) + random() * interval '1 day'
     WHERE id > :background_count
       AND model_number LIKE 'PUBTIER-D-%'
    RETURNING 1
)
SELECT COUNT(*) AS rearmed FROM u;
