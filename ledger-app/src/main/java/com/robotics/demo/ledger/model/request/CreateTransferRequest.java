package com.robotics.demo.ledger.model.request;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.domain.Money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

public record CreateTransferRequest(String accountFrom, String accountTo,
                                    String amount, String currency) {

    public Money toMoney() {
        BigDecimal amount = getAmount();
        Currency currency = getCurrency();
        try {
            return Money.of(amount, currency);
        } catch (ArithmeticException ex) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
    }

    private BigDecimal getAmount() {
        if (amount == null || amount.isBlank()) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        try {
            return new BigDecimal(amount);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
    }

    private Currency getCurrency() {
        if (currency == null || currency.isBlank()) {
            return Money.DEFAULT_CURRENCY;
        }
        try {
            Currency parsed = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
            if (parsed.getDefaultFractionDigits() < 0) {
                throw new LedgerServiceException(ErrorClassification.CURRENCY_MISMATCH);
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new LedgerServiceException(ErrorClassification.CURRENCY_MISMATCH);
        }
    }
}
