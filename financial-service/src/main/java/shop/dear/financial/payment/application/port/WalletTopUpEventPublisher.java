package shop.dear.financial.payment.application.port;

import shop.dear.financial.payment.application.event.WalletTopUpRequestedEvent;

public interface WalletTopUpEventPublisher {

    void publish(WalletTopUpRequestedEvent event);

}
