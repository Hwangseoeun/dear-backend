package shop.dear.financial.payment.application.port;

import shop.dear.common.event.financial.PaymentCompletedEvent;

public interface PaymentCompletedEventPublisher {

    void publish(PaymentCompletedEvent event);

}
