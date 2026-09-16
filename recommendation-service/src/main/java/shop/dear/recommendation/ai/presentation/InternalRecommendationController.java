package shop.dear.recommendation.ai.presentation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.dear.common.response.ApiResponse;
import shop.dear.recommendation.ai.infrastructure.inbox.InboxService;
import shop.dear.recommendation.ai.presentation.dto.request.ProductEventRelayRequest;

import java.util.List;

import static shop.dear.common.response.ApiResponse.success;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/recommendations")
public class InternalRecommendationController {

    private final InboxService inboxService;

    @PostMapping("/product-events")
    public ResponseEntity<ApiResponse<Void>> receiveProductEvents(
        @RequestBody final List<@Valid ProductEventRelayRequest> requests
    ) {
        inboxService.saveProductEvents(
            requests.stream()
                .map(ProductEventRelayRequest::toInbox)
                .toList()
        );

        return ResponseEntity.ok(success());
    }
}
