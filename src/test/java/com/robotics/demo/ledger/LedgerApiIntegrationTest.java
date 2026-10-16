package com.robotics.demo.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.robotics.demo.ledger.model.Account;
import com.robotics.demo.ledger.model.Money;
import com.robotics.demo.ledger.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("container")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String TEST_CLIENT_ID = "test-client";
    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

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

        mockMvc.perform(authorizedPost("/api/transfers")
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

    private long fundedAccount(String amount) {
        Instant now = Instant.now();
        Money balance = Money.of(new BigDecimal(amount), Currency.getInstance("EUR"));
        return accountService.save(Account.of(null, balance, now, now)).getId();
    }

    private String transferBody(long from, long to, String amount) {
        return "{\"accountFrom\": \"" + from + "\", \"accountTo\": \"" + to + "\", \"amount\": \"" + amount
                + "\", \"currency\": \"EUR\"}";
    }

    @Test
    void transferMovesMoneyAndWritesTwoEntriesLinkedToTheTransfer() throws Exception {
        long from = fundedAccount("100.00");
        long to = createAccount();

        String body = mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(from, to, "25.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.amount").value(25.50))
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(body).get("transferId").asLong();

        mockMvc.perform(authorizedGet("/api/accounts/{id}", from))
                .andExpect(jsonPath("$.balance").value(74.50));
        mockMvc.perform(authorizedGet("/api/accounts/{id}", to))
                .andExpect(jsonPath("$.balance").value(25.50));
        mockMvc.perform(authorizedGet("/api/accounts/{id}/transactions", from))
                .andExpect(jsonPath("$[0].transactionType").value("WITHDRAWAL"))
                .andExpect(jsonPath("$[0].transferId").value(transferId));
        mockMvc.perform(authorizedGet("/api/accounts/{id}/transactions", to))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].transactionType").value("DEPOSIT"))
                .andExpect(jsonPath("$[0].transferId").value(transferId));
        mockMvc.perform(authorizedGet("/api/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    void transferWithInsufficientFundsIsRecordedAsFailedAndMovesNothing() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        String body = mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(from, to, "10.01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("tx-ex-02"))
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(body).get("transferId").asLong();

        mockMvc.perform(authorizedGet("/api/accounts/{id}", from))
                .andExpect(jsonPath("$.balance").value(10.00));
        mockMvc.perform(authorizedGet("/api/accounts/{id}/transactions", to))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(authorizedGet("/api/transfers/{id}", transferId))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void transferWithMissingAmountReturns400() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFrom\": \"" + from + "\", \"accountTo\": \"" + to + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-00"));
    }

    @Test
    void transferWithZeroAmountReturns400() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(from, to, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-00"));
    }

    @Test
    void transferWithUnknownCurrencyReturns400() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFrom\": \"" + from + "\", \"accountTo\": \"" + to
                                + "\", \"amount\": \"1.00\", \"currency\": \"XXY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-01"));
    }

    @Test
    void transferInAnotherCurrencyIsRecordedAsFailed() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFrom\": \"" + from + "\", \"accountTo\": \"" + to
                                + "\", \"amount\": \"1.00\", \"currency\": \"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("tx-ex-01"));
    }

    @Test
    void transferMissingCurrencyDefaultsToEur() throws Exception {
        long from = fundedAccount("10.00");
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFrom\": \"" + from + "\", \"accountTo\": \"" + to
                                + "\", \"amount\": \"1.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void transferToTheSameAccountReturns400() throws Exception {
        long account = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(account, account, "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tr-ex-00"));
    }

    @Test
    void transferWithNegativeAmountReturns400() throws Exception {
        long from = createAccount();
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(from, to, "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tx-ex-00"));
    }

    @Test
    void transferFromUnknownAccountReturns404() throws Exception {
        long to = createAccount();

        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(999999, to, "1.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-02"));
    }

    @Test
    void transferWithNonNumericAccountReturns400() throws Exception {
        mockMvc.perform(authorizedPost("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFrom\": \"x\", \"accountTo\": \"1\", \"amount\": \"1.00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("acc-ex-00"));
    }

    @Test
    void unknownTransferReturns404() throws Exception {
        mockMvc.perform(authorizedGet("/api/transfers/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("tr-ex-01"));
    }

    @Test
    void nonNumericTransferIdReturns400() throws Exception {
        mockMvc.perform(authorizedGet("/api/transfers/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("tr-ex-02"));
    }

    @Test
    void transferWithoutIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/transfers").header(HEADER_CLIENT_ID, TEST_CLIENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("idem-ex-00"));
    }
}
