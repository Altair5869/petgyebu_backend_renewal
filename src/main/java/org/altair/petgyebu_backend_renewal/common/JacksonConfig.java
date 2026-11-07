package org.altair.petgyebu_backend_renewal.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
class JacksonConfig {

    // Jackson 기본값은 "5000"·1.5·1e3을 정수로, 1234를 문자열로 받아들인다. 요청 본문에서는 모두 거부한다.
    @Bean
    JsonMapperBuilderCustomizer strictCoercion() {
        return builder -> builder
                .withCoercionConfig(LogicalType.Integer, cfg -> {
                    cfg.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                })
                .withCoercionConfig(LogicalType.Textual, cfg -> {
                    cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    cfg.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                });
    }
}
