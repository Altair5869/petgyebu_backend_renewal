package org.altair.petgyebu_backend_renewal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

// 보안 필터 단계의 처리되지 않은 예외(예: 세션 확인 중 DB 오류)를 ApiExceptionHandler의 500 응답과 같은 본문으로 바꾼다.
// MVC 단계 예외는 ApiExceptionHandler와 DispatcherServlet이 응답으로 바꾸므로 보통 여기까지 올라오지 않는다
// (예외: 그 응답을 쓰다 실패한 경우 — 연결이 끊겼다면 이 응답도 전달되지 않는다).
// 서블릿 필터로 중복 등록되지 않도록 빈으로 만들지 않고 SecurityConfig에서 직접 추가한다.
class UnhandledFilterErrorJson extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UnhandledFilterErrorJson.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            if (response.isCommitted()) {
                throw e;
            }
            log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
            response.reset();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"INTERNAL_ERROR\",\"message\":\"일시적인 오류가 발생했습니다.\"}");
        }
    }
}
