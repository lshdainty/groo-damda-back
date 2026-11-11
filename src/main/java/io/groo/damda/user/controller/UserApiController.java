package io.groo.damda.user.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import io.groo.damda.user.controller.dto.UserApiDto;
import io.groo.damda.user.service.ReauthProxyService;
import io.groo.damda.user.service.ReauthTicketVerifier;
import io.groo.damda.user.service.UserService;
import io.groo.damda.user.service.WithdrawalService;
import io.groo.damda.user.service.dto.WithdrawalServiceDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserApiController {

    private final UserService userService;
    private final WithdrawalService withdrawalService;
    private final ReauthProxyService reauthProxyService;
    private final ReauthTicketVerifier reauthTicketVerifier;

    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.ChangePasswordReq request) {
        userService.changePassword(
                loginUser.getUserId(),
                request.getCurrentPassword(),
                request.getNewPassword(),
                request.getConfirmPassword()
        );
        return ApiResponse.success(null);
    }

    @PostMapping("/me/verify-password")
    public ApiResponse<Void> verifyPassword(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.VerifyPasswordReq request) {
        userService.verifyPassword(loginUser.getUserId(), request.getPassword());
        return ApiResponse.success(null);
    }

    /**
     * 해지해도 되는지 · 해지하면 무엇이 사라지는지. 화면이 확인창에 그대로 쓴다.
     *
     * <p>막는 사유가 있으면 {@code blocked} 에 담기고, 구독이 막을 때는 언제부터 가능한지
     * ({@code subscriptionPeriodEnd})를 같이 준다 — 그게 없으면 사용자가 기다릴지 말지 모른다.
     */
    @GetMapping("/me/withdrawal-check")
    public ApiResponse<WithdrawalServiceDto.CheckResult> withdrawalCheck(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(withdrawalService.check(loginUser.getRowId()));
    }

    /**
     * 본인 메일로 재인증 코드를 보낸다.
     *
     * <p>비밀번호가 없는 소셜 전용 계정을 위한 경로다. 브라우저·앱에는 SSO 액세스 토큰이
     * 없어 SSO 를 직접 못 부르므로 여기서 서비스 토큰으로 대신 부른다.
     */
    @PostMapping("/me/reauth/email-code")
    public ApiResponse<Void> sendReauthEmailCode(@LoginUser UserPrincipal loginUser) {
        reauthProxyService.sendEmailCode(loginUser.getUserId());
        return ApiResponse.success(null);
    }

    /** 코드 확인 → 재인증 티켓. 이 티켓을 {@code X-Reauth-Token} 으로 들고 해지를 부른다. */
    @PostMapping("/me/reauth/email-code/verify")
    public ApiResponse<UserApiDto.ReauthTicketResp> verifyReauthEmailCode(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.ReauthEmailCodeReq request) {
        return ApiResponse.success(new UserApiDto.ReauthTicketResp(
                reauthProxyService.verifyEmailCode(loginUser.getUserId(), request.getCode())));
    }

    /** 비밀번호 확인 → 재인증 티켓. */
    @PostMapping("/me/reauth/password")
    public ApiResponse<UserApiDto.ReauthTicketResp> verifyReauthPassword(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.ReauthPasswordReq request) {
        return ApiResponse.success(new UserApiDto.ReauthTicketResp(
                reauthProxyService.verifyPassword(loginUser.getUserId(), request.getPassword())));
    }

    /**
     * desk 이용 해지. <b>되돌릴 수 없다.</b>
     *
     * <p>{@code X-Reauth-Token} 이 있어야 한다 — 방금 본인 확인이 끝났다는 SSO 서명 티켓이고,
     * 여기서 한 번만 쓰인다. 두 번 불러도 같은 결과다(멱등).
     */
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            @LoginUser UserPrincipal loginUser,
            @RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken,
            // `@Valid` 가 없으면 `@Size(max = 200)` 이 아예 안 돈다 — 201자 사유가
            // 그대로 내려가 컬럼 길이에서 500 이 났다(2026-09-17 QA). 본문이 없을
            // 때는 검증도 건너뛴다.
            @Valid @RequestBody(required = false) UserApiDto.WithdrawReq request) {
        // 티켓의 주인이 **지금 로그인한 사람과 같은지** 본다. 서명·용도·단회만 보고
        // 넘기면 남의 티켓으로 내 계정을 해지시킬 수 있다 — 검증기가 주인을 돌려주는데
        // 그 값을 버리고 있었다(2026-09-17 QA: r13 티켓 + r12 세션이 통과).
        reauthTicketVerifier.consumeFor(reauthToken, "withdraw", loginUser.getUserId());
        withdrawalService.withdraw(loginUser.getRowId(),
                request != null ? request.getReason() : null);
        return ApiResponse.success(null);
    }

    @GetMapping("/me/preferences")
    public ApiResponse<UserApiDto.PreferencesResponse> getPreferences(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(userService.getPreferences(loginUser.getRowId()));
    }

    @PatchMapping("/me/preferences")
    public ApiResponse<UserApiDto.PreferencesResponse> updatePreferences(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.UpdatePreferencesReq request) {
        return ApiResponse.success(userService.updatePreferences(loginUser.getRowId(), request));
    }

    /**
     * 금액 가리기 목록 — 기기가 아니라 <b>계정</b>에 붙는다.
     *
     * <p>예전에는 웹 localStorage · 앱 SharedPreferences 에 따로 저장해서, 폰에서 가려도
     * 웹으로 로그인하면 금액이 그대로 보였다.
     *
     * <p>알림 설정({@code /me/preferences})과 엔드포인트를 나눈 이유는 <b>호출 시점</b>이
     * 다르기 때문이다 — 이건 화면에 금액을 그리기 전에 필요해 앱을 열 때마다 부르고,
     * preferences 는 설정 화면에서만 부른다.
     */
    @GetMapping("/me/hide-cards")
    public ApiResponse<UserApiDto.HideCardsResponse> getHideCards(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(userService.getHideCards(loginUser.getRowId()));
    }

    /**
     * 금액 가리기 목록 교체 — 부분 갱신이 아니라 통째로 바꾼다.
     *
     * <p>가리기를 <b>푸는</b> 요청도 여기로 온다. 푸는 경로는 호출 전에 비밀번호 확인을
     * 거치는데({@code /me/verify-password}) 그건 화면 책임이다 — 서버는 어떤 카드가
     * 빠졌는지 알 수 없어(카드 어휘를 안 갖고 있다) 여기서 판별할 수 없다.
     */
    @PutMapping("/me/hide-cards")
    public ApiResponse<UserApiDto.HideCardsResponse> updateHideCards(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody UserApiDto.UpdateHideCardsReq request) {
        return ApiResponse.success(userService.updateHideCards(loginUser.getRowId(), request));
    }
}
