package com.robotics.demo.ledger.model.request;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import com.robotics.demo.ledger.model.Money;

import java.math.BigDecimal;
import java.util.Currency;

public record CreateTransferRequest(String accountFrom, String accountTo,
                                       String amount, String currency) {

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("EUR");

    public Money toMoney() {
        BigDecimal decimalAmount = parseAmount();
        Currency parsedCurrency = parseCurrency();
        try {
            return Money.of(decimalAmount, parsedCurrency);
        } catch (ArithmeticException ex) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
    }

    private BigDecimal parseAmount() {
        if (amount == null || amount.isBlank()) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
        try {
            return new BigDecimal(amount);
        } catch (NumberFormatException ex) {
            throw new LedgerServiceException(ErrorClassification.AMOUNT_PARSE_ERROR);
        }
    }

    private Currency parseCurrency() {
        if (currency == null || currency.isBlank()) {
            return DEFAULT_CURRENCY;
        }
        try {
            return Currency.getInstance(currency.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new LedgerServiceException(ErrorClassification.CURRENCY_MISMATCH);
        }
    }
}
