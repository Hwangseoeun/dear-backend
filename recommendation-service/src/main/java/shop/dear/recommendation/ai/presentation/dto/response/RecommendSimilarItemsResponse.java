package shop.dear.recommendation.ai.presentation.dto.response;

import shop.dear.recommendation.ai.domain.model.RecommendationSimilarItem;

import java.util.List;
import java.util.stream.IntStream;

public record RecommendSimilarItemsResponse(
	Long productId,
	Integer rank
) {

	public static List<RecommendSimilarItemsResponse> listOf(final List<RecommendationSimilarItem> items) {
		return IntStream.range(0, items.size())
			.mapToObj(index -> new RecommendSimilarItemsResponse(items.get(index).productId(), index + 1))
			.toList();
	}
}
