package io.groo.damda.user.service;

import io.groo.damda.user.controller.dto.OAuthLinkDto.ProviderInfoResp;

import java.util.List;

/**
 * 소셜 계정 연동(link) 프록시 서비스 인터페이스.
 *
 * <p>desk 쿠키로 인증된 사용자의 userId 를 서비스 토큰(client_credentials)으로 SSO 에 relay 한다(BFF).
 */
public interface OAuthLinkService {

    /**
     * 소셜 계정 연동 시작 — SSO 로부터 브라우저가 이동할 연동 시작 URL 을 받아온다.
     *
     * @param userId    desk 쿠키에서 얻은 사용자 ID(SSO sub)
     * @param provider  연동 제공자(예: google) — SSO 로는 소문자로 전달
     * @param returnUrl 연동 완료 후 돌아올 desk-front 경로(선택, null 허용)
     * @param reauthToken 재인증 티켓(purpose=oauth-link) — SSO 가 검증한다. 없으면 SSO 가 거절
     * @return SSO 연동 시작 URL(startUrl)
     */
    String startLink(String userId, String provider, String returnUrl, String reauthToken);

    /** 연동 가능한 제공자 목록과 사용자별 연동 상태 조회. */
    List<ProviderInfoResp> getProviders(String userId);

    /** 소셜 계정 연동 해제. */
    /**
     * 연동 해제 — 재인증 티켓(purpose=oauth-link)을 SSO 에 그대로 넘긴다(보안 점검 S-2 잔여).
     * 연동 시작과 같은 이유로 검증 · 단회 처리는 SSO 가 한다.
     */
    void unlink(String userId, String provider, String reauthToken);
}
