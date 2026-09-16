package shop.dear.financial.payment.application.port;

import shop.dear.common.event.financial.PaymentFailedEvent;

public interface PaymentFailedEventPublisher {

    void publish(PaymentFailedEvent event);

}
