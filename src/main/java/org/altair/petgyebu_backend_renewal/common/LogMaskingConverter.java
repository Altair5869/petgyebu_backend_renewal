package org.altair.petgyebu_backend_renewal.common;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import java.util.regex.Pattern;

// logback-spring.xml의 %mask(...). 로그 메시지와 예외 출력에서 형태로 알아볼 수 있는 비밀값을 가린다.
// 소셜 식별자·세션 UUID처럼 형태로 구분되지 않는 값은 여기서 잡을 수 없으므로 toString에서 막는다.
public class LogMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    // TokenIssuer.newRefreshToken 형식: {userId}.{32바이트 base64url 43자}
    private static final Pattern REFRESH_TOKEN = Pattern.compile("\\b\\d+\\.[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])");
    // PostgreSQL 무결성 오류 상세: Key (컬럼)=(값) already exists / is not present / is still referenced
    private static final Pattern PG_KEY = Pattern.compile(
            "(Key \\([^)]*\\)=\\().*?(\\) (?:already exists|is not present|is still referenced))");
    private static final Pattern PG_FAILING_ROW = Pattern.compile("(Failing row contains ).*");

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return mask(in);
    }

    static String mask(String text) {
        String masked = JWT.matcher(text).replaceAll("***");
        masked = BEARER.matcher(masked).replaceAll("$1***");
        masked = REFRESH_TOKEN.matcher(masked).replaceAll("***");
        masked = PG_KEY.matcher(masked).replaceAll("$1***$2");
        return PG_FAILING_ROW.matcher(masked).replaceAll("$1***");
    }
}
