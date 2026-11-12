package io.groo.damda.calendar.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.domain.EventLabel;
import io.groo.damda.calendar.domain.EventReminder;
import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.calendar.domain.UserCalendarMember;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.repository.EventLabelRepository;
import io.groo.damda.calendar.repository.EventReminderRepository;
import io.groo.damda.calendar.repository.UserCalendarRepository;
import io.groo.damda.calendar.service.dto.CalendarEventServiceDto;
import io.groo.damda.calendar.service.dto.EventReminderServiceDto;
import io.groo.damda.calendar.service.dto.UserCalendarServiceDto;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.common.validation.PeriodLimits;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CalendarEventServiceImpl implements CalendarEventService {
    private final CalendarEventRepository calendarEventRepository;
    private final EventLabelRepository eventLabelRepository;
    private final EventReminderRepository eventReminderRepository;
    private final UserCalendarRepository userCalendarRepository;
    private final UserCalendarService userCalendarService;
    private final UserRepository userRepository;
    private final CalendarMembershipValidator calendarMembershipValidator;

    /**
     * 지금 서버가 만드는 유일한 알림 종류. {@code event_reminder.reminder_type} 은 컬럼으로 남아 있고
     * 유일성도 이 값을 낀 조합(event_row_id, reminder_type, minutes_before)으로 잡는다 —
     * 나중에 "10분 전 푸시 + 10분 전 메일" 을 넣을 자리를 지금 막지 않기 위해서다.
     */
    private static final String DEFAULT_REMINDER_TYPE = "NOTIFICATION";

    /**
     * 일정 목록 조회({@code GET /calendar/events}) 한 번의 최대 기간 폭(일). 종료 ≤ 시작 + 이 값.
     *
     * <p>반복 일정은 일정 하나가 구간 안에서 최대 {@code RecurrenceExpander.MAX_OCCURRENCES_IN_RANGE}
     * (2,000)회까지 펼쳐지므로, 폭에 상한이 없으면 매일 반복 50개만으로 요청 한 건이
     * 발생 100,000건 · 응답 약 43MB 를 만들었다(보안 점검 — 한 달 조회는 1,500건 · 약 0.65MB).
     *
     * <p><b>값의 근거 — 화면이 보내는 가장 넓은 기간을 깨지 않는다.</b> 웹 {@code CalendarContent}
     * 는 월·목록 보기 = 그달 1일~말일(최대 31일), 주 보기 = 7일만 요청하고, 앱
     * {@code monthEventsProvider} 도 그달 1일~말일만 요청한다. 다른 호출처는 없다. 월 격자를 앞뒤
     * 주까지(6주 = 42일) 불러오게 바뀌어도 들어가도록 두 달(62일)로 잡았다. 화면이 더 긴 기간을
     * 한 번에 부르게 되면 여기를 같이 넓혀야 한다(웹 연 보기는 v2.0.0 에서 걷혔다).
     */
    static final long MAX_QUERY_SPAN_DAYS = 62;

    @Override
    @Transactional
    public CalendarEventServiceDto.EventInfo createEvent(CalendarEventServiceDto.CreateCommand command) {
        log.debug("캘린더 이벤트 등록 시작: userRowId={}, title={}", command.userRowId(), command.title());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        validateDateRange(command.startDate(), command.endDate());

        EventLabel label = null;
        if (command.labelRowId() != null) {
            label = eventLabelRepository.findById(command.labelRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EVENT_LABEL_NOT_FOUND));
            validateLabelOwnership(label, command.userRowId());
        }

        // 캘린더는 <b>반드시</b> 붙는다. 안 보냈으면 거절이 아니라 기본 캘린더를 대입한다
        // (사용자 결정 2026-09-08 "캘린더 없는 일정은 존재할 수 없다" 의 서버 쪽 절반).
        //
        // 생성에서 거절을 고르지 않은 이유: 캘린더를 고르는 것은 사용자가 내린 결정이 아니라
        // 화면이 채워 주는 값이다. 안 왔다는 것은 "아무 데나" 가 아니라 "화면이 아직 못 채웠다"
        // 는 뜻이고, 그때 400 을 던지면 <b>일정 자체를 못 만든다</b> — 사용자가 고칠 수 있는
        // 입력이 아니라서 되돌릴 방법도 없다. 답이 하나뿐인 상황이면 서버가 그 답을 쓴다.
        // (수정은 다르다 — updateEvent 주석 참고.)
        UserCalendar calendar;
        if (command.calendarRowId() != null) {
            calendar = userCalendarRepository.findById(command.calendarRowId())
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));
            // 공유 캘린더면 편집가능(EDIT) 이상만 일정 생성 가능 (읽기전용 차단)
            calendarMembershipValidator.validateCanWrite(command.calendarRowId(), command.userRowId());
        } else {
            calendar = defaultCalendarOf(command.userRowId());
        }

        CalendarEvent event = CalendarEvent.createEvent(
            user,
            command.title(),
            command.description(),
            command.eventType(),
            command.color(),
            command.startDate(),
            command.endDate(),
            command.isAllDay(),
            label,
            command.location(),
            command.rrule(),
            calendar
        );
        calendarEventRepository.save(event);

        List<EventReminderServiceDto.ReminderInfo> reminderInfos = new ArrayList<>();
        for (Integer minutes : distinctReminderMinutes(command.reminderMinutes())) {
            EventReminder reminder = EventReminder.create(event, DEFAULT_REMINDER_TYPE, minutes);
            eventReminderRepository.save(reminder);
            reminderInfos.add(EventReminderServiceDto.ReminderInfo.from(reminder));
        }

        log.info("캘린더 이벤트 등록 완료: eventId={}, userRowId={}", event.getRowId(), command.userRowId());
        return CalendarEventServiceDto.EventInfo.from(event, reminderInfos);
    }

    @Override
    public List<CalendarEventServiceDto.EventInfo> getEvents(Long userRowId, LocalDateTime startDate, LocalDateTime endDate) {
        log.debug("캘린더 이벤트 목록 조회: userRowId={}, startDate={}, endDate={}", userRowId, startDate, endDate);

        validateQueryRange(startDate, endDate);

        // 접근 가능한(소유 + 공유받은) 모든 캘린더의 이벤트
        List<Long> calendarIds = calendarMembershipValidator.getAccessibleCalendarIds(userRowId);
        List<CalendarEvent> events = calendarEventRepository.findByCalendarIdsAndDateRange(calendarIds, startDate, endDate);

        List<Long> eventIds = events.stream().map(CalendarEvent::getRowId).toList();
        Map<Long, List<EventReminderServiceDto.ReminderInfo>> remindersMap = loadRemindersMap(eventIds);

        // 반복(rrule) 이벤트는 구간 안 발생(occurrence)들로 전개해 내려준다 —
        // 전개가 없으면 매주 반복이 첫 회차 한 번만 화면에 남는다.
        List<CalendarEventServiceDto.EventInfo> result = new java.util.ArrayList<>();
        for (CalendarEvent event : events) {
            CalendarEventServiceDto.EventInfo base = CalendarEventServiceDto.EventInfo.from(
                event, remindersMap.getOrDefault(event.getRowId(), List.of()));
            for (RecurrenceExpander.Occurrence oc : RecurrenceExpander.expand(
                    event.getStartDate(), event.getEndDate(), event.getRrule(), startDate, endDate)) {
                result.add(base.withOccurrence(oc.startDate(), oc.endDate()));
            }
        }
        result.sort(java.util.Comparator.comparing(CalendarEventServiceDto.EventInfo::startDate));
        return result;
    }

    @Override
    @Transactional
    public CalendarEventServiceDto.EventInfo updateEvent(Long eventId, Long userRowId, CalendarEventServiceDto.UpdateCommand command) {
        log.debug("캘린더 이벤트 수정 시작: eventId={}", eventId);

        CalendarEvent event = findEventOrThrow(eventId);
        validateEventOwnership(event, userRowId);

        validateDateRange(command.startDate(), command.endDate());

        // 라벨은 <b>실렸을 때만</b> 찾는다 — 안 보낸 요청이 붙여 둔 라벨을 떼면 안 되고,
        // 없는 값을 조회하면 404 가 난다. 명시적 null 은 그대로 통과해 라벨을 뗀다.
        EventLabel label = command.labelRowId()
            .map(rowId -> {
                EventLabel found = eventLabelRepository.findById(rowId)
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EVENT_LABEL_NOT_FOUND));
                validateLabelOwnership(found, userRowId);
                return found;
            })
            .orKeep(event.getActiveLabel());

        String rrule = command.rrule().orKeep(event.getRrule());
        RecurrenceExpander.Occurrence dates = seriesDates(event, rrule, command);

        // 실린 칸만 바꾼다 — 안 온 칸은 지금 값을 그대로 넘긴다(QA #96).
        event.updateEvent(
            command.title(),
            command.description().orKeep(event.getDescription()),
            command.eventType(),
            command.color().orKeep(event.getColor()),
            dates.startDate(),
            dates.endDate(),
            command.isAllDay(),
            label,
            command.location().orKeep(event.getLocation()),
            rrule
        );

        applyCalendar(event, command.calendarRowId(), userRowId);

        List<EventReminderServiceDto.ReminderInfo> reminderInfos;
        if (command.reminderMinutes() != null) {
            reminderInfos = syncReminders(event, command.reminderMinutes());
        } else {
            reminderInfos = eventReminderRepository.findByEventId(eventId).stream()
                .map(EventReminderServiceDto.ReminderInfo::from)
                .toList();
        }

        log.info("캘린더 이벤트 수정 완료: eventId={}", eventId);
        return CalendarEventServiceDto.EventInfo.from(event, reminderInfos);
    }

    /**
     * 수정이 새로 쓸 시작·종료.
     *
     * <p>반복 일정은 회차마다 따로 있는 게 아니라 일정 하나가 반복해서 보이는 것이고, 수정은
     * 반복 전체에 적용된다. 그런데 조회는 회차마다 그 회차의 날짜로 내려가고 폼은 그 날짜로
     * 채워져서, 뒤 회차를 열어 아무것도 안 바꾸고 저장해도 반복의 시작이 그 회차로 옮겨져
     * 앞 회차들이 사라졌다(QA 30 2). 그래서 연 회차의 원래 시작을 받아 <b>그 회차에서 바뀐
     * 만큼만</b> 반복 전체를 옮긴다 — 안 바꿨으면 그대로, 시각을 한 시간 늦추면 모든 회차가
     * 한 시간 늦게, 날짜를 하루 미루면 모든 회차가 하루 뒤로.
     *
     * <p>보낸 날짜를 그대로 쓰는 경우: 연 회차를 안 알려 준 요청(옛 클라이언트), 반복이 아닌
     * 일정, 반복을 끄는 수정(폼에 보이는 그 회차가 남는 일정이다), 지금 반복의 회차가 아닌 값.
     */
    private static RecurrenceExpander.Occurrence seriesDates(CalendarEvent event, String rrule,
                                                             CalendarEventServiceDto.UpdateCommand command) {
        LocalDateTime opened = command.occurrenceStartDate();
        if (opened == null || rrule == null || event.getRrule() == null || !isOccurrenceOf(event, opened)) {
            return new RecurrenceExpander.Occurrence(command.startDate(), command.endDate());
        }
        LocalDateTime start = event.getStartDate().plus(Duration.between(opened, command.startDate()));
        return new RecurrenceExpander.Occurrence(
            start, start.plus(Duration.between(command.startDate(), command.endDate())));
    }

    /** 이 일정의 지금 반복에 {@code at} 에 시작하는 회차가 있는가. */
    private static boolean isOccurrenceOf(CalendarEvent event, LocalDateTime at) {
        return RecurrenceExpander.expand(event.getStartDate(), event.getEndDate(), event.getRrule(), at, at)
            .stream()
            .anyMatch(oc -> oc.startDate().equals(at));
    }

    @Override
    @Transactional
    public void deleteEvent(Long eventId, Long userRowId) {
        log.debug("캘린더 이벤트 삭제 시작: eventId={}", eventId);

        CalendarEvent event = findEventOrThrow(eventId);
        validateEventOwnership(event, userRowId);
        event.deleteEvent();
        eventReminderRepository.deleteByEventId(eventId);

        log.info("캘린더 이벤트 삭제 완료: eventId={}", eventId);
    }

    /**
     * 요청이 보낸 알림 분(分) 목록을 <b>저장할 수 있는 형태</b>로 접는다 — null 을 걷어내고 중복을 없앤다.
     *
     * <p>중복을 409 로 되돌리지 않는 이유: "10분 전을 두 번 알려 달라" 는 표현할 수 있는 의도가 아니다.
     * 라벨·태그의 이름 중복은 사용자가 고쳐야 할 입력이지만, 같은 알림 두 개는 요청이 잘못됐다기보다
     * 화면이 같은 줄을 두 번 담은 것에 가깝다. 조용히 하나로 접는 게 사용자가 기대하는 결과다.
     *
     * <p>null 원소를 걷어내는 것도 여기다. {@code minutes_before} 는 DB·엔티티 모두 NOT NULL 이라
     * {@code [null]} 이 그대로 내려가면 500 이 됐다. 컨트롤러 DTO 가 {@code @NotNull} 로 먼저 막지만,
     * 서비스를 직접 부르는 경로(가져오기·집계)까지 덮으려면 이 자리에도 있어야 한다.
     *
     * <p>순서는 요청이 보낸 순서를 유지한다({@link LinkedHashSet}) — 응답의 알림 순서가 요청과
     * 어긋나면 화면이 방금 저장한 줄을 못 찾는다.
     */
    private static List<Integer> distinctReminderMinutes(List<Integer> reminderMinutes) {
        if (reminderMinutes == null || reminderMinutes.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(
            reminderMinutes.stream().filter(Objects::nonNull).toList()));
    }

    /**
     * 알림 세트를 요청대로 맞춘다 — <b>있으면 그 행을 두고, 없는 것만 만들고, 빠진 것만 지운다.</b>
     *
     * <p>종전에는 {@code deleteByEventId} 로 전량을 지우고 다시 넣었다. 결과 세트는 같지만 두 가지가
     * 무너진다.
     * <ul>
     *   <li><b>이미 보낸 알림이 다시 간다.</b> 새로 만든 행은 {@code is_sent='N'} 이라, 일정 제목만
     *       고쳐도 어제 울린 알림이 오늘 또 울린다.</li>
     *   <li><b>row_id 가 매번 바뀐다.</b> 지금은 참조하는 곳이 없지만, 알림 읽음 표시처럼 행을
     *       가리키는 것이 하나라도 붙는 순간 끊긴다.</li>
     * </ul>
     * 지운 알림은 소프트 삭제가 아니라 <b>실제 DELETE</b> 다 — 이 테이블에는 삭제 플래그가 없다.
     */
    private List<EventReminderServiceDto.ReminderInfo> syncReminders(CalendarEvent event, List<Integer> reminderMinutes) {
        List<Integer> wanted = distinctReminderMinutes(reminderMinutes);
        // 같은 (타입, 분) 이 이미 여러 행으로 들어와 있을 수 있다(DB UNIQUE 가 붙기 전에 쌓인 것).
        // 첫 행만 남기고 나머지는 여기서 정리된다 — 두 번째부터는 byMinutes 에 안 담겨 삭제 대상이 된다.
        List<EventReminder> existing = eventReminderRepository.findByEventId(event.getRowId());
        Map<Integer, EventReminder> byMinutes = new LinkedHashMap<>();
        List<EventReminder> stale = new ArrayList<>();
        for (EventReminder reminder : existing) {
            boolean sameType = DEFAULT_REMINDER_TYPE.equals(reminder.getReminderType());
            if (sameType && reminder.getMinutesBefore() != null
                && byMinutes.putIfAbsent(reminder.getMinutesBefore(), reminder) == null) {
                continue;
            }
            stale.add(reminder);
        }

        List<EventReminderServiceDto.ReminderInfo> result = new ArrayList<>(wanted.size());
        for (Integer minutes : wanted) {
            EventReminder reminder = byMinutes.remove(minutes);
            if (reminder == null) {
                reminder = EventReminder.create(event, DEFAULT_REMINDER_TYPE, minutes);
                eventReminderRepository.save(reminder);
            }
            result.add(EventReminderServiceDto.ReminderInfo.from(reminder));
        }
        // 요청에서 빠진 것 + 타입이 다르거나 중복이라 못 담은 것
        stale.addAll(byMinutes.values());
        for (EventReminder reminder : stale) {
            eventReminderRepository.deleteById(reminder.getRowId());
        }
        return result;
    }

    /**
     * 수정 요청의 소속 캘린더를 반영한다 — <b>일정이 캘린더 없이 남는 경우가 없게</b> 한다.
     *
     * <table border="1">
     *   <caption>본문 → 결과</caption>
     *   <tr><th>본문</th><th>결과</th></tr>
     *   <tr><td>키가 없다</td><td>지금 캘린더 유지. 지금이 <b>없으면</b> 기본 캘린더를 붙인다</td></tr>
     *   <tr><td>{@code "calendarRowId": 12}</td><td>12 로 옮긴다(쓰기 권한 확인)</td></tr>
     *   <tr><td>{@code "calendarRowId": null}</td><td><b>400</b> — 뗄 수 없다</td></tr>
     * </table>
     *
     * <p><b>왜 명시적 null 만 거절하나.</b> 생성과 달리 수정에서 답이 하나가 아니다. 기본 캘린더를
     * 대입하면 <b>공유 캘린더에 있던 일정이 조용히 내 개인 캘린더로 빠져나온다</b> — 같이 보던
     * 사람들의 화면에서 사라지는데 아무도 그렇게 해 달라고 한 적이 없다. 눈에 안 보이게 틀리는
     * 쪽보다 400 이 낫다. 반대로 <b>키가 없는 것</b>은 "소속을 건드리지 마라" 라서 거절할 이유가
     * 없다 — 여기서 거절하면 제목 한 줄 고치는 것도 캘린더를 같이 실어야 하고, 웹·앱은 지금
     * 그러지 않는다.
     *
     * <p><b>캘린더 없이 저장된 옛 일정</b>은 키가 없는 경로에서 기본 캘린더로 붙인다. 그냥 두면
     * 그 일정은 영영 소속이 없고(목록 조회는 접근 가능한 캘린더로만 긁으므로 화면에서도 안
     * 보인다), 거절하면 <b>고칠 방법이 없는 채로 갇힌다.</b> 붙이는 쪽만 빠져나갈 구멍이 있다.
     */
    private void applyCalendar(CalendarEvent event, Patch<Long> calendarRowId, Long userRowId) {
        if (calendarRowId.present() && calendarRowId.value() == null) {
            throw new InvalidValueException(DeskErrorCode.CALENDAR_EVENT_CALENDAR_REQUIRED);
        }
        if (calendarRowId.present()) {
            Long targetRowId = calendarRowId.value();
            UserCalendar calendar = userCalendarRepository.findById(targetRowId)
                .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));
            // 옮기려는 캘린더에 쓰기 권한 필요
            calendarMembershipValidator.validateCanWrite(targetRowId, userRowId);
            event.setCalendar(calendar);
            return;
        }
        if (event.getCalendar() == null) {
            log.info("캘린더 미소속 일정을 기본 캘린더로 붙임: eventId={}, userRowId={}", event.getRowId(), userRowId);
            event.setCalendar(defaultCalendarOf(userRowId));
        }
    }

    /** 기본 캘린더 엔티티. 없으면 만든다({@link UserCalendarService#getOrCreateDefault}). */
    private UserCalendar defaultCalendarOf(Long userRowId) {
        UserCalendarServiceDto.CalendarInfo defaultInfo = userCalendarService.getOrCreateDefault(userRowId);
        return userCalendarRepository.findById(defaultInfo.rowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_CALENDAR_NOT_FOUND));
    }

    /**
     * 이벤트는 항상 캘린더에 소속 — 캘린더 멤버십+권한으로 판정.
     * 이벤트 생성자 본인이거나 EDIT 이상 권한이면 수정/삭제 가능.
     */
    private void validateEventOwnership(CalendarEvent event, Long userRowId) {
        // 오래된 데이터에서 생성자(user)가 삭제돼 null 일 수 있으므로 null-safe 하게 소유자 id 추출.
        Long ownerRowId = event.getUser() != null ? event.getUser().getRowId() : null;
        if (event.getCalendar() != null) {
            UserCalendarMember member = calendarMembershipValidator.validateMembership(
                event.getCalendar().getRowId(), userRowId);
            if (!calendarMembershipValidator.canEditOrDelete(member, ownerRowId, userRowId)) {
                throw new ForbiddenException(DeskErrorCode.CALENDAR_EVENT_ACCESS_DENIED);
            }
            return;
        }
        // 캘린더 미소속 이벤트(옛 데이터): 생성자만 (생성자 불명이면 접근 거부).
        // 이 갈래를 남겨 둬야 본인이 수정을 열어 applyCalendar 가 기본 캘린더를 붙일 수 있다 —
        // 여기서 막으면 소속 없는 일정이 영영 소속을 못 갖는다.
        if (!userRowId.equals(ownerRowId)) {
            throw new ForbiddenException(DeskErrorCode.CALENDAR_EVENT_ACCESS_DENIED);
        }
    }

    /**
     * 시작·종료가 있고 순서가 맞는지. <b>널 검사를 여기 두는 이유</b>는 종전에
     * {@code command.startDate().isAfter(...)} 가 곧바로 NPE 를 내 <b>500</b> 이 나갔기 때문이다
     * (QA #81). 날짜를 안 보낸 것은 요청 잘못이므로 400 이 맞다.
     *
     * <p>DTO 에도 {@code @NotNull} 을 걸었지만 그것과 별개로 여기를 지킨다 — 서비스는 컨트롤러
     * 하나만 부르는 것이 아니고, 검증이 DTO 에만 있으면 그 애노테이션이 지워지는 날 다시 500 이 된다.
     */
    private static void validateDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new InvalidValueException(DeskErrorCode.CALENDAR_INVALID_DATE_RANGE);
        }
    }

    /**
     * 목록 조회 기간 검사 — 순서는 기존 {@code CAL_002}, 연도 범위·폭은 {@code PERIOD_OUT_OF_RANGE}.
     *
     * <p>연도 범위(1900~2199)는 내보내기·지출 기간 조회와 같은 {@link PeriodLimits} 를 태운다 — 폭만
     * 막으면 {@code +999999999} 년 근처 구간에서 반복 전개의 날짜 덧셈이 넘쳐 500 이 난다.
     */
    private static void validateQueryRange(LocalDateTime startDate, LocalDateTime endDate) {
        validateDateRange(startDate, endDate);
        PeriodLimits.check(startDate.toLocalDate(), endDate.toLocalDate());
        if (endDate.isAfter(startDate.plusDays(MAX_QUERY_SPAN_DAYS))) {
            throw new InvalidValueException(DeskErrorCode.PERIOD_OUT_OF_RANGE);
        }
    }

    /**
     * 라벨은 사용자 개인 분류값 — 본인 소유 라벨만 이벤트에 부착 가능.
     * (남의 라벨 부착 시 타인 라벨명/색상이 EventInfo 로 노출되는 정보 누출 차단)
     */
    private void validateLabelOwnership(EventLabel label, Long userRowId) {
        Long ownerRowId = label.getUser() != null ? label.getUser().getRowId() : null;
        if (!userRowId.equals(ownerRowId)) {
            log.warn("이벤트 라벨 소유권 검증 실패 - labelId={}, ownerRowId={}, requestUserRowId={}",
                label.getRowId(), ownerRowId, userRowId);
            throw new ForbiddenException(DeskErrorCode.EVENT_LABEL_ACCESS_DENIED);
        }
    }

    private CalendarEvent findEventOrThrow(Long eventId) {
        return calendarEventRepository.findById(eventId)
            .orElseThrow(() -> {
                log.warn("캘린더 이벤트 조회 실패 - 존재하지 않는 이벤트: eventId={}", eventId);
                return new EntityNotFoundException(DeskErrorCode.CALENDAR_EVENT_NOT_FOUND);
            });
    }

    private Map<Long, List<EventReminderServiceDto.ReminderInfo>> loadRemindersMap(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return eventReminderRepository.findByEventIds(eventIds).stream()
            .map(EventReminderServiceDto.ReminderInfo::from)
            .collect(Collectors.groupingBy(EventReminderServiceDto.ReminderInfo::eventRowId));
    }
}
