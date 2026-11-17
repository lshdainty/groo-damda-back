package io.groo.damda.security.service;

import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.UnauthorizedException;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.controller.dto.TokenExchangeDto;
import io.groo.damda.expense.service.ExpenseCategoryService;
import io.groo.damda.security.jwt.JwtTokenProvider;
import io.groo.damda.security.principal.JwtClaimsPrincipal;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.security.session.store.SsoLoginLogoutStore;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExchangeService {
    private static final String DESK_SERVICE_CODE = "desk";
    /** SSO access token 이 싣는 로그인 번호 — 로그인 한 번에 값 하나. 비밀번호로 다시 로그인하면 새 값이 나온다. */
    private static final String SSO_SID_CLAIM = "sid";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final UserCalendarService userCalendarService;
    private final ExpenseCategoryService expenseCategoryService;
    private final SsoOAuth2Client ssoOAuth2Client;
    private final SsoSessionService ssoSessionService;
    private final SsoLoginLogoutStore ssoLoginLogoutStore;

    /**
     * Authorization Code 교환 — SSO {@code /oauth2/token} 에 code+code_verifier 를 교환해
     * SSO access_token 을 받은 뒤, 그 토큰을 검증하고 자체 desk JWT 를 발급한다.
     * (BFF: 자체 desk JWT 를 httpOnly 쿠키로 내려준다.)
     *
     * <p>같이 받은 refresh token 은 세션에 보관한다. desk 토큰이 만료됐을 때 사용자를 다시
     * 로그인시키지 않고 조용히 재발급하는 데 쓴다.
     *
     * <p><b>담다에서 로그아웃한 SSO 로그인으로 돌아왔으면 거절한다</b>({@code AUTH_030}, 보안 점검 CLI-6).
     * 로그아웃은 담다 세션만 끝내고 SSO 로그인은 그대로 두므로, SSO 는 비밀번호를 묻지 않고 인가코드를
     * 다시 내준다. 여기서 막지 않으면 로그아웃이 로그인 버튼 한 번으로 되돌려진다. 토큰도 세션도 만들지
     * 않는다 — 화면이 이 코드를 보고 비밀번호를 다시 묻는 로그인으로 보낸다(그러면 새 sid 가 나온다).
     * 이 검사는 <b>새 로그인을 만드는 여기에만</b> 둔다. {@link #reauthenticate}(저장해 둔 SSO refresh 로
     * 세션을 조용히 연장)에는 두지 않는다 — 같은 SSO 로그인으로 만든 다른 담다 세션은 로그아웃하지 않았다.
     *
     * <p><b>새 로그인을 만드는 입구는 여기 하나다.</b> SSO 토큰을 직접 받아 desk 토큰을 내주는 다른 공개
     * 메서드를 만들지 마라 — 이 검사를 건너뛰는 길이 된다.
     */
    @Transactional
    public TokenExchangeDto.Response exchangeCode(String code, String codeVerifier, String redirectUri,
                                                  String userAgent) {
        SsoOAuth2Client.TokenPair pair = ssoOAuth2Client.exchangeCodeForToken(code, codeVerifier, redirectUri);
        Claims ssoClaims = validateSsoToken(pair.accessToken());
        String ssoSid = ssoSidOf(ssoClaims);
        // sid 가 없는 옛 SSO 토큰은 무엇을 로그아웃했는지 가릴 수 없다 — 검사 없이 통과시킨다.
        if (ssoSid != null && ssoLoginLogoutStore.isLoggedOut(ssoSid)) {
            log.info("담다에서 로그아웃한 SSO 로그인으로 돌아온 교환 — 거절한다. userId={}", ssoClaims.getSubject());
            throw new UnauthorizedException(DeskErrorCode.SSO_LOGIN_LOGGED_OUT);
        }
        String sessionId = jwtTokenProvider.newSessionId();
        Exchanged exchanged = doExchange(ssoClaims, sessionId);
        ssoSessionService.create(exchanged.userRowId(), sessionId, pair.refreshToken(), userAgent);
        return exchanged.response();
    }

    /**
     * 만료된 desk 토큰을 조용히 새 것으로 바꾼다 — 필터가 부른다.
     *
     * <p>재발급이 되지 않으면 {@code null}. 그러면 요청은 인증 없이 흘러가 401 이 되고,
     * 클라이언트가 로그인 화면으로 보낸다.
     *
     * <p>만료 직전 갱신도 이 경로를 탄다(보안 점검 DAM-5) — 세션 행·미끄럼 수명·절대 수명(30일)을
     * {@link SsoSessionService#refresh} 가 한 자리에서 본다.
     *
     * <p><b>트랜잭션을 걸지 않는다.</b> 걸면 {@link SsoSessionService#refresh} 의 단계들이 여기 합류해
     * SSO 를 부르는 동안(읽기 타임아웃 10초) 커넥션과 세션 행 잠금을 그대로 쥐게 된다(보안 점검 DAM-5
     * 잔여). desk 토큰 발급 검증은 세션 회전과 같은 짧은 트랜잭션 안에서 돈다 — 검증이 실패하면 회전도
     * 함께 롤백된다(종전 한 트랜잭션일 때와 같은 결말).
     *
     * @param expired 서명이 검증된 토큰의 claims(만료됐거나 만료 직전)
     */
    public String reauthenticate(JwtClaimsPrincipal expired) {
        // SSO 가 끊은 로그인에서 나온 세션이면 재발급하지 않는다(보안 점검 CLI-6). 아래 refresh 도 SSO 가 거절해
        // 결국 실패하지만, 앞선 요청이 방금 갱신해 둔 유예 구간에서는 SSO 를 부르지 않고 같은 sso_sid 로 다시 서명한다 —
        // 그 틈을 여기서 막는다. 이 세션은 더 쓸 수 없으므로 행도 지워 "로그인된 기기" 목록에서 사라지게 한다.
        if (expired.hasSsoSid() && ssoSessionService.isSsoLoginRevoked(expired.ssoSid())) {
            ssoSessionService.revoke(expired.sessionId());
            return null;
        }
        AtomicReference<String> exchanged = new AtomicReference<>();
        SsoSessionService.RefreshResult result = ssoSessionService.refresh(expired.sessionId(),
            // SSO 를 실제로 불렀다 — 로그인과 같은 경로로 태운다. desk 접근 권한·사용자 정보가
            // 매 재발급마다 다시 확인된다(권한을 뺏겼으면 여기서 걸린다). 새 SSO access token 에도
            // 같은 sid 가 실려 있어, 다시 발급되는 desk 토큰의 sso_sid 가 그대로 이어진다.
            ssoAccessToken -> exchanged.set(
                doExchange(validateSsoToken(ssoAccessToken), expired.sessionId()).response().accessToken()));
        if (!result.renewed()) {
            return null;
        }

        if (result.ssoAccessToken() != null) {
            return exchanged.get();
        }

        // 앞선 요청이 방금 갱신해 뒀다. 신원은 서명이 검증된 만료 토큰에서 그대로 가져온다.
        return jwtTokenProvider.createAccessToken(
            expired.userId(), expired.userName(), expired.userEmail(), expired.userRowId(), expired.sessionId(),
            expired.ssoSid()
        );
    }

    /**
     * 응답과 함께 userRowId 를 들고 나온다 — 세션을 만들 때 필요한데, API 응답 형태에
     * 내부 식별자를 끼워 넣고 싶지 않아 안쪽에서만 쓴다.
     */
    private record Exchanged(TokenExchangeDto.Response response, Long userRowId) {}

    /** SSO access token 의 서명을 검증하고 claims 를 꺼낸다. 통과하지 못하면 {@code AUTH_EXCHANGE_FAILED}. */
    private Claims validateSsoToken(String ssoToken) {
        try {
            return jwtTokenProvider.validateSsoToken(ssoToken);
        } catch (Exception e) {
            log.error("SSO token validation failed: {}", e.getMessage());
            throw new UnauthorizedException(DeskErrorCode.AUTH_EXCHANGE_FAILED);
        }
    }

    /** SSO 로그인 번호. SSO 가 sid 를 싣기 전에 발급한 토큰에는 없다({@code null}). */
    private static String ssoSidOf(Claims ssoClaims) {
        String sid = ssoClaims.get(SSO_SID_CLAIM, String.class);
        return sid == null || sid.isBlank() ? null : sid;
    }

    private Exchanged doExchange(Claims ssoClaims, String sessionId) {
        String userId = ssoClaims.getSubject();

        // services claim에서 desk 서비스 접근 권한 확인
        List<String> services = ssoClaims.get("services", List.class);
        if (services == null || !services.contains(DESK_SERVICE_CODE)) {
            // 해지하면 SSO 쪽 desk 접근이 꺼져 services 에서 빠진다 — 그래서 해지자는
            // 여기에 먼저 걸려 "권한 없음"(AUTH_003)으로 나갔고, 화면은 그걸 보통의 로그인
            // 실패로 읽어 안내 화면(/withdrawn)에 닿지 못했다(2026-09-17 QA).
            // 해지자인지는 여기서만 묻는다 — 정상 로그인에는 질의가 늘지 않는다.
            if (isWithdrawn(userId)) {
                log.info("해지한 계정의 로그인 시도. userId={}", userId);
                throw new ForbiddenException(DeskErrorCode.USER_WITHDRAWN);
            }
            log.warn("User does not have access to Desk service");
            throw new ForbiddenException(DeskErrorCode.AUTH_ACCESS_DENIED);
        }
        String userName = ssoClaims.get("name", String.class);
        String userEmail = ssoClaims.get("email", String.class);
        Long ssoUserNo = ssoClaims.get("userNo", Long.class);
        // 가입 지역 — SSO 가 회원가입 때 받아 claim 으로 내려준다.
        // 구버전 토큰에는 없으므로 null 이면 User 기본값(Asia/Seoul)이 쓰인다.
        String timezone = ssoClaims.get("timezone", String.class);

        User user = userRepository.findByUserId(userId).orElse(null);
        if (user == null) {
            // 해지한 계정인지 먼저 본다. 위 조회는 is_deleted='N' 만 보므로 해지자는 "없는
            // 사람" 으로 나오고, 그대로 두면 아래에서 새 행을 만들려다 UK 위반으로 500 이 난다.
            // 사용자에게는 "왜 안 되는지" 를 알려 줘야 한다.
            // SSO 접근 해제가 실패해 services 에 desk 가 남아 있는 경우도 여기서 막는다.
            if (isWithdrawn(userId)) {
                log.info("해지한 계정의 로그인 시도. userId={}", userId);
                throw new ForbiddenException(DeskErrorCode.USER_WITHDRAWN);
            }
            user = userRepository.save(User.createUser(ssoUserNo, userId, userName, userEmail, timezone));
            // 신규 사용자: 기본 캘린더를 가입(최초 프로비저닝) 시점에 즉시 생성한다.
            // 지연 생성(getOrCreateDefault on first event)에 의존하면 신규 사용자에게
            // 동시 요청이 겹칠 때 기본 캘린더가 중복 생성될 수 있어, 단일 트랜잭션인
            // 이 시점에서 한 번 만들어 경쟁 자체를 없앤다.
            userCalendarService.getOrCreateDefault(user.getRowId());
            // 기본 카테고리도 같은 이유로 이 시점에 심는다 — 카테고리가 하나도
            // 없으면 거래 시트의 저장이 조용히 비활성이라 첫 기록 자체가 막힌다.
            expenseCategoryService.seedDefaults(user.getRowId());
        }

        // Update user info if changed
        user.updateFromSso(ssoUserNo, userName, userEmail);

        // SSO 로그인 번호를 desk 토큰에 옮겨 싣는다 — 로그아웃이 어느 SSO 로그인을 기억할지 여기서 정해진다.
        String accessToken = jwtTokenProvider.createAccessToken(
            user.getUserId(), user.getUserName(), user.getUserEmail(), user.getRowId(), sessionId,
            ssoSidOf(ssoClaims)
        );

        return new Exchanged(
            new TokenExchangeDto.Response(accessToken, user.getUserId(), user.getUserName(), user.getUserEmail()),
            user.getRowId()
        );
    }

    /**
     * desk 이용을 해지한 계정인가.
     *
     * <p>{@code findByUserId} 는 {@code is_deleted='N'} 만 보므로 해지자는 "없는 사람" 으로
     * 나온다. 해지 여부는 이 조회로만 알 수 있다 — 정상 로그인 경로에서는 부르지 않는다.
     */
    private boolean isWithdrawn(String userId) {
        return userRepository.findByUserIdIncludingWithdrawn(userId)
                .filter(User::isWithdrawn)
                .isPresent();
    }

}
