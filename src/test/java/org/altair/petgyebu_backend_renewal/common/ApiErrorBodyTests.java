package org.altair.petgyebu_backend_renewal.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.altair.petgyebu_backend_renewal.support.MutableClock;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.altair.petgyebu_backend_renewal.support.TestFailingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 프레임워크 단계 오류도 공통 오류 본문 {code, message}를 쓰는지 확인한다.
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ApiErrorBodyTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE expenses, category_goals, monthly_budgets, users RESTART IDENTITY");
        clock.set(TestAuthConfig.START);
    }

    @Test
    void unsupportedContentTypeReturns415CommonBody() throws Exception {
        String a = accessToken("a");

        expectCode(mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.TEXT_PLAIN).content("{\"amount\":1,\"expenseDate\":\"2026-10-13\"}")),
                415, "UNSUPPORTED_MEDIA_TYPE");
        expectCode(mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + a)
                        .content("{\"amount\":1,\"expenseDate\":\"2026-10-13\"}")),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unsupportedMethodReturns405CommonBodyWithAllowHeader() throws Exception {
        String a = accessToken("a");

        expectCode(mvc.perform(put("/api/v1/expenses/1").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("PATCH")))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("DELETE")));
        expectCode(mvc.perform(post("/api/v1/home").header("Authorization", "Bearer " + a)),
                405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
    }

    @Test
    void unknownPathReturns404CommonBodyWhenAuthenticated() throws Exception {
        String a = accessToken("a");

        expectCode(mvc.perform(get("/api/v1/nope").header("Authorization", "Bearer " + a)), 404, "NOT_FOUND");
    }

    @Test
    void unknownPathStillReturns401WhenUnauthenticated() throws Exception {
        expectCode(mvc.perform(get("/api/v1/nope")), 401, "UNAUTHORIZED");
    }

    @Test
    void unhandledExceptionReturns500GenericBodyWithoutDetail() throws Exception {
        String a = accessToken("a");

        expectCode(mvc.perform(get("/api/v1/test/fail").header("Authorization", "Bearer " + a)),
                500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString(TestFailingController.SECRET_MESSAGE))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void otherFrameworkClientErrorsStay4xx() throws Exception {
        String a = accessToken("a");

        mvc.perform(get("/api/v1/test/me").header("Authorization", "Bearer " + a)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void existingErrorCodesUnchanged() throws Exception {
        String a = accessToken("a");

        expectCode(mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expenseDate\":\"2026-10-13\"}")),
                400, "INVALID_REQUEST")
                .andExpect(jsonPath("$.fields[0].field").value("amount"));
        expectCode(mvc.perform(get("/api/v1/expenses/999").header("Authorization", "Bearer " + a)),
                404, "EXPENSE_NOT_FOUND");
        expectCode(mvc.perform(get("/api/v1/home").header("Authorization", "Bearer " + a)), 409, "PET_REQUIRED");
    }

    private ResultActions expectCode(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString());
    }

    private String accessToken(String id) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:" + id + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
