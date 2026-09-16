package shop.dear.financial.settlement.presentation.dto.response;

import java.math.BigDecimal;

public record NetAmountResponse(
	BigDecimal netAmount
) {
	public static NetAmountResponse from(BigDecimal netAmount){
		return new NetAmountResponse(netAmount);
	}
}
