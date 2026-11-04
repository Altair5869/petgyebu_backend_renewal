package org.altair.petgyebu_backend_renewal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.jayway.jsonpath.JsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.jackson.nullable.JsonNullable;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class RequestBodyBindingApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;

    record R(JsonNullable<String> m) {}
    record N(Long a) {}
    record S(String s) {}

    @Test
    void credentialOfWrongJsonTypeIsFieldTypeError() throws Exception {
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields[0].field").value("credential"))
                .andExpect(jsonPath("$.fields[0].message").value("형식이 올바르지 않습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"x\"", "{", ""})
    void unreadableBodiesKeepGenericError(String body) throws Exception {
        mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void jsonNullableModuleIsRegistered() throws Exception {
        assertThat(mapper.readValue("{}", R.class).m().isPresent()).isFalse();
        JsonNullable<String> m = mapper.readValue("{\"m\":null}", R.class).m();
        assertThat(m.isPresent()).isTrue();
        assertThat(m.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5000\"", "1.5", "1.0", "1e3", "true", "\"\""})
    void strictCoercionRejectsLooseNumbers(String value) {
        assertThatThrownBy(() -> mapper.readValue("{\"a\":" + value + "}", N.class))
                .isInstanceOf(DatabindException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "true"})
    void strictCoercionRejectsNonStringText(String value) {
        assertThatThrownBy(() -> mapper.readValue("{\"s\":" + value + "}", S.class))
                .isInstanceOf(DatabindException.class);
    }

    @Test
    void stringStaysString() throws Exception {
        assertThat(mapper.readValue("{\"s\":\"1234\"}", S.class).s()).isEqualTo("1234");
    }

    // 범위를 넘는 정수는 DatabindException이 아닌 InputCoercionException이지만 경로(필드)를 가진다.
    @Test
    void outOfRangeIntegerCarriesFieldPath() {
        Throwable t = catchThrowable(() -> mapper.readValue("{\"a\":9223372036854775808}", N.class));
        assertThat(t).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(((tools.jackson.core.JacksonException) t).getPath().get(0).getPropertyName()).isEqualTo("a");
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:hostile\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    @Test
    void excessivelyNestedBodyIs400NotServerError() throws Exception {
        String body = "{\"x\":" + "[".repeat(5000) + "}";
        mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    @Test
    void hugeIntegerLiteralIs400NotServerError() throws Exception {
        String body = "{\"amount\":" + "9".repeat(2000) + ",\"expenseDate\":\"2026-10-13\"}";
        mvc.perform(post("/api/v1/expenses").header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
                .andExpect(jsonPath("$.fields").doesNotExist());
    }
}
