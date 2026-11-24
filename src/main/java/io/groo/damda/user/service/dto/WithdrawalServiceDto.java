package io.groo.damda.user.service.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class WithdrawalServiceDto {

    /**
     * 해지 전 점검 결과.
     *
     * @param blocked                막는 사유 — 비어 있어야 해지할 수 있다
     * @param subscriptionPeriodEnd  구독이 막고 있을 때 <b>언제부터 가능한지</b>. 화면이 이 날짜를
     *                               보여 줘야 사용자가 기다릴지 해지할지 정할 수 있다
     * @param sharedCalendarsOwned   내가 만든 공유 캘린더 수 — 해지하면 삭제되고 멤버도 못 본다
     * @param sharedCalendarsToTransfer 내가 소유자이고 다른 멤버가 남은 캘린더 수 — 0 이 아니면 해지가
     *                               막힌다({@code SHARED_CALENDAR_OWNED}). 소유자를 넘기고 나가야 풀린다
     * @param calendarMemberships    남의 캘린더에 들어가 있는 수 — 거기서 빠진다
     * @param dutchPaysOwned         내가 만든 정산 수 — 삭제된다
     * @param dutchPayParticipations 남의 정산에 참가한 수 — "탈퇴한 사용자" 로 남는다
     */
    @Builder
    public record CheckResult(
            List<String> blocked,
            LocalDateTime subscriptionPeriodEnd,
            int sharedCalendarsOwned,
            int sharedCalendarsToTransfer,
            int calendarMemberships,
            int dutchPaysOwned,
            int dutchPayParticipations
    ) {
        /**
         * 막는 사유가 하나라도 있나.
         *
         * <p>이름이 {@code isBlocked} 가 아닌 이유 — Jackson 이 그 이름을 레코드 컴포넌트
         * {@code blocked} 와 같은 프로퍼티로 보고 목록 대신 boolean 을 내보낸다. 화면은 사유
         * 목록을 읽어야 하므로 목록이 이겨야 한다.
         */
        public boolean hasBlockers() {
            return blocked != null && !blocked.isEmpty();
        }
    }
}
