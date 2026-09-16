package shop.dear.financial.payment.infrastructure.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import shop.dear.financial.payment.application.PaymentService;
import shop.dear.financial.wallet.application.event.WalletTopUpSucceededEvent;

@Component
@RequiredArgsConstructor
public class WalletTopUpResultEventListener {

    private final PaymentService paymentService;

    @EventListener
    public void handle(final WalletTopUpSucceededEvent event) {
        paymentService.completePayment(event.paymentId());
    }
}
