package shop.dear.financial.settlement.application.port;

import shop.dear.common.event.settlement.SettlementPayoutEvent;

public interface SettlementEventPublisher {

	void publish(final SettlementPayoutEvent event);
}
