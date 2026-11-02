package org.altair.petgyebu_backend_renewal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UnhandledFilterErrorJsonTests {

    @Test
    void rethrowsWhenResponseIsAlreadyCommitted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuntimeException failure = new RuntimeException("late failure");

        assertThatThrownBy(() -> new UnhandledFilterErrorJson().doFilter(new MockHttpServletRequest(), response,
                (req, res) -> {
                    res.getWriter().write("partial");
                    res.flushBuffer();
                    throw failure;
                }))
                .isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("partial");
    }
}
