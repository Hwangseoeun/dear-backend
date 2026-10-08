package shop.dear.commerce.product.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import shop.dear.commerce.product.application.dto.external.PublishProductInfo;
import shop.dear.commerce.product.domain.constant.ProductCategory;
import shop.dear.commerce.product.domain.constant.ProductSaleType;
import shop.dear.commerce.product.domain.constant.ProductStatus;
import shop.dear.commerce.product.domain.model.Price;
import shop.dear.commerce.product.domain.model.Product;
import shop.dear.commerce.product.domain.repository.ProductRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@SpringBootTest
class ProductSchedulerTest {

    private final LocalDateTime endTime = LocalDate.now().atTime(20, 0, 0);
    private final LocalDateTime startTime = endTime.minusDays(1);

    @Autowired
    private ProductScheduler productScheduler;

    @Autowired
    private ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Product createProduct(final Long memberId) {
        return Product.create(
            memberId,
            "testName",
            "testBrand",
            "testModelNumber-001",
            ProductCategory.SNEAKERS,
            LocalDate.now(),
            Price.from(BigDecimal.valueOf(120000)),
            ProductSaleType.OFFER,
            "Test Description"
        );
    }

    private Long saveProductWithInsertedAt(final LocalDateTime insertedAt) {
        final Product savedProduct = productRepository.save(createProduct(1L));
        entityManager.flush();

        entityManager.createNativeQuery("UPDATE product SET inserted_at = :insertedAt WHERE id = :id")
            .setParameter("insertedAt", insertedAt)
            .setParameter("id", savedProduct.getId())
            .executeUpdate();

        entityManager.clear();

        return savedProduct.getId();
    }

    @DisplayName("등록된 상품의 상태를 판매중으로 변경한다.")
    @Test
    void whenPublishDailyProducts_thenSuccess() {
        //Given
        final Long productId = saveProductWithInsertedAt(endTime.minusHours(1));

        //When
        final PublishProductInfo info = productScheduler.publishDailyProducts();

        //Then
        final Product updatedProduct = productRepository.findById(productId);

        assertThat(info.count()).isEqualTo(1);
        assertThat(updatedProduct.getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @DisplayName("시작 시각(어제 20:00:00)에 등록된 상품은 공개 대상에 포함된다.")
    @Test
    void givenProductInsertedAtWindowStart_whenPublishDailyProducts_thenPublished() {
        //Given
        final Long productId = saveProductWithInsertedAt(startTime);

        //When
        final PublishProductInfo info = productScheduler.publishDailyProducts();

        //Then
        assertThat(info.count()).isEqualTo(1);
        assertThat(productRepository.findById(productId).getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @DisplayName("시작 1분 전에 등록된 상품은 공개 대상에서 제외된다.")
    @Test
    void givenProductInsertedBeforeWindowStart_whenPublishDailyProducts_thenNotPublished() {
        //Given
        final Long productId = saveProductWithInsertedAt(startTime.minusMinutes(1));

        //When
        final PublishProductInfo info = productScheduler.publishDailyProducts();

        //Then
        assertThat(info.count()).isZero();
        assertThat(productRepository.findById(productId).getStatus()).isEqualTo(ProductStatus.PREPARING);
    }

    @DisplayName("종료 시각(오늘 20:00:00)에 등록된 상품은 공개 대상에서 제외된다.")
    @Test
    void givenProductInsertedAtWindowEnd_whenPublishDailyProducts_thenNotPublished() {
        //Given
        final Long productId = saveProductWithInsertedAt(endTime);

        //When
        final PublishProductInfo info = productScheduler.publishDailyProducts();

        //Then
        assertThat(info.count()).isZero();
        assertThat(productRepository.findById(productId).getStatus()).isEqualTo(ProductStatus.PREPARING);
    }

    @DisplayName("공개 작업을 연속으로 두 번 실행해도 두 번째는 아무 상품도 변경하지 않는다.")
    @Test
    void whenPublishDailyProductsTwice_thenSecondRunChangesNothing() {
        //Given
        final Long productId = saveProductWithInsertedAt(endTime.minusHours(1));

        //When
        final PublishProductInfo first = productScheduler.publishDailyProducts();
        final PublishProductInfo second = productScheduler.publishDailyProducts();

        //Then
        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isZero();
        assertThat(productRepository.findById(productId).getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }
}
