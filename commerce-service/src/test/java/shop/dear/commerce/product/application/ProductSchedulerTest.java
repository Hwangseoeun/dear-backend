package shop.dear.commerce.product.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import shop.dear.commerce.product.application.dto.external.PublishProductInfo;
import shop.dear.commerce.product.domain.constant.ProductCategory;
import shop.dear.commerce.product.domain.constant.ProductSaleType;
import shop.dear.commerce.product.domain.model.Price;
import shop.dear.commerce.product.domain.model.Product;
import shop.dear.commerce.product.domain.repository.ProductRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductSchedulerTest {

    @Autowired
    private ProductScheduler productScheduler;

    @Autowired
    private ProductRepository productRepository;

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

    @DisplayName("등록된 상품의 상태를 판매중으로 변경한다.")
    @Test
    void whenPublishDailyProducts_thenSuccess() {
        //Given
        final Long sellerId = 1L;
        final Product product = createProduct(sellerId);
        final Product savedProduct = productRepository.save(product);

        //When
        final PublishProductInfo info = productScheduler.publishDailyProducts();

        //Then
        final Product updatedProduct = productRepository.findById(savedProduct.getId());

        assertThat(info.count()).isEqualTo(1);
        assertThat(updatedProduct.getStatus().toString()).isEqualTo("ON_SALE");
    }
}