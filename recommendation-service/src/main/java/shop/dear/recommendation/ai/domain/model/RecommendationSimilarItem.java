package shop.dear.recommendation.ai.domain.model;

//조회 결과
public record RecommendationSimilarItem(
	Long productId,
	double distance
) {
}
