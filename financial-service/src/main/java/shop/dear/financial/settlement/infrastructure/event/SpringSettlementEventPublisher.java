package shop.dear.financial.settlement.infrastructure.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import shop.dear.financial.settlement.application.port.SettlementEventPublisher;
import shop.dear.common.event.settlement.SettlementPayoutEvent;

@Component
@RequiredArgsConstructor
public class SpringSettlementEventPublisher implements SettlementEventPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;

	@Override
	public void publish(final SettlementPayoutEvent event) {
		applicationEventPublisher.publishEvent(event);
	}
}
