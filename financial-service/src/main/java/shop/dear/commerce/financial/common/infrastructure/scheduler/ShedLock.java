package shop.dear.commerce.financial.common.infrastructure.scheduler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * ShedLock(JdbcTemplateLockProvider)이 스케줄러 분산 락 상태를 기록하는 테이블의 스키마 정의.
 * payment(PaymentOutboxRelay)와 settlement(SpringSettlementScheduler)가 공유해서 쓴다.
 *
 * <p>애플리케이션 코드는 이 엔티티를 직접 조회·저장하지 않는다 - ShedLock이 자체 JDBC 쿼리로
 * 이 테이블을 직접 읽고 쓴다. 여기서는 로컬/개발 환경에서 Hibernate ddl-auto가
 * shedlock 테이블을 생성하도록 스키마만 정의해둔다.</p>
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "shedlock")
@Entity
public class ShedLock {

    @Id
    @Column(name = "name", length = 64, nullable = false, updatable = false)
    private String name;

    @Column(name = "lock_until", nullable = false, columnDefinition = "TIMESTAMP(3) WITH TIME ZONE")
    private OffsetDateTime lockUntil;

    @Column(name = "locked_at", nullable = false, columnDefinition = "TIMESTAMP(3) WITH TIME ZONE")
    private OffsetDateTime lockedAt;

    @Column(name = "locked_by", length = 255, nullable = false)
    private String lockedBy;
}
