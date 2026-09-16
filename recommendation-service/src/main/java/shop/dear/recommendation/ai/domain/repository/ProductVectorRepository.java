package shop.dear.recommendation.ai.domain.repository;

import shop.dear.recommendation.ai.domain.model.Embedding;
import shop.dear.recommendation.ai.domain.model.RecommendationSimilarItem;

import java.util.List;
import java.util.Optional;

public interface ProductVectorRepository {

	void save(Long productId, String story, Embedding embedding);

	Optional<String> findStory(Long productId);

	int deleteByProductId(Long productId);

	List<RecommendationSimilarItem> findSimilar(Long productId, double maxDistance, int limit);
}
