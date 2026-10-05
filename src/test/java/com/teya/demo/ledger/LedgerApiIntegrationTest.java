package com.teya.demo.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIntegrationTest {

    private static final String TEST_CLIENT_ID = "test-client";
    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletRequestBuilder authorizedPost(String urlTemplate, Object... uriVariables) {
        return post(urlTemplate, uriVariables)
                .header(HEADER_CLIENT_ID, TEST_CLIENT_ID)
                .header(HEADER_IDEMPOTENCY_KEY, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder authorizedGet(String urlTemplate, Object... uriVariables) {
        return get(urlTemplate, uriVariables).header(HEADER_CLIENT_ID, TEST_CLIENT_ID);
    }

    private MockHttpServletRequestBuilder authorizedDelete(String urlTemplate, Object... uriVariables) {
        return delete(urlTemplate, uriVariables).header(HEADER_CLIENT_ID, TEST_CLIENT_ID);
    }

    private long createAccount() throws Exception {
        String body = mockMvc.perform(authorizedPost("/api/accounts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void createAccountReturnsFreshAccountWithZeroBalance() throws Exception {
        mockMvc.perform(authorizedPost("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getAccountReturns404WhenAccountDoesNotExist() throws Exception {
        mockMvc.perform(authorizedGet("/api/accounts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-02"));
    }

    @Test
    void getAccountReturns400ForNonNumericId() throws Exception {
        mockMvc.perform(authorizedGet("/api/accounts/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-00"));
    }

    @Test
    void depositThenWithdrawUpdatesBalanceAndHistory() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "100.00", "currency": "EUR"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionType").value("DEPOSIT"))
                .andExpect(jsonPath("$.balanceAfter").value(100.00));

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "-30.00", "currency": "EUR"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionType").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balanceAfter").value(70.00));

        mockMvc.perform(authorizedGet("/api/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(70.00));

        mockMvc.perform(authorizedGet("/api/accounts/{id}/transactions", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].transactionType").value("WITHDRAWAL"))
                .andExpect(jsonPath("$[1].transactionType").value("DEPOSIT"));
    }

    @Test
    void transactMissingAmountReturns400() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency": "EUR"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-00"));
    }

    @Test
    void transactZeroAmountReturns400() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "0", "currency": "EUR"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-00"));
    }

    @Test
    void transactUnknownCurrencyReturns400() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "10.00", "currency": "USD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-01"));
    }

    @Test
    void transactMissingCurrencyDefaultsToEur() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "10.00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(10.00));
    }

    @Test
    void transactInsufficientFundsReturns400() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "-1.00", "currency": "EUR"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-02"));
    }

    @Test
    void transactReturns404WhenAccountDoesNotExist() throws Exception {
        mockMvc.perform(authorizedPost("/api/accounts/999999/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": "10.00", "currency": "EUR"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-02"));
    }

    @Test
    void getTransactionsReturns404WhenAccountDoesNotExist() throws Exception {
        mockMvc.perform(authorizedGet("/api/accounts/999999/transactions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-02"));
    }

    @Test
    void getTransactionsFreshAccountReturnsEmptyList() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedGet("/api/accounts/{id}/transactions", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void wrongHttpMethodReturns405WithProblemDetail() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedDelete("/api/accounts/{id}", accountId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void malformedJsonBodyReturns400WithProblemDetail() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(authorizedPost("/api/accounts/{id}/transactions", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").doesNotExist());
    }

    @Test
    void requestWithoutClientIdReturns401() throws Exception {
        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("auth-ex-00"));
    }

    @Test
    void postWithoutIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/accounts").header(HEADER_CLIENT_ID, TEST_CLIENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("idem-ex-00"));
    }

    @Test
    void getWithoutIdempotencyKeyIsAllowed() throws Exception {
        long accountId = createAccount();

        mockMvc.perform(get("/api/accounts/{id}", accountId).header(HEADER_CLIENT_ID, TEST_CLIENT_ID))
                .andExpect(status().isOk());
    }

}
