package com.robotics.demo.ledger.model.request;

import com.robotics.demo.ledger.exception.classification.ErrorClassification;
import com.robotics.demo.ledger.exception.classification.LedgerServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateTransferRequestTest {

    private static CreateTransferRequest withCurrency(String currency) {
        return new CreateTransferRequest("1", "2", "10", currency);
    }

    @Test
    void aCurrencyCodeIsAcceptedInAnyCase() {
        assertThat(withCurrency("eur").toMoney().currency().getCurrencyCode()).isEqualTo("EUR");
        assertThat(withCurrency("Usd").toMoney().currency().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void aCurrencyCodeIsNotAffectedByTheDefaultLocaleOfTheJvm() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(withCurrency("isk").toMoney().currency().getCurrencyCode()).isEqualTo("ISK");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void aMissingCurrencyMeansTheDefaultOne() {
        assertThat(withCurrency(null).toMoney().currency().getCurrencyCode()).isEqualTo("EUR");
        assertThat(withCurrency(" ").toMoney().currency().getCurrencyCode()).isEqualTo("EUR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXY", "EU", "euro", "XAU", "XXX", "XTS"})
    void anUnknownOrNonMonetaryCurrencyIsRejected(String currency) {
        assertThatThrownBy(() -> withCurrency(currency).toMoney())
                .isInstanceOf(LedgerServiceException.class)
                .extracting("errorClassification")
                .isEqualTo(ErrorClassification.CURRENCY_MISMATCH);
    }
}
