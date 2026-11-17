package io.groo.damda.user.service;

import io.groo.core.exception.UnauthorizedException;
import io.groo.core.util.MaskUtils;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 재인증 티켓 검증 — SSO 가 서명해 준 "방금 본인 확인이 끝났다" 를 desk 가 확인한다.
 *
 * <p>티켓 발급은 SSO 가 한다(비밀번호·메일 코드 두 경로 모두). desk 는 <b>한 형식만</b>
 * 검증하면 된다 — 그러려고 비밀번호 경로도 SSO 가 내주게 했다(sso-back#60).
 *
 * <p><b>단회 사용은 desk 가 기록한다.</b> 티켓을 실제로 쓰는 쪽이 여기뿐이라, SSO 가
 * "언제 쓰였는지" 를 알 방법이 없다. 남은 만료 시간만큼만 표식을 들고 있으면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReauthTicketVerifier {

    // 서비스 접두사(damda:)는 여기 쓰지 않는다 — 템플릿(RedisConfig)이 모든 키에 붙인다
    private static final String USED_PREFIX = "reauth:used:";
    private static final String TYPE_CLAIM = "type";
    private static final String PURPOSE_CLAIM = "purpose";
    private static final String TYPE_REAUTH = "reauth";

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 티켓이 이 용도로 방금 발급된 것인지 확인하고 <b>즉시 사용 처리</b>한다.
     *
     * @param ticket  {@code X-Reauth-Token} 헤더 값
     * @param purpose 이 조작의 용도(예: {@code withdraw})
     * @return 확인된 사용자 ID(SSO userId)
     * @throws UnauthorizedException 없음·서명 불일치·만료·용도 불일치·재사용
     */
    public String consume(String ticket, String purpose) {
        if (!StringUtils.hasText(ticket)) {
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.validateSsoToken(ticket);
        } catch (Exception e) {
            log.warn("재인증 티켓 검증 실패: {}", e.getMessage());
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }

        if (!TYPE_REAUTH.equals(claims.get(TYPE_CLAIM, String.class))) {
            // 접근 토큰을 재인증 티켓 자리에 넣는 걸 막는다.
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }
        if (!purpose.equals(claims.get(PURPOSE_CLAIM, String.class))) {
            // 해지 확인으로 받은 티켓이 다른 조작에 통과하면 안 된다.
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }

        String jti = claims.getId();
        if (!StringUtils.hasText(jti)) {
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }
        Boolean firstUse = stringRedisTemplate.opsForValue()
                .setIfAbsent(USED_PREFIX + jti, "1", remainingSeconds(claims), TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(firstUse)) {
            log.warn("재인증 티켓 재사용 시도. jti={}", jti);
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }

        return claims.getSubject();
    }

    /**
     * [consume] 과 같되, 티켓의 <b>주인이 기대한 사람인지</b>까지 본다.
     *
     * <p>서명·용도·단회만 보고 넘기면 <b>남의 티켓으로 내 계정을 해지시킬 수 있다.</b>
     * 티켓은 SSO 가 서명하므로 위조는 못 하지만, 자기 계정으로 정상 발급받은 티켓을
     * 남의 세션에 실어 보내는 것은 막을 것이 없었다(2026-09-17 QA 실측).
     *
     * <p>대조는 <b>티켓을 소모한 뒤에</b> 한다 — 먼저 걸러 내면 남의 티켓이 살아남아
     * 계속 시도할 수 있다. 어차피 주인이 아니면 못 쓰는 티켓이니 태워 버리는 편이 낫다.
     *
     * @param expectedUserId 지금 로그인한 사용자의 SSO 식별자
     * @throws UnauthorizedException 티켓이 없거나·용도가 다르거나·재사용이거나
     *                               <b>주인이 다를 때</b>
     */
    public String consumeFor(String ticket, String purpose, String expectedUserId) {
        String subject = consume(ticket, purpose);
        if (!StringUtils.hasText(expectedUserId) || !expectedUserId.equals(subject)) {
            // 아이디는 가려 남긴다 — 이 줄은 남의 티켓이 섞여 들어온 자리라 두 값이
            // 다르다는 것과 앞글자만 있으면 추적이 되고, 원문까지 남길 이유는 없다.
            log.warn("재인증 티켓 주인 불일치. 기대={} 티켓={}",
                    MaskUtils.custom(expectedUserId, 2, 0), MaskUtils.custom(subject, 2, 0));
            throw new UnauthorizedException(DeskErrorCode.REAUTH_REQUIRED);
        }
        return subject;
    }

    /** 표식은 티켓이 만료될 때까지만 들고 있으면 된다 — 그 뒤엔 서명 검증이 알아서 막는다. */
    private long remainingSeconds(Claims claims) {
        long remain = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remain, 1L);
    }
}
