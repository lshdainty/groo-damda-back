package io.groo.damda.common.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보안 점검 IEU-6 ③ — 서명 없는 SSO 이벤트는 기본으로 처리하지 않는다.
 * 설정 파일 기본값과 코드 기본값(설정이 아예 없을 때)이 둘 다 "강제" 여야 한다.
 */
@DisplayName("SSO 이벤트 서명 강제 기본값 (IEU-6 ③)")
class SsoEventRequireSignatureDefaultTest {

    @Test
    @DisplayName("application.yml 기본값은 true(env 로만 끈다)")
    void yamlDefaultIsTrue() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        // 테스트 클래스패스의 설정이 운영 파일을 가릴 수 있어 파일로 직접 읽는다
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));

        assertThat(yaml.getObject().getProperty("app.sso-events.require-signature"))
                .isEqualTo("${SSO_EVENTS_REQUIRE_SIGNATURE:true}");
    }

    @Test
    @DisplayName("설정이 없을 때의 코드 기본값도 true")
    void codeDefaultIsTrue() throws Exception {
        var method = SecurityWebConfig.class.getMethod("ssoEventVerifier",
                io.groo.core.security.jwt.SsoJwksKeyLocator.class,
                org.springframework.data.redis.core.StringRedisTemplate.class, boolean.class);

        assertThat(method.getParameters()[2].getAnnotation(Value.class).value())
                .isEqualTo("${app.sso-events.require-signature:true}");
    }
}
