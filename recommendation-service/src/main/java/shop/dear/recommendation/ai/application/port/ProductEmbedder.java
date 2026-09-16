package shop.dear.recommendation.ai.application.port;

import shop.dear.recommendation.ai.domain.model.Embedding;

public interface ProductEmbedder {

	Embedding embed(String story);
}
