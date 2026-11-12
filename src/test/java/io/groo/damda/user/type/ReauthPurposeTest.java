package io.groo.damda.user.type;

import io.groo.core.exception.InvalidValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReauthPurposeTest {

    @Test
    @DisplayName("비어 있으면 해지 — 용도가 생기기 전 클라이언트(옛 앱 빌드)는 해지에만 재인증을 썼다")
    void blankMeansWithdraw() {
        assertThat(ReauthPurpose.fromRequest(null)).isEqualTo(ReauthPurpose.WITHDRAW);
        assertThat(ReauthPurpose.fromRequest(" ")).isEqualTo(ReauthPurpose.WITHDRAW);
    }

    @Test
    @DisplayName("연동 시작과 해제는 용도가 다르다(보안 점검 S-2 잔여) — 한쪽 티켓으로 다른 쪽을 못 하게")
    void linkAndUnlinkAreDistinct() {
        assertThat(ReauthPurpose.fromRequest("oauth-link")).isEqualTo(ReauthPurpose.OAUTH_LINK);
        assertThat(ReauthPurpose.fromRequest("oauth-unlink")).isEqualTo(ReauthPurpose.OAUTH_UNLINK);
        assertThat(ReauthPurpose.OAUTH_UNLINK.wire()).isEqualTo("oauth-unlink");
        assertThat(ReauthPurpose.OAUTH_UNLINK.wire()).isNotEqualTo(ReauthPurpose.OAUTH_LINK.wire());
    }

    @Test
    @DisplayName("목록에 없는 값은 거절 — 열린 문자열이면 용도 대조가 의미를 잃는다")
    void unknownRejected() {
        assertThatThrownBy(() -> ReauthPurpose.fromRequest("anything"))
                .isInstanceOf(InvalidValueException.class);
    }
}
