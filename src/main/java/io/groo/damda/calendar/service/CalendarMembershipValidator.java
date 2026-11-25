package io.groo.damda.calendar.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.type.CalendarRole;
import io.groo.damda.calendar.repository.UserCalendarMemberRepository;
import io.groo.damda.calendar.repository.UserCalendarRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 공유 캘린더 멤버십·권한 검증. group 의 GroupMembershipValidator 를 캘린더 도메인으로 이식.
 * 소유자도 user_calendar_member 에 OWNER 행으로 존재하므로 멤버 조회로 단일 판정.
 */
@Component
@RequiredArgsConstructor
public class CalendarMembershipValidator {
    private final UserCalendarMemberRepository memberRepo;
    private final UserCalendarRepository calendarRepo;

    public UserCalendarMember validateMembership(Long calendarRowId, Long userRowId) {
        calendarRepo.findById(calendarRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));
        return memberRepo.findByCalendarAndUser(calendarRowId, userRowId)
            .orElseThrow(() -> new ForbiddenException(DeskErrorCode.CALENDAR_ACCESS_DENIED));
    }

    /**
     * 공유 중인 캘린더인가 — 소유자 말고 멤버가 한 명이라도 있으면 공유 중이다.
     * 공유 중인 캘린더의 일정은 그 캘린더 밖으로 옮길 수 없다({@code CalendarEventServiceImpl.applyCalendar}).
     */
    public boolean isShared(Long calendarRowId) {
        return memberRepo.findAllByCalendar(calendarRowId).stream()
            .anyMatch(m -> m.getPermission() != CalendarRole.OWNER);
    }

    /** 쓰기 권한(일정 생성·수정·삭제) 검증. 읽기전용(READ) 멤버는 자기가 만든 일정이라도 차단. */
    public UserCalendarMember validateCanWrite(Long calendarRowId, Long userRowId) {
        UserCalendarMember member = validateMembership(calendarRowId, userRowId);
        if (!member.getPermission().canWrite()) {
            throw new ForbiddenException(DeskErrorCode.CALENDAR_ACCESS_DENIED);
        }
        return member;
    }

    /** 멤버 관리 권한(초대·퇴출·권한변경) 검증. 소유자만 허용. */
    public UserCalendarMember validateOwner(Long calendarRowId, Long userRowId) {
        UserCalendarMember member = validateMembership(calendarRowId, userRowId);
        if (!member.getPermission().canManageMembers()) {
            throw new ForbiddenException(DeskErrorCode.CALENDAR_ACCESS_DENIED);
        }
        return member;
    }

    /** 사용자가 접근 가능한(소유+공유받은) 모든 캘린더 id. */
    public List<Long> getAccessibleCalendarIds(Long userRowId) {
        return memberRepo.findCalendarIdsByUser(userRowId);
    }
}
