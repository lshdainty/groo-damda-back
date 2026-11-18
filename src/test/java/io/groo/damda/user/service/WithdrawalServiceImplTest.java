package io.groo.damda.user.service;

import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.dutchpay.domain.DutchPay;
import io.groo.damda.dutchpay.domain.DutchPayParticipant;
import io.groo.damda.dutchpay.repository.DutchPayRepository;
import io.groo.damda.expense.domain.RecurringTransaction;
import io.groo.damda.expense.repository.RecurringTransactionRepository;
import io.groo.damda.securities.repository.UserSecuritiesCredentialRepository;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.security.client.SsoOAuth2Client;
import io.groo.damda.security.session.service.SsoSessionService;
import io.groo.damda.subscription.domain.UserSubscription;
import io.groo.damda.subscription.repository.UserSubscriptionRepository;
import io.groo.damda.todo.domain.Todo;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 해지는 <b>되돌릴 수 없다.</b> 그래서 (1) 막아야 할 때 확실히 막히는지, (2) 남의 것에 남긴
 * 흔적이 제대로 정리되는지, (3) 기본 캘린더처럼 삭제가 금지된 것을 건드려 전체가 롤백되지
 * 않는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalServiceImplTest {

    private static final Long USER = 3L;

    @Mock private UserRepository userRepository;
    @Mock private UserSubscriptionRepository subscriptionRepository;
    @Mock private UserCalendarService userCalendarService;
    @Mock private UserCalendarMemberRepository calendarMemberRepository;
    @Mock private DutchPayRepository dutchPayRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private RecurringTransactionRepository recurringTransactionRepository;
    @Mock private UserSecuritiesCredentialRepository securitiesCredentialRepository;
    @Mock private SecuritiesCredentialService securitiesCredentialService;
    @Mock private ApiTokenService apiTokenService;
    @Mock private SsoSessionService ssoSessionService;
    @Mock private SsoOAuth2Client ssoOAuth2Client;

    private WithdrawalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalServiceImpl(userRepository, subscriptionRepository,
                userCalendarService, calendarMemberRepository, dutchPayRepository, todoRepository,
                recurringTransactionRepository, securitiesCredentialRepository,
                securitiesCredentialService, apiTokenService, ssoSessionService, ssoOAuth2Client);

        given(userRepository.findById(USER)).willReturn(Optional.of(user()));
        given(subscriptionRepository.findEntitled(anyLong(), any(), any())).willReturn(List.of());
        given(userCalendarService.getCalendars(USER)).willReturn(List.of());
        given(dutchPayRepository.findAllByUser(USER)).willReturn(List.of());
        given(dutchPayRepository.findParticipantsByUser(USER)).willReturn(List.of());
        given(todoRepository.findAllByAssignee(USER)).willReturn(List.of());
        given(recurringTransactionRepository.findByUser(USER)).willReturn(List.of());
        given(securitiesCredentialRepository.findAllByUserRowId(USER)).willReturn(List.of());
    }

    private User user() {
        User u = User.createUser(11L, "qa_user", "홍길동", "hong@hangroo.io");
        ReflectionTestUtils.setField(u, "rowId", USER);
        return u;
    }

    private UserCalendarServiceDto.CalendarInfo calendar(Long id, boolean isDefault, CalendarRole role) {
        return new UserCalendarServiceDto.CalendarInfo(id, 1L, "주인", "달력", "#fff", 0,
                isDefault, true, null, false, role == CalendarRole.OWNER, role, 1,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("구독이 살아 있으면 막는다 — 화면이 아니라 여기서 최종 판정한다")
    void blockedBySubscription() {
        UserSubscription sub = org.mockito.Mockito.mock(UserSubscription.class);
        given(subscriptionRepository.findEntitled(anyLong(), any(), any())).willReturn(List.of(sub));

        assertThatThrownBy(() -> service.withdraw(USER, null))
                .isInstanceOf(InvalidValueException.class);
        verify(ssoOAuth2Client, never()).deactivateDeskAccess(anyLong());
    }

    @Test
    @DisplayName("이미 해지한 계정을 또 불러도 조용히 끝난다 — 재시도가 실패로 보이면 안 된다")
    void idempotent() {
        User withdrawn = user();
        withdrawn.withdraw(null);
        given(userRepository.findById(USER)).willReturn(Optional.of(withdrawn));

        service.withdraw(USER, null);

        verify(userCalendarService, never()).deleteCalendar(anyLong(), anyLong());
        verify(apiTokenService, never()).revokeAll(anyLong());
    }

    @Test
    @DisplayName("해지하면 프로그램용 API 토큰도 폐기한다 — 증권 키로 가는 길을 남겨 두지 않는다")
    void revokesApiTokens() {
        service.withdraw(USER, null);

        verify(apiTokenService).revokeAll(USER);
    }

    @Test
    @DisplayName("기본 캘린더는 건드리지 않는다 — 삭제가 금지돼 있어 부르면 해지 전체가 롤백된다")
    void defaultCalendarUntouched() {
        given(userCalendarService.getCalendars(USER)).willReturn(List.of(
                calendar(1L, true, CalendarRole.OWNER),    // 기본 — 그대로 둔다
                calendar(2L, false, CalendarRole.OWNER))); // 공유 — 지운다

        service.withdraw(USER, null);

        verify(userCalendarService).deleteCalendar(2L, USER);
        verify(userCalendarService, never()).deleteCalendar(1L, USER);
    }

    /**
     * 남의 캘린더에서는 <b>내가 스스로 나간다</b>.
     *
     * <p>예전엔 {@code removeMember} 를 불렀는데 그건 <b>소유자가 남을 내보내는</b> 길이라
     * {@code validateOwner} 를 지난다 — 남의 캘린더 멤버인 사람은 거기서 403 을 맞아
     * 해지가 통째로 롤백됐다(티켓만 태우고 아무것도 안 됨, 2026-09-17 QA 실측).
     *
     * <p>단위 테스트가 그걸 못 잡은 이유는 {@code removeMember} 자체를 mock 해서다 —
     * 실제로 권한 검사를 지나지 않으니 늘 통과했다. 그래서 여기서는 <b>어느 길로 부르는지</b>
     * 를 못박고, 권한 검사가 실제로 통과하는지는 아래 통합 테스트가 본다.
     */
    @Test
    @DisplayName("남의 캘린더에서는 내가 스스로 나간다 — 소유자 권한이 필요한 길로 가지 않는다")
    void leavesOthersCalendars() {
        given(userCalendarService.getCalendars(USER)).willReturn(List.of(
                calendar(9L, false, CalendarRole.READ)));

        service.withdraw(USER, null);

        verify(userCalendarService).leaveCalendar(9L, USER);
        verify(userCalendarService, never())
                .removeMember(anyLong(), anyLong(), anyLong());
        verify(userCalendarService, never()).deleteCalendar(anyLong(), anyLong());
    }

    @Test
    @DisplayName("남의 정산에 남은 나는 연결만 끊고 이름을 남긴다 — 정산은 만든 사람 것이다")
    void anonymizesParticipation() {
        DutchPay mine = org.mockito.Mockito.mock(DutchPay.class);
        DutchPayParticipant p = DutchPayParticipant.create(mine, user(), "홍길동", 1000L, false);
        ReflectionTestUtils.setField(p, "rowId", 42L);
        ReflectionTestUtils.setField(p, "dutchPay", null); // 남의 정산(소유자 판정에서 빠진다)
        given(dutchPayRepository.findParticipantsByUser(USER)).willReturn(List.of(p));

        service.withdraw(USER, null);

        assertThat(p.getUser()).isNull();
        assertThat(p.getParticipantName()).isEqualTo("탈퇴한 사용자 #42");
    }

    @Test
    @DisplayName("반복 규칙을 내린다 — 자정 배치가 탈퇴자 거래를 만들면 안 된다")
    void deactivatesRecurring() {
        RecurringTransaction r = org.mockito.Mockito.mock(RecurringTransaction.class);
        given(recurringTransactionRepository.findByUser(USER)).willReturn(List.of(r));

        service.withdraw(USER, null);

        verify(r).deactivate();
    }

    @Test
    @DisplayName("나에게 배정된 할 일은 배정만 뗀다")
    void detachesTodoAssignment() {
        Todo t = org.mockito.Mockito.mock(Todo.class);
        given(todoRepository.findAllByAssignee(USER)).willReturn(List.of(t));

        service.withdraw(USER, null);

        verify(t).setAssignee(null);
    }

    @Test
    @DisplayName("해지하면 시각과 사유가 남는다 — 파기 배치가 셀 기준이 필요하다")
    void stampsWithdrawnAt() {
        User u = user();
        given(userRepository.findById(USER)).willReturn(Optional.of(u));

        service.withdraw(USER, "이유");

        assertThat(u.isWithdrawn()).isTrue();
        assertThat(u.getWithdrawnAt()).isNotNull();
        assertThat(u.getWithdrawReason()).isEqualTo("이유");
    }

    @Test
    @DisplayName("점검은 막는 사유와 알리기만 하는 것을 나눠 준다")
    void checkSplitsBlockedAndInfo() {
        given(userCalendarService.getCalendars(USER)).willReturn(List.of(
                calendar(1L, true, CalendarRole.OWNER),
                calendar(2L, false, CalendarRole.OWNER),
                calendar(3L, false, CalendarRole.EDIT)));

        var result = service.check(USER);

        assertThat(result.hasBlockers()).isFalse();
        assertThat(result.sharedCalendarsOwned()).isEqualTo(1); // 기본은 안 센다
        assertThat(result.calendarMemberships()).isEqualTo(1);
    }

    /**
     * 세션 <b>행</b>은 트랜잭션 안에서 끊고, 되돌릴 수 없는 Redis 표식만 커밋 뒤로 미룬다.
     *
     * <p>둘 다 커밋 뒤에 하면 영속성 컨텍스트가 닫혀 있어 더티 체킹이 <b>DB 에 닿지
     * 않는다</b> — 표식만 남고 행은 활성으로 남아 있었다(2026-09-17 QA 실측:
     * {@code user_sso_session} 4행 활성 유지). 여기서는 트랜잭션이 없어 둘 다 바로
     * 도는데, <b>어느 것을 어느 메서드로 부르는지</b> 가 고정되면 그 분리가 지켜진다.
     */
    @Test
    @DisplayName("세션은 행을 먼저 끊고 표식은 그 목록으로 남긴다 — 옛 revokeAll 로 뭉치지 않는다")
    void revokesSessionRowsThenMarks() {
        given(ssoSessionService.revokeAllRows(USER)).willReturn(List.of("sid-1", "sid-2"));

        service.withdraw(USER, null);

        verify(ssoSessionService).revokeAllRows(USER);
        verify(ssoSessionService).markRevokedAll(List.of("sid-1", "sid-2"));
        verify(ssoSessionService, never()).revokeAll(anyLong());
    }

    /**
     * SSO 가 죽어도 <b>세션 표식은 남는다.</b>
     *
     * <p>예전엔 접근 해제가 먼저였고 그 실패가 그대로 튀어 표식까지 건너뛰었다 —
     * 이미 끊은 행과 짝이 안 맞아 옛 토큰이 만료까지 살아 있었다.
     */
    @Test
    @DisplayName("SSO 접근 해제가 터져도 세션 표식은 남고 해지는 실패로 보이지 않는다")
    void ssoFailureDoesNotSkipSessionMarks() {
        given(ssoSessionService.revokeAllRows(USER)).willReturn(List.of("sid-1"));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("SSO down"))
                .given(ssoOAuth2Client).deactivateDeskAccess(anyLong());

        service.withdraw(USER, null);

        verify(ssoSessionService).markRevokedAll(List.of("sid-1"));
    }
}