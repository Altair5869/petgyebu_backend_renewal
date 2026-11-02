package org.altair.petgyebu_backend_renewal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.time.Duration;
import javax.sql.DataSource;
import org.altair.petgyebu_backend_renewal.support.TestAuthConfig;
import org.junit.jupiter.api.Test;
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

// 풀이 모두 사용 중이면 application.yaml의 connection-timeout(5초) 뒤 공통 500으로 빨리 실패한다.
// 풀 크기만 1로 줄이고 대기 시간은 덮어쓰지 않는다. 설정이 빠지면 Hikari 기본 30초가 되어 실패한다.
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ConnectionTimeoutApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;

    @Test
    void exhaustedPoolFailsFastWithCommon500() throws Exception {
        String login = mvc.perform(post("/api/v1/auth/login/kakao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"ok:a\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(login, "$.accessToken");

        Connection held = dataSource.getConnection();
        try {
            long start = System.nanoTime();
            // 보안 필터의 세션 확인이 연결을 기다리다 시간 초과된다.
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다."))
                    .andExpect(jsonPath("$.id").doesNotExist())
                    .andExpect(content().string(not(containsString("Connection"))))
                    .andExpect(content().string(not(containsString("HikariPool"))));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(4)).isLessThan(Duration.ofSeconds(15));
        } finally {
            held.close();
        }
    }
}
