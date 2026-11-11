package io.groo.damda.security.session.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.security.session.controller.dto.SessionApiDto;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.session.service.SsoSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "로그인된 기기" — 본인 세션 조회·해지.
 *
 * <p>SSO 를 거치지 않는다. desk 는 로그인할 때마다 자기 세션 테이블(user_sso_session)에
 * 한 행을 남기므로 그것만으로 목록이 나온다.
 */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@RequiredArgsConstructor
public class SessionApiController {

    private final SsoSessionService ssoSessionService;
    private final SsoOAuth2Client ssoOAuth2Client;

    /** 살아 있는 기기 목록. 최근 사용 순. */
    @GetMapping
    public ApiResponse<List<SessionApiDto.DeviceRes>> list(@LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(
                ssoSessionService.listDevices(loginUser.getRowId(), loginUser.getSessionId()));
    }

    /**
     * 기기 하나 로그아웃.
     *
     * <p>없거나 남의 세션이어도 성공으로 응답한다. 구분해 주면 "그 id 는 존재한다" 는 사실이
     * 새어 나간다. 사용자 입장에서도 결과는 같다 — 그 기기는 더 이상 안 붙는다.
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> revoke(@LoginUser UserPrincipal loginUser,
                                    @PathVariable String sessionId) {
        ssoSessionService.revokeOwned(loginUser.getRowId(), sessionId);
        return ApiResponse.success();
    }

    /**
     * 모든 기기에서 로그아웃 — 지금 이 기기도 포함해, <b>전 기기·전 서비스</b>를 끊는다.
     *
     * <p>desk 세션을 먼저 끊고, SSO 에 전체 폐기를 요청한다. SSO 가 자기 토큰을 전부 끊으면서
     * 세션 폐기 이벤트를 내보내 hr 까지 전파된다.
     *
     * <p>순서가 이렇다. SSO 요청이 실패하면 예외가 올라가 사용자에게 알려지는데, 그때
     * <b>desk 는 이미 끊긴 상태</b>여야 한다 — 반대로 두면 SSO 만 끊기고 desk 가 남아
     * "로그아웃했는데 이 서비스만 열려 있는" 상태가 된다.
     *
     * <p>SSO 이벤트를 받아 처리하는 쪽({@code SsoSessionEventSubscriber})은 여기를 타지 않고
     * {@code revokeAll} 만 부른다. 거기서 SSO 를 다시 부르면 이벤트가 무한히 돈다.
     */
    @DeleteMapping
    public ApiResponse<Void> revokeAll(@LoginUser UserPrincipal loginUser) {
        ssoSessionService.revokeAll(loginUser.getRowId());
        ssoOAuth2Client.revokeAllSessions(loginUser.getUserId());
        return ApiResponse.success();
    }
}
