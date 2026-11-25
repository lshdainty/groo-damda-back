package io.groo.damda.calendar.service;

import io.groo.core.type.YNType;
import io.groo.damda.calendar.domain.CalendarEvent;
import io.groo.damda.calendar.domain.EventReminder;
import io.groo.damda.calendar.domain.UserCalendar;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.repository.EventLabelRepository;
import io.groo.damda.calendar.repository.EventReminderRepository;
import io.groo.damda.calendar.repository.UserCalendarRepository;
import io.groo.damda.calendar.service.dto.CalendarEventServiceDto;
import io.groo.damda.common.patch.Patch;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 한 일정에 같은 알림이 두 번 잡히지 않게 하는 자리 — 저장 전에 접는다(QA #78).
 *
 * <p>여기서 고정하는 것은 셋이다.
 * <ol>
 *   <li>요청에 같은 (타입, 사전분) 이 두 번 담겨 와도 <b>한 행</b>만 만든다.</li>
 *   <li>수정은 전량 삭제 후 재삽입이 아니라 <b>있으면 그 행을 두고, 없는 것만 만들고,
 *       빠진 것만 지운다</b>(공통 원칙 ③). 그래야 이미 보낸 알림이 다시 울리지 않는다.</li>
 *   <li>{@code minutes_before} 는 NOT NULL 이라 리스트에 섞인 null 원소를 걷어낸다(#309).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EventReminderSyncTest {

    @Mock private CalendarEventRepository calendarEventRepository;
    @Mock private EventLabelRepository eventLabelRepository;
    @Mock private EventReminderRepository eventReminderRepository;
    @Mock private UserCalendarRepository userCalendarRepository;
    @Mock private UserCalendarService userCalendarService;
    @Mock private UserRepository userRepository;
    @Mock private CalendarMembershipValidator calendarMembershipValidator;

    @InjectMocks private CalendarEventServiceImpl sut;

    private static final long USER_ID = 1L;
    private static final long EVENT_ID = 77L;
    private static final long CALENDAR_ID = 40L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 6, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 6, 1, 11, 0);

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private User user() {
        User u = User.createUser(null, "tester", "테스터", "tester@porest.com");
        ReflectionTestUtils.setField(u, "rowId", USER_ID);
        return u;
    }

    private CalendarEvent event() {
        CalendarEvent e = CalendarEvent.createEvent(user(), "회의", null, null, null,
                START, END, null, null, null, null, mock(UserCalendar.class));
        ReflectionTestUtils.setField(e, "rowId", EVENT_ID);
        return e;
    }

    private EventReminder reminder(long rowId, String type, Integer minutes, YNType sent) {
        EventReminder r = EventReminder.create(event(), type, minutes);
        ReflectionTestUtils.setField(r, "rowId", rowId);
        if (sent == YNType.Y) {
            r.markSent();
        }
        return r;
    }

    private CalendarEventServiceDto.CreateCommand createCmd(Integer... minutes) {
        return new CalendarEventServiceDto.CreateCommand(
                USER_ID, "회의", null, null, null, START, END, null,
                null, null, null, Arrays.asList(minutes), CALENDAR_ID);
    }

    private CalendarEventServiceDto.UpdateCommand updateCmd(List<Integer> minutes) {
        return new CalendarEventServiceDto.UpdateCommand(
                "회의", Patch.absent(), null, Patch.absent(), START, END, null,
                Patch.absent(), Patch.absent(), Patch.absent(), minutes, Patch.absent());
    }

    /** 생성 경로가 지나가는 조회들. */
    private void stubCreatePath() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
        given(userCalendarRepository.findById(CALENDAR_ID)).willReturn(Optional.of(mock(UserCalendar.class)));
    }

    /** 수정 경로가 지나가는 조회들 + 지금 저장돼 있는 알림. */
    private void stubUpdatePath(EventReminder... existing) {
        CalendarEvent event = event();
        given(calendarEventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
        io.groo.damda.calendar.domain.UserCalendarMember member = mock(io.groo.damda.calendar.domain.UserCalendarMember.class);
        given(member.getPermission()).willReturn(io.groo.damda.calendar.type.CalendarRole.EDIT);
        given(calendarMembershipValidator.validateMembership(any(), anyLong())).willReturn(member);
        given(eventReminderRepository.findByEventId(EVENT_ID)).willReturn(new ArrayList<>(List.of(existing)));
    }

    private List<Integer> savedMinutes() {
        ArgumentCaptor<EventReminder> captor = ArgumentCaptor.forClass(EventReminder.class);
        verify(eventReminderRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(EventReminder::getMinutesBefore).toList();
    }

    private List<Long> deletedRowIds() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(eventReminderRepository, org.mockito.Mockito.atLeast(0)).deleteById(captor.capture());
        return captor.getAllValues();
    }

    // ── 생성 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createEvent — 같은 알림이 두 번 담겨 와도 한 행만 만든다")
    void createFoldsDuplicateMinutes() {
        stubCreatePath();

        var info = sut.createEvent(createCmd(10, 10, 30, 10));

        // 접기가 없으면 요청 원소 수(4)만큼 행이 생긴다.
        assertThat(savedMinutes()).containsExactly(10, 30);
        assertThat(info.reminders()).hasSize(2);
    }

    @Test
    @DisplayName("createEvent — 리스트에 섞인 null 원소는 저장까지 가지 않는다")
    void createDropsNullMinutes() {
        stubCreatePath();

        sut.createEvent(createCmd(null, 10, null));

        // minutes_before 는 NOT NULL 이라 null 이 그대로 내려가면 500 이 된다.
        assertThat(savedMinutes()).containsExactly(10);
    }

    @Test
    @DisplayName("createEvent — 요청 순서를 그대로 지킨다")
    void createKeepsRequestOrder() {
        stubCreatePath();

        sut.createEvent(createCmd(30, 10, 30));

        assertThat(savedMinutes()).containsExactly(30, 10);
    }

    // ── 수정 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateEvent — 그대로인 알림은 그 행을 둔다(전량 삭제 후 재삽입 금지)")
    void updateKeepsUnchangedRows() {
        stubUpdatePath(reminder(1L, "NOTIFICATION", 10, YNType.N),
                       reminder(2L, "NOTIFICATION", 30, YNType.N));

        sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10, 30)));

        assertThat(savedMinutes()).isEmpty();
        assertThat(deletedRowIds()).isEmpty();
        // 전량 삭제는 이 경로에서 더는 쓰지 않는다 — 쓰면 row_id 가 매번 바뀐다.
        verify(eventReminderRepository, never()).deleteByEventId(anyLong());
    }

    @Test
    @DisplayName("updateEvent — 이미 보낸 알림은 일정을 고쳐도 다시 울리지 않는다")
    void updateDoesNotResurrectSentReminder() {
        EventReminder sent = reminder(1L, "NOTIFICATION", 10, YNType.Y);
        stubUpdatePath(sent);

        var info = sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10)));

        // 지웠다 다시 넣으면 is_sent 가 'N' 인 새 행이 되어 어제 울린 알림이 오늘 또 울린다.
        assertThat(savedMinutes()).isEmpty();
        assertThat(info.reminders()).singleElement()
                .satisfies(r -> {
                    assertThat(r.rowId()).isEqualTo(1L);
                    assertThat(r.isSent()).isEqualTo(YNType.Y);
                });
        assertThat(sent.getIsSent()).isEqualTo(YNType.Y);
    }

    @Test
    @DisplayName("updateEvent — 빠진 알림만 지우고 새 알림만 만든다")
    void updateAddsAndRemovesOnlyTheDiff() {
        stubUpdatePath(reminder(1L, "NOTIFICATION", 10, YNType.N),
                       reminder(2L, "NOTIFICATION", 30, YNType.N));

        sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10, 60)));

        assertThat(savedMinutes()).containsExactly(60);
        assertThat(deletedRowIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("updateEvent — 요청에 같은 알림이 두 번 담겨도 한 행만 남는다")
    void updateFoldsDuplicateMinutes() {
        stubUpdatePath();

        sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10, 10)));

        assertThat(savedMinutes()).containsExactly(10);
    }

    @Test
    @DisplayName("updateEvent — 유일성이 붙기 전에 쌓인 중복 행은 저장할 때 하나로 정리된다")
    void updateCollapsesPreExistingDuplicates() {
        stubUpdatePath(reminder(1L, "NOTIFICATION", 10, YNType.N),
                       reminder(2L, "NOTIFICATION", 10, YNType.N),
                       reminder(3L, "NOTIFICATION", 10, YNType.N));

        var info = sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10)));

        // 가장 먼저 만들어진 행을 남긴다 — 이미 보냈을 가능성이 가장 높은 행이다.
        assertThat(deletedRowIds()).containsExactly(2L, 3L);
        assertThat(savedMinutes()).isEmpty();
        assertThat(info.reminders()).singleElement()
                .satisfies(r -> assertThat(r.rowId()).isEqualTo(1L));
    }

    @Test
    @DisplayName("updateEvent — 서버가 안 만드는 타입의 알림은 세트에서 빠진다")
    void updateDropsForeignReminderTypes() {
        // reminder_type 은 유일성 키에 들어가 있다. 지금 서버는 NOTIFICATION 만 만들므로
        // 다른 타입이 남아 있으면 이번 세트에 속하지 않는 행으로 보고 지운다.
        stubUpdatePath(reminder(1L, "EMAIL", 10, YNType.N));

        sut.updateEvent(EVENT_ID, USER_ID, updateCmd(List.of(10)));

        assertThat(deletedRowIds()).containsExactly(1L);
        assertThat(savedMinutes()).containsExactly(10);
    }

    @Test
    @DisplayName("updateEvent — 알림을 안 보낸 요청은 기존 알림을 건드리지 않는다")
    void updateWithoutRemindersLeavesThemAlone() {
        stubUpdatePath(reminder(1L, "NOTIFICATION", 10, YNType.N));

        var info = sut.updateEvent(EVENT_ID, USER_ID, updateCmd(null));

        assertThat(savedMinutes()).isEmpty();
        assertThat(deletedRowIds()).isEmpty();
        verify(eventReminderRepository, never()).deleteByEventId(anyLong());
        assertThat(info.reminders()).hasSize(1);
    }
}
