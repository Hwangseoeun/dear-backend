-- ===========================================================================
-- 상품 일괄 공개 측정용 Tier 시드 정리 (sql/seed-publish-tier.sql 로 넣은 것)
--
-- 실행:
--   psql -h localhost -U root -d dear_bench -f k6/single/product/publish/sql/cleanup-publish-tier.sql
--
-- 식별 규칙 (seed-publish-tier.sql 이 넣은 접두사)
--   product.model_number LIKE 'PUBTIER-B-%'  배경 상품
--   product.model_number LIKE 'PUBTIER-D-%'  당일 상품
--
-- ⚠️ 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행된다. 그 외에는 즉시 중단한다.
-- ⚠️ 측정 전용 DB 안의 product 테이블을 TRUNCATE(CASCADE) 한다.
--    행이 1천만 건이면 DELETE 보다 TRUNCATE 가 훨씬 빠르다. DB 자체를 지우려면 DROP DATABASE dear_bench;
-- ===========================================================================

SELECT current_database() LIKE '%\_bench' AS is_bench \gset
\if :is_bench
\else
\echo '중단: 측정 전용 DB(이름이 _bench 로 끝나는 DB)에서만 실행할 수 있습니다.'
\quit
\endif

-- ── 삭제 대상 확인 ───────────────────────────────────────────────────
\echo '=== 삭제 대상 ==='
SELECT COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-B-%') AS background,
       COUNT(*) FILTER (WHERE model_number LIKE 'PUBTIER-D-%') AS daily,
       COUNT(*)                                                AS total
FROM product;

TRUNCATE TABLE product RESTART IDENTITY CASCADE;

\echo '=== 삭제 후 ==='
SELECT COUNT(*) AS total FROM product;
