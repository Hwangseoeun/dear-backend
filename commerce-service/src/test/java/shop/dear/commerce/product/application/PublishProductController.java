package shop.dear.commerce.product.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.dear.commerce.product.application.dto.external.PublishProductInfo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [로컬 측정 전용] 상품 일괄 공개 소요 시간을 k6로 재기 위한 테스트 전용 API.
 *
 * src/test 소스셋에 있어서 bootJar(운영 이미지)에는 포함되지 않고,
 * 로컬에서 `./gradlew :commerce-service:bootRunBench`로 띄울 때만 클래스패스에 올라온다.
 * 그래도 실수로 올라오는 경우를 대비해 local 프로파일 + 측정 전용 DB(`_bench`)일 때만 동작한다.
 *
 * 사용처: k6/single/product/publish/publish-duration.js
 */
@Profile("local")
@RestController
@RequestMapping("/internal/dev/product-publish")
public class PublishProductController {

    // sql/seed-publish-tier.sql 이 당일 상품에 붙이는 model_number 접두사
    private static final String DAILY_PRODUCT_PREFIX = "PUBTIER-D-";

    private final ProductScheduler productScheduler;
    private final TransactionTemplate transactionTemplate;
    private final String datasourceUrl;

    @PersistenceContext
    private EntityManager entityManager;

    public PublishProductController(
        final ProductScheduler productScheduler,
        final PlatformTransactionManager transactionManager,
        @Value("${spring.datasource.url}") final String datasourceUrl
    ) {
        this.productScheduler = productScheduler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.datasourceUrl = datasourceUrl;
    }

    /**
     * 당일 상품을 공개 전 상태로 되돌린다.
     * 공개 작업(/run)이 상태를 ON_SALE로 바꿔 버리므로, 반복 측정하려면 매번 필요하다.
     * 공개 대상 윈도우(어제 20:00 이상 ~ 오늘 20:00 미만)는 ProductScheduler 와 같은 식으로 이 JVM 에서 계산한다.
     */
    @PostMapping("/rearm")
    public ResponseEntity<?> rearm() {
        if (!isBenchDatabase()) {
            return forbidden();
        }

        final LocalDateTime windowStart = LocalDate.now().atTime(20, 0, 0).minusDays(1);

        final Integer rearmed = transactionTemplate.execute(status ->
            entityManager.createNativeQuery(
                    "UPDATE product SET status = 'PREPARING', "
                        + "inserted_at = CAST(:windowStart AS timestamp) + random() * interval '1 day' "
                        + "WHERE model_number LIKE :prefix"
                )
                .setParameter("windowStart", windowStart)
                .setParameter("prefix", DAILY_PRODUCT_PREFIX + "%")
                .executeUpdate()
        );

        return ResponseEntity.ok(new RearmResponse(rearmed == null ? 0 : rearmed));
    }

    /**
     * 크론이 호출하는 것과 같은 ProductScheduler.publishDailyProducts() 를 실행하고 걸린 시간을 잰다.
     * 이 메서드에는 트랜잭션을 걸지 않는다. publishDailyProducts() 자체의 트랜잭션이 커밋까지 끝낸 뒤 반환되므로
     * elapsedMs 에는 커밋 시간이 포함된다.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run() {
        if (!isBenchDatabase()) {
            return forbidden();
        }

        final long start = System.nanoTime();
        final PublishProductInfo info = productScheduler.publishDailyProducts();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        return ResponseEntity.ok(new RunResponse(elapsedMs, info.count(), info.startTime(), info.endTime()));
    }

    private boolean isBenchDatabase() {
        return datasourceUrl.contains("_bench");
    }

    // 전역 예외 핸들러가 ResponseStatusException 을 일반 500 으로 바꿔 버리므로 응답을 직접 만든다.
    private ResponseEntity<ErrorResponse> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(
            "측정 전용 DB(이름에 _bench 포함)에서만 사용할 수 있습니다. 현재: " + datasourceUrl
        ));
    }

    public record ErrorResponse(String message) {
    }

    public record RearmResponse(int rearmed) {
    }

    public record RunResponse(long elapsedMs, int count, LocalDateTime startTime, LocalDateTime endTime) {
    }
}
