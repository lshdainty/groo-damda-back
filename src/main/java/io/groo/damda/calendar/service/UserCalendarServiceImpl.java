package io.groo.damda.calendar.service;

import io.groo.core.exception.BusinessRuleViolationException;
import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.repository.UserCalendarRepository;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserCalendarServiceImpl implements UserCalendarService {
    private final UserCalendarRepository userCalendarRepository;
    private final UserCalendarMemberRepository memberRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final UserRepository userRepository;
    private final CalendarMembershipValidator membershipValidator;
    private final CalendarJoinRateLimiter joinRateLimiter;

    @Override
    @Transactional
    public UserCalendarServiceDto.CalendarInfo createCalendar(UserCalendarServiceDto.CreateCommand command) {
        log.debug("사용자 캘린더 생성 시작: userRowId={}", command.userRowId());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        int nextOrder = userCalendarRepository.findAllByUser(command.userRowId()).size();
        boolean shouldBeDefault = userCalendarRepository.findDefaultByUser(command.userRowId()).isEmpty();

        UserCalendar calendar = UserCalendar.createCalendar(user, command.calendarName(), command.color(), nextOrder, shouldBeDefault);
        userCalendarRepository.save(calendar);

        // 소유자를 OWNER 멤버로 등록 (멤버 조회 단일화 — 소유+공유 모두 멤버 행으로 판정)
        memberRepository.save(UserCalendarMember.create(calendar, user, CalendarRole.OWNER));

        log.info("사용자 캘린더 생성 완료: calendarId={}", calendar.getRowId());
        return UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.OWNER, 1);
    }

    @Override
    public List<UserCalendarServiceDto.CalendarInfo> getCalendars(Long userRowId) {
        log.debug("사용자 캘린더 목록 조회(소유+공유): userRowId={}", userRowId);

        List<Long> ids = membershipValidator.getAccessibleCalendarIds(userRowId);
        List<UserCalendar> calendars = userCalendarRepository.findAllByIds(ids);

        List<UserCalendarServiceDto.CalendarInfo> result = new ArrayList<>();
        for (UserCalendar cal : calendars) {
            CalendarRole myRole = memberRepository.findByCalendarAndUser(cal.getRowId(), userRowId)
                .map(UserCalendarMember::getPermission)
                .orElse(CalendarRole.READ);
            int count = memberRepository.findAllByCalendar(cal.getRowId()).size();
            result.add(UserCalendarServiceDto.CalendarInfo.from(cal, myRole, count));
        }
        return result;
    }

    @Override
    @Transactional
    public UserCalendarServiceDto.CalendarInfo updateCalendar(Long calendarId, Long userRowId, UserCalendarServiceDto.UpdateCommand command) {
        UserCalendar calendar = findCalendarOrThrow(calendarId);
        validateOwnership(calendar, userRowId);

        calendar.updateCalendar(command.calendarName(), command.color());
        int count = memberRepository.findAllByCalendar(calendarId).size();
        log.info("사용자 캘린더 수정 완료: calendarId={}", calendarId);
        return UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.OWNER, count);
    }

    @Override
    @Transactional
    public UserCalendarServiceDto.CalendarInfo toggleVisibility(Long calendarId, Long userRowId) {
        UserCalendar calendar = findCalendarOrThrow(calendarId);
        // 표시 여부는 멤버마다가 아니라 <b>캘린더 하나에 한 값</b>이라, 바꾸면 소유자·다른 멤버 화면에서도
        // 숨겨진다. 그래서 쓰기 권한(소유·편집)이 있어야 바꾼다 — 종전엔 읽기 전용 멤버도 소유자의 화면에서
        // 캘린더를 숨길 수 있었다(보안 점검 DAM-8).
        membershipValidator.validateCanWrite(calendarId, userRowId);

        // 기본 캘린더는 숨길 수 없다. 일정을 만들 때 캘린더를 안 고르면 서버가 기본 캘린더를 대입하는데,
        // 그 기본 캘린더까지 숨겨져 있으면 방금 저장한 일정이 곧바로 안 보인다 — 숨긴 캘린더는 목록에서
        // 빠지므로 사용자는 어디로 갔는지도 알 수 없다. 여기서 막으면 선택칸은 늘 최소 하나를 갖고,
        // 서버의 자동 대입도 늘 보이는 곳에 떨어진다.
        if (calendar.getIsDefault() == YNType.Y) {
            throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_DEFAULT_HIDE);
        }

        calendar.toggleVisibility();
        CalendarRole myRole = memberRepository.findByCalendarAndUser(calendarId, userRowId)
            .map(UserCalendarMember::getPermission).orElse(CalendarRole.READ);
        int count = memberRepository.findAllByCalendar(calendarId).size();
        return UserCalendarServiceDto.CalendarInfo.from(calendar, myRole, count);
    }

    @Override
    @Transactional
    public void deleteCalendar(Long calendarId, Long userRowId) {
        UserCalendar calendar = findCalendarOrThrow(calendarId);
        validateOwnership(calendar, userRowId);

        if (calendar.getIsDefault() == YNType.Y) {
            throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_DEFAULT_DELETE);
        }

        // 삭제할 캘린더의 이벤트를 소유자 기본 캘린더로 이동
        UserCalendar defaultCalendar = userCalendarRepository.findDefaultByUser(calendar.getUser().getRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));

        List<CalendarEvent> events = calendarEventRepository.findByCalendarId(calendarId);
        for (CalendarEvent event : events) {
            event.setCalendar(defaultCalendar);
        }

        // 공유 멤버 정리
        for (UserCalendarMember member : memberRepository.findAllByCalendar(calendarId)) {
            member.removeMember();
        }

        calendar.deleteCalendar();
        log.info("사용자 캘린더 삭제 완료: calendarId={}, movedEvents={}", calendarId, events.size());
    }

    @Override
    @Transactional
    public UserCalendarServiceDto.CalendarInfo getOrCreateDefault(Long userRowId) {
        log.debug("기본 캘린더 조회 또는 생성: userRowId={}", userRowId);

        return userCalendarRepository.findDefaultByUser(userRowId)
            .map(c -> UserCalendarServiceDto.CalendarInfo.from(
                c, CalendarRole.OWNER, memberRepository.findAllByCalendar(c.getRowId()).size()))
            .orElseGet(() -> {
                User user = userRepository.findById(userRowId)
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

                UserCalendar defaultCalendar = UserCalendar.createCalendar(user, "내 캘린더", "#2c70bf", 0, true);
                userCalendarRepository.save(defaultCalendar);
                memberRepository.save(UserCalendarMember.create(defaultCalendar, user, CalendarRole.OWNER));

                log.info("기본 캘린더 생성 완료: calendarId={}, userRowId={}", defaultCalendar.getRowId(), userRowId);
                return UserCalendarServiceDto.CalendarInfo.from(defaultCalendar, CalendarRole.OWNER, 1);
            });
    }

    // ── 공유 ──

    @Override
    public List<UserCalendarServiceDto.MemberInfo> getMembers(Long calendarId, Long userRowId) {
        membershipValidator.validateMembership(calendarId, userRowId);
        return memberRepository.findAllByCalendar(calendarId).stream()
            .map(UserCalendarServiceDto.MemberInfo::from)
            .toList();
    }

    /**
     * 공유 켜기·코드 새로 만들기 — 소유자만. 코드가 없던 캘린더는 이때 처음 발급된다(공유를 켠 캘린더에만 코드가 있다).
     * 이미 있으면 갈아 끼워 옛 코드로는 더 참여할 수 없다.
     */
    @Override
    @Transactional
    public String regenerateInviteCode(Long calendarId, Long userRowId) {
        membershipValidator.validateOwner(calendarId, userRowId);
        UserCalendar calendar = findCalendarOrThrow(calendarId);
        String newCode = calendar.issueInviteCode();
        log.info("캘린더 초대코드 발급 완료: calendarId={}", calendarId);
        return newCode;
    }

    /** 공유 끄기 — 소유자만. 코드를 지워 새로 참여할 수 없게 한다. 이미 참여한 멤버는 그대로다. */
    @Override
    @Transactional
    public void stopSharing(Long calendarId, Long userRowId) {
        membershipValidator.validateOwner(calendarId, userRowId);
        findCalendarOrThrow(calendarId).clearInviteCode();
        log.info("캘린더 초대코드 해제 완료: calendarId={}", calendarId);
    }

    /**
     * 초대 코드로 참여.
     *
     * <p>틀린 시도는 사용자·IP 별로 세고, 한도를 넘으면 맞는 코드라도 창이 끝날 때까지 받지 않는다
     * ({@link CalendarJoinRateLimiter}, 보안 점검 DAM-4). 형식이 틀린 코드는 DB 를 보지 않고 틀린 시도로 센다.
     */
    @Override
    @Transactional
    public UserCalendarServiceDto.CalendarInfo joinByInviteCode(Long userRowId, String inviteCode, String clientIp) {
        if (!joinRateLimiter.isAllowed(userRowId, clientIp)) {
            log.warn("캘린더 참여 시도 한도 초과: userRowId={}, ip={}", userRowId, clientIp);
            throw new BusinessRuleViolationException(DeskErrorCode.USER_CALENDAR_JOIN_RATE_LIMITED);
        }
        User user = userRepository.findById(userRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        String normalized = normalizeInviteCode(inviteCode);
        UserCalendar calendar = (normalized == null ? Optional.<UserCalendar>empty()
                : userCalendarRepository.findByInviteCode(normalized))
            .orElseThrow(() -> {
                joinRateLimiter.recordFailure(userRowId, clientIp);
                return new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_INVITE_INVALID);
            });

        memberRepository.findByCalendarAndUser(calendar.getRowId(), userRowId)
            .ifPresent(m -> { throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_ALREADY_MEMBER); });

        // 초대코드로 참여 시 기본 편집가능(EDIT). 소유자가 이후 읽기전용(READ)으로 강등 가능.
        memberRepository.save(UserCalendarMember.create(calendar, user, CalendarRole.EDIT));
        int count = memberRepository.findAllByCalendar(calendar.getRowId()).size();
        log.info("캘린더 공유 참여 완료: calendarId={}, userRowId={}", calendar.getRowId(), userRowId);
        return UserCalendarServiceDto.CalendarInfo.from(calendar, CalendarRole.EDIT, count);
    }

    @Override
    @Transactional
    public void removeMember(Long calendarId, Long memberId, Long requestUserRowId) {
        membershipValidator.validateOwner(calendarId, requestUserRowId);

        UserCalendarMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_MEMBER_NOT_FOUND));
        // 무결성: 멤버가 이 캘린더 소속이어야 함(타 캘린더 memberId 로 교차 조작하는 IDOR 차단).
        if (member.getCalendar() == null || !member.getCalendar().getRowId().equals(calendarId)) {
            throw new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_MEMBER_NOT_FOUND);
        }
        if (member.getPermission() == CalendarRole.OWNER) {
            throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_OWNER_REMOVE);
        }

        member.removeMember();
        log.info("캘린더 멤버 제거 완료: memberId={}", memberId);
    }

    @Override
    @Transactional
    public void leaveCalendar(Long calendarId, Long userRowId) {
        // 소유자 권한을 보지 않는다 — 나가는 사람이 자기 자신이라 확인할 것이 "그 멤버가
        // 나인가" 뿐이다. 조회 자체를 (캘린더, 나)로 하므로 남의 행에 닿을 수가 없다.
        memberRepository.findByCalendarAndUser(calendarId, userRowId).ifPresent(member -> {
            if (member.getPermission() == CalendarRole.OWNER) {
                throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_OWNER_REMOVE);
            }
            member.removeMember();
            log.info("캘린더 나가기 완료: calendarId={}, userRowId={}", calendarId, userRowId);
        });
    }

    @Override
    @Transactional
    public void changeMemberRole(Long calendarId, Long memberId, CalendarRole permission, Long requestUserRowId) {
        membershipValidator.validateOwner(calendarId, requestUserRowId);

        if (permission == CalendarRole.OWNER) {
            throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_OWNER_REMOVE);
        }

        UserCalendarMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_MEMBER_NOT_FOUND));
        // 무결성: 멤버가 이 캘린더 소속이어야 함(타 캘린더 memberId 로 교차 조작하는 IDOR 차단).
        if (member.getCalendar() == null || !member.getCalendar().getRowId().equals(calendarId)) {
            throw new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_MEMBER_NOT_FOUND);
        }
        if (member.getPermission() == CalendarRole.OWNER) {
            throw new InvalidValueException(DeskErrorCode.USER_CALENDAR_OWNER_REMOVE);
        }

        member.changePermission(permission);
        log.info("캘린더 멤버 권한 변경 완료: memberId={}, permission={}", memberId, permission);
    }

    // ── helpers ──

    /** 붙여 넣은 값의 공백·하이픈을 걷고 대문자로 — 형식(16진수 32자)이 아니면 null. */
    static String normalizeInviteCode(String raw) {
        if (raw == null) return null;
        String code = raw.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        return INVITE_CODE_FORMAT.matcher(code).matches() ? code : null;
    }

    private static final java.util.regex.Pattern INVITE_CODE_FORMAT =
        java.util.regex.Pattern.compile("[0-9A-F]{" + UserCalendar.INVITE_CODE_LENGTH + "}");

    private UserCalendar findCalendarOrThrow(Long calendarId) {
        return userCalendarRepository.findById(calendarId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));
    }

    private void validateOwnership(UserCalendar calendar, Long userRowId) {
        if (!calendar.getUser().getRowId().equals(userRowId)) {
            throw new ForbiddenException(DeskErrorCode.CALENDAR_ACCESS_DENIED);
        }
    }
}
