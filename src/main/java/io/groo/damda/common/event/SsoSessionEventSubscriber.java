package io.groo.damda.common.event;

import io.groo.core.security.event.SsoEventSignature;
import io.groo.core.security.event.SsoEventVerifier;
import java.util.Optional;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSO 세션 폐기 이벤트 구독자 — {@code porest:sso:session-events}.
 *
 * <p>SSO 가 토큰을 끊어도 desk 는 자기 {@code user_sso_session} 을 그대로 들고 있었다.
 * 그래서 관리자가 강제 로그아웃을 걸어도 desk 는 무음 재인증으로 계속 열려 있었다 —
 * 끊었다고 생각한 쪽과 실제가 달랐다. 이 구독자가 그 간극을 메운다.
 *
 * <p><b>무엇을 하든 예외를 밖으로 내지 않는다.</b> Redis 리스너에서 예외가 올라가면 그
 * 메시지만 유실되는 게 아니라 컨테이너가 시끄러워지고, 어차피 재시도할 방법도 없다
 * (pub/sub 은 저장하지 않는다). 놓친 세션은 다음 재발급 시도에서 SSO 가 거부하며 끊긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoSessionEventSubscriber {

    private final UserRepository userRepository;
    private final SsoSessionService ssoSessionService;
    private final SsoEventVerifier ssoEventVerifier;

    /**
     * 발행 쪽이 필드를 더해도 계속 읽히게 모르는 필드는 무시한다. 두 레포 사이에 공유
     * 타입이 없어 이쪽이 늘 한 발 늦게 따라오기 때문이다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Redis {@code MessageListenerAdapter} 가 부른다. */
    @Transactional
    public void handleSessionEvent(String message) {
        // 서명 확인(보안 점검 IEU-6) — 통과하면 서명 안의 원문을 쓴다. 거절 이유는 검증기가 warn 으로 남긴다
        Optional<String> verified = ssoEventVerifier.verify(SsoEventSignature.SESSION_EVENTS_CHANNEL, message);
        if (verified.isEmpty()) {
            return;
        }
        message = verified.get();
        SessionEvent event;
        try {
            event = objectMapper.readValue(message, SessionEvent.class);
        } catch (JacksonException e) {
            log.error("세션 이벤트를 읽지 못했다: {}", message, e);
            return;
        }

        if (event.type() == null) {
            // 발행 쪽이 새 타입을 냈는데 이쪽이 아직 모른다. 이건 정상적인 시차다.
            log.debug("모르는 세션 이벤트 타입 — 무시한다: {}", message);
            return;
        }

        try {
            switch (event.type()) {
                case SESSION_REVOKED_ALL -> handleRevokedAll(event);
            }
        } catch (Exception e) {
            log.error("세션 이벤트 처리 실패: {}", message, e);
        }
    }

    private void handleRevokedAll(SessionEvent event) {
        if (event.ssoUserRowId() == null) {
            log.warn("세션 폐기 이벤트에 사용자가 없다 — 무시한다. reason={}", event.reason());
            return;
        }

        User user = userRepository.findBySsoUserRowId(event.ssoUserRowId()).orElse(null);
        if (user == null) {
            // desk 를 한 번도 안 쓴 SSO 사용자다. 끊을 세션도 없으니 정상이다.
            log.debug("desk 에 없는 사용자의 세션 폐기 — 넘어간다. ssoUserRowId={}", event.ssoUserRowId());
            return;
        }

        ssoSessionService.revokeAll(user.getRowId());
        log.info("SSO 폐기 전파 — desk 세션을 끊었다. ssoUserRowId={}, userRowId={}, reason={}",
                event.ssoUserRowId(), user.getRowId(), event.reason());
    }
}
