package io.groo.damda.common.event;

import java.time.LocalDateTime;

/**
 * SSO 가 내리는 세션 폐기 이벤트 — {@code porest:sso:session-events} 채널.
 *
 * <p><b>발행 쪽(porest-sso-back)의 {@code SessionEvent} 와 형태를 맞춰야 한다.</b> 두 레포
 * 사이에 공유 타입도 코드젠도 없어 컴파일러가 어긋남을 잡아 주지 않는다 — 필드를 바꾸면
 * 조용히 역직렬화만 실패하고 로그아웃이 전파되지 않는다.
 *
 * <p>모르는 필드는 무시한다({@code @JsonIgnoreProperties}) — 발행 쪽이 필드를 <b>더하는</b>
 * 변경은 이쪽을 안 고쳐도 계속 돌아야 한다.
 *
 * @param type         이벤트 종류. 모르는 값이면 {@code null} 로 들어와 무시된다
 * @param ssoUserRowId SSO 의 {@code users.row_id}. desk 는 {@code users.sso_user_row_id} 로 대조한다.
 *                     로그인 한 건 폐기에서는 로그에만 쓰이고 비어 있을 수 있다
 * @param sessionId    끊긴 SSO 로그인 번호(SSO access 토큰의 {@code sid}) — 로그인 한 건 폐기에만 실린다.
 *                     담다는 자기 토큰에 실어 둔 {@code sso_sid} 와 대조한다
 * @param reason       왜 끊었는지 — 로그에만 쓴다
 * @param timestamp    [UTC] 발행 시각
 */
public record SessionEvent(
        SessionEventType type,
        Long ssoUserRowId,
        String sessionId,
        String reason,
        LocalDateTime timestamp
) {}
