package shop.dear.financial.payment.application.port;

import shop.dear.financial.payment.application.event.WalletDebitRequestedEvent;

public interface WalletDebitEventPublisher {

    void publish(WalletDebitRequestedEvent event);
}
