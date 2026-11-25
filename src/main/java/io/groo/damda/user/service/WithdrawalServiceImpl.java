package io.groo.damda.user.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.apitoken.service.ApiTokenService;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.service.UserCalendarService;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.dutchpay.domain.DutchPay;
import io.groo.damda.dutchpay.domain.DutchPayParticipant;
import io.groo.damda.dutchpay.repository.DutchPayRepository;
import io.groo.damda.expense.domain.RecurringTransaction;
import io.groo.damda.expense.repository.RecurringTransactionRepository;
import io.groo.damda.securities.domain.UserSecuritiesCredential;
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
import io.groo.damda.user.service.dto.WithdrawalServiceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * desk 이용 해지 구현.
 *
 * <p><b>한 트랜잭션이다.</b> 중간에 실패하면 전부 되돌아간다 — "캘린더는 지워졌는데 계정은
 * 살아 있는" 반쪽 상태가 남으면 사용자도 우리도 무엇이 끝났는지 알 수 없다.
 *
 * <p><b>SSO 호출과 세션 폐기는 커밋 뒤</b>에 한다. 그 둘은 되돌릴 수 없으므로, 트랜잭션이
 * 굴러떨어지면 이미 나간 요청을 주워 담을 방법이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {

    /** 남의 정산·캘린더에서 탈퇴자를 가리키는 이름. */
    /** 화면 표시(`User.displayName()`)와 같은 이름을 쓴다 — 갈리면 한 사람이 두 이름으로 보인다. */
    private static final String ANONYMOUS_NAME = User.WITHDRAWN_DISPLAY_NAME;

    private final UserRepository userRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final UserCalendarService userCalendarService;
    private final UserCalendarMemberRepository calendarMemberRepository;
    private final DutchPayRepository dutchPayRepository;
    private final TodoRepository todoRepository;
    private final RecurringTransactionRepository recurringTransactionRepository;
    private final UserSecuritiesCredentialRepository securitiesCredentialRepository;
    private final SecuritiesCredentialService securitiesCredentialService;
    private final ApiTokenService apiTokenService;
    private final SsoSessionService ssoSessionService;
    private final SsoOAuth2Client ssoOAuth2Client;

    @Override
    @Transactional(readOnly = true)
    public WithdrawalServiceDto.CheckResult check(Long userRowId) {
        List<UserSubscription> entitled = entitledSubscriptions(userRowId);

        List<UserCalendarServiceDto.CalendarInfo> calendars = userCalendarService.getCalendars(userRowId);
        long ownedShared = calendars.stream().filter(c -> isOwnedShareable(c)).count();
        long toTransfer = calendars.stream().filter(WithdrawalServiceImpl::isOwnedWithMembers).count();
        long memberships = calendars.stream()
                .filter(c -> c.myRole() != CalendarRole.OWNER)
                .count();

        int ownedDutchPays = dutchPayRepository.findAllByUser(userRowId).size();
        int participations = (int) dutchPayRepository.findParticipantsByUser(userRowId).stream()
                .filter(p -> !isMine(p, userRowId))
                .count();

        return WithdrawalServiceDto.CheckResult.builder()
                .blocked(blockers(!entitled.isEmpty(), toTransfer > 0))
                .subscriptionPeriodEnd(entitled.stream()
                        .map(UserSubscription::getCurrentPeriodEnd)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null))
                .sharedCalendarsOwned((int) ownedShared)
                .sharedCalendarsToTransfer((int) toTransfer)
                .calendarMemberships((int) memberships)
                .dutchPaysOwned(ownedDutchPays)
                .dutchPayParticipations(participations)
                .build();
    }

    @Override
    @Transactional
    public void withdraw(Long userRowId, String reason) {
        User user = userRepository.findById(userRowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        if (user.isWithdrawn()) {
            // 이미 끝났다 — 재시도를 에러로 만들지 않는다.
            log.info("이미 해지된 사용자. userRowId={}", userRowId);
            return;
        }

        // ① 구독은 화면이 아니라 여기서 최종 판정한다 — 점검과 해지 사이에 바뀔 수 있다.
        if (!entitledSubscriptions(userRowId).isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.WITHDRAW_BLOCKED_SUBSCRIPTION);
        }
        // 다른 멤버가 남은 캘린더의 소유자는 먼저 소유자를 넘긴다 — 해지가 지우면 멤버가 함께 쓰던 일정이
        // 사라진다. 점검과 해지 사이에 누가 들어올 수 있으니 여기서 다시 본다.
        if (userCalendarService.getCalendars(userRowId).stream().anyMatch(WithdrawalServiceImpl::isOwnedWithMembers)) {
            throw new InvalidValueException(DeskErrorCode.WITHDRAW_BLOCKED_SHARED_CALENDAR);
        }

        disconnectSecurities(userRowId);
        clearCalendars(userRowId);
        clearDutchPays(userRowId);
        detachTodoAssignments(userRowId);
        deactivateRecurringRules(userRowId);

        user.withdraw(reason);
        log.info("desk 이용 해지 완료. userRowId={}, ssoUserRowId={}", userRowId, user.getSsoUserRowId());

        afterCommit(user);
    }

    /** 활성 구독 — 해지했어도 기간이 남아 있으면 아직 권한이 산다(구독 규칙 그대로). */
    private List<UserSubscription> entitledSubscriptions(Long userRowId) {
        return subscriptionRepository.findEntitled(userRowId, YNType.N, LocalDateTime.now());
    }

    private static List<String> blockers(boolean subscription, boolean sharedCalendar) {
        List<String> reasons = new java.util.ArrayList<>();
        if (subscription) reasons.add("SUBSCRIPTION_ACTIVE");
        if (sharedCalendar) reasons.add("SHARED_CALENDAR_OWNED");
        return List.copyOf(reasons);
    }

    /** 내가 소유자이고 다른 멤버가 남아 있는 캘린더 — 기본 캘린더도 포함한다(기본 캘린더도 공유할 수 있다). */
    private static boolean isOwnedWithMembers(UserCalendarServiceDto.CalendarInfo c) {
        return c.myRole() == CalendarRole.OWNER && c.memberCount() > 1;
    }

    /** 내가 OWNER 이고 기본 캘린더가 아닌 것 — 기본 캘린더는 삭제 자체가 금지다. */
    private boolean isOwnedShareable(UserCalendarServiceDto.CalendarInfo c) {
        return c.myRole() == CalendarRole.OWNER && !c.isDefault();
    }

    private boolean isMine(DutchPayParticipant p, Long userRowId) {
        return p.getDutchPay() != null
                && p.getDutchPay().getUser() != null
                && userRowId.equals(p.getDutchPay().getUser().getRowId());
    }

    /**
     * ② 증권 키를 못 쓰게 한다. 증권사 API 를 부르지는 않는다 — 우리가 가진 키만 끊는다.
     *
     * <p>그 키로 가는 프로그램용 API 토큰도 같이 폐기한다. 토큰은 주인이 해지했으면 어차피
     * 거절되지만({@code ApiTokenService#authenticate}), 살아 있는 표식으로 남겨 둘 이유가 없다.
     */
    private void disconnectSecurities(Long userRowId) {
        for (UserSecuritiesCredential c : securitiesCredentialRepository.findAllByUserRowId(userRowId)) {
            securitiesCredentialService.disconnect(userRowId, c.getBroker());
        }
        apiTokenService.revokeAll(userRowId);
    }

    /**
     * ③④ 내가 만든 공유 캘린더는 지우고(멤버 전원 끊김), 남의 캘린더에서는 내가 빠진다.
     *
     * <p><b>기본 캘린더는 건드리지 않는다.</b> {@code deleteCalendar()} 가 기본 캘린더를
     * 거부하므로 그대로 부르면 해지 전체가 롤백된다. 기본 캘린더와 그 일정은 내 데이터라
     * 파기 배치가 가져간다.
     */
    private void clearCalendars(Long userRowId) {
        for (UserCalendarServiceDto.CalendarInfo c : userCalendarService.getCalendars(userRowId)) {
            if (isOwnedShareable(c)) {
                userCalendarService.deleteCalendar(c.rowId(), userRowId);
            } else if (c.myRole() != CalendarRole.OWNER) {
                // 내가 스스로 나가는 길이다. `removeMember` 는 **소유자가 남을 내보내는**
                // 길이라 `validateOwner` 를 지나고, 남의 캘린더 멤버인 사람은 거기서
                // 403 을 맞아 해지가 통째로 롤백됐다(티켓만 쓰고 아무것도 안 됨).
                userCalendarService.leaveCalendar(c.rowId(), userRowId);
            }
        }
    }

    /**
     * ⑥⑦ 내가 만든 정산은 지우고, 남의 정산에 남은 나는 이름만 남기고 연결을 끊는다.
     *
     * <p>이름이 겹치면 활성 이름 UNIQUE 에 걸리므로 뒤에 번호를 붙인다 — 제약을 풀지 않는다.
     */
    private void clearDutchPays(Long userRowId) {
        for (DutchPay d : dutchPayRepository.findAllByUser(userRowId)) {
            d.deleteDutchPay();
        }
        for (DutchPayParticipant p : dutchPayRepository.findParticipantsByUser(userRowId)) {
            if (isMine(p, userRowId)) {
                continue; // 내 정산은 위에서 통째로 지웠다
            }
            p.anonymize(ANONYMOUS_NAME + " #" + p.getRowId());
        }
    }

    /** ⑧ 남이 나에게 맡긴 할 일에서 배정만 뗀다 — 할 일 자체는 만든 사람 것이다. */
    private void detachTodoAssignments(Long userRowId) {
        for (Todo t : todoRepository.findAllByAssignee(userRowId)) {
            t.setAssignee(null);
        }
    }

    /** ⑨ 자정 배치가 탈퇴자 이름으로 거래를 만들지 않게 규칙을 내린다. */
    private void deactivateRecurringRules(Long userRowId) {
        for (RecurringTransaction r : recurringTransactionRepository.findByUser(userRowId)) {
            r.deactivate();
        }
    }

    /**
     * ⑪⑫ 되돌릴 수 없는 바깥 호출은 커밋 뒤에 한다.
     *
     * <p>SSO 접근 비활성이 실패해도 해지를 되돌리지 않는다 — desk 쪽은 이미 막혔고(토큰
     * 교환이 거절), 이건 이중 안전장치다.
     */
    private void afterCommit(User user) {
        Long ssoUserRowId = user.getSsoUserRowId();
        String userId = user.getUserId();
        // 세션 **행**은 지금(트랜잭션 안에서) 끊는다. 커밋 뒤에는 영속성 컨텍스트가 닫혀
        // 있어 더티 체킹이 DB 에 닿지 않는다 — 예전엔 Redis 표식만 남고 행은 활성으로
        // 남아 있었다(2026-09-17 QA). 되돌릴 수 없는 Redis 표식만 커밋 뒤로 미룬다.
        List<String> revokedSessionIds = ssoSessionService.revokeAllRows(user.getRowId());

        Runnable work = () -> {
            // 둘을 각자 감싼다 — 앞이 터져서 뒤가 안 도는 일이 없어야 한다.
            // 특히 표식은 이미 끊은 행과 짝이라, 안 남기면 옛 토큰이 만료까지 살아 있다.
            try {
                if (ssoUserRowId != null) {
                    ssoOAuth2Client.deactivateDeskAccess(ssoUserRowId);
                }
            } catch (Exception e) {
                log.error("해지 후 SSO 접근 해제 실패 — desk 쪽은 이미 막혔다. userId={}", userId, e);
            }
            try {
                ssoSessionService.markRevokedAll(revokedSessionIds);
            } catch (Exception e) {
                log.error("해지 후 세션 폐기 표식 실패 — 행은 이미 끊겼다. userId={}", userId, e);
            }
        };

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 불린 경우(단위 테스트 등) — 기다릴 커밋이 없으니 바로 한다.
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }
}
