package io.groo.damda.export.service;

import io.groo.core.time.UserClock;
import io.groo.core.type.YNType;
import io.groo.core.util.TimeUtils;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.calendar.repository.CalendarEventRepository;
import io.groo.damda.calendar.service.dto.CalendarEventServiceDto;
import io.groo.damda.expense.domain.ExpenseBudget;
import io.groo.damda.expense.repository.ExpenseBudgetRepository;
import io.groo.damda.expense.repository.ExpenseCategoryRepository;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.service.dto.ExpenseBudgetServiceDto;
import io.groo.damda.expense.service.dto.ExpenseCategoryServiceDto;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.groo.damda.export.repository.ExportCountRepository;
import io.groo.damda.export.type.ExportType;
import io.groo.damda.memo.repository.MemoRepository;
import io.groo.damda.memo.service.dto.MemoServiceDto;
import io.groo.damda.todo.repository.TodoRepository;
import io.groo.damda.todo.service.dto.TodoServiceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 각 데이터 종류를 내보내기 표({@link ExportTable})로 변환.
 *
 * <p>기간 적용 규칙(타입별 분기): 거래=expenseDate, 예산=budgetYear/Month 교집합,
 * 캘린더=start/endDate, 할일=dueDate. 자산·카테고리·메모는 기간 무관 전체.
 *
 * <p>FK 는 이름 우선(categoryName/assetName 등 DTO 동봉 이름). 마스킹 ON 시
 * 금융 민감필드(잔액·금액·신용한도·기관)를 "****" 로 가린다.
 *
 * <p>건수는 현재 목록 size 기반(MVP). 대용량 거래의 전용 COUNT 쿼리는 후속 최적화.
 *
 * <p><b>일시 컬럼 규칙</b> — 어느 기준인지는 porest-sql 컬럼 COMMENT 의 {@code [UTC]}/{@code [userClock]}
 * 표기로 판단한다. {@code [userClock]}(거래일·일정 시작/종료)은 이미 사용자 벽시계라 변환하지 않고
 * ({@link #wallClock}), {@code [UTC]}(메모 생성일·할 일 완료일)만 {@link TimeUtils#toUserZone} 으로
 * 사용자 타임존으로 바꾼다({@link #utcAt}). 벽시계를 또 변환하면 자정 근처 날짜가 하루 밀린다.
 * 출력 형식은 전 파일 {@code yyyy-MM-dd HH:mm}, 날짜 전용 열(할 일 마감일)은 {@code yyyy-MM-dd}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExportDataService {

    private static final String MASKED = "****";

    private final ExpenseRepository expenseRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseBudgetRepository expenseBudgetRepository;
    private final AssetRepository assetRepository;
    private final MemoRepository memoRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final TodoRepository todoRepository;
    private final ExportCountRepository exportCountRepository;
    private final UserClock userClock;

    /** 전체 행을 담은 표. */
    public ExportTable buildTable(ExportType type, Long userRowId, LocalDate start, LocalDate end, boolean mask) {
        return switch (type) {
            case EXPENSE -> expenseTable(userRowId, start, end, mask);
            case ASSET -> assetTable(userRowId, mask);
            case BUDGET -> budgetTable(userRowId, start, end, mask);
            case CATEGORY -> categoryTable(userRowId);
            case MEMO -> memoTable(userRowId);
            case CALENDAR -> calendarTable(userRowId, start, end, mask);
            case TODO -> todoTable(userRowId, start, end, mask);
        };
    }

    /** 건수 (기간 적용 후). 대용량 타입은 전용 COUNT(*), 그 외는 목록 size. */
    public long count(ExportType type, Long userRowId, LocalDate start, LocalDate end) {
        return switch (type) {
            case EXPENSE -> exportCountRepository.countExpense(userRowId, start, end);
            case CALENDAR -> exportCountRepository.countCalendar(userRowId, start, end);
            case TODO -> exportCountRepository.countTodo(userRowId, start, end);
            // 자산·카테고리·예산·메모는 사용자당 소량 — 목록 size 로 충분.
            default -> buildTable(type, userRowId, start, end, false).rows().size();
        };
    }

    // ── 타입별 표 ─────────────────────────────────────────────

    private ExportTable expenseTable(Long userRowId, LocalDate start, LocalDate end, boolean mask) {
        // 환불된 거래도 **행으로는 싣는다** — 받아 간 자료가 화면과 달라지면 안 된다.
        // 다만 합계에는 안 들어가므로 그 사실을 칸으로 알려 준다(설계 5절).
        List<String> headers = List.of("날짜", "유형", "카테고리", "자산", "금액", "설명",
            "거래처", "결제수단", "환불");
        List<List<String>> rows = new ArrayList<>();
        expenseRepository.findByDateRange(userRowId, start, end).forEach(e -> {
            ExpenseServiceDto.ExpenseInfo i = ExpenseServiceDto.ExpenseInfo.from(e);
            rows.add(List.of(
                wallClock(i.expenseDate()),
                cell(i.expenseType()),
                cell(i.categoryName()),
                cell(i.assetName()),
                money(mask, i.amount()),
                cell(i.description()),
                cell(i.merchant()),
                cell(i.paymentMethod()),
                i.refundedAt() != null ? "Y" : "N"
            ));
        });
        return new ExportTable(ExportType.EXPENSE, headers, rows);
    }

    private ExportTable assetTable(Long userRowId, boolean mask) {
        // 내보내기는 화면 가리기를 따르지 않는다 — 본인이 받아 가는 자기 자료라
        // 금액을 그대로 싣고, 가릴지는 `mask` 옵션이 따로 정한다. "금액 숨김" 은
        // **그 자산을 화면에서 가려 뒀다는 사실**을 알려 주는 칸이다.
        List<String> headers =
                List.of("자산명", "유형", "잔액", "통화", "기관", "메모", "총자산 포함", "금액 숨김");
        List<List<String>> rows = new ArrayList<>();
        assetRepository.findByUser(userRowId).forEach(a -> {
            AssetServiceDto.AssetInfo i = AssetServiceDto.AssetInfo.from(a);
            rows.add(List.of(
                cell(i.assetName()),
                cell(i.assetType()),
                money(mask, i.balance()),
                cell(i.currency()),
                mask ? MASKED : cell(i.institution()),
                cell(i.memo()),
                yn(i.isIncludedInTotal()),
                yn(i.isAmountHidden())
            ));
        });
        return new ExportTable(ExportType.ASSET, headers, rows);
    }

    private ExportTable budgetTable(Long userRowId, LocalDate start, LocalDate end, boolean mask) {
        List<String> headers = List.of("연도", "월", "카테고리", "예산액");
        List<List<String>> rows = new ArrayList<>();
        YearMonth from = YearMonth.from(start);
        YearMonth to = YearMonth.from(end);
        // 한 번에 읽고 기간으로 거른다. 종전엔 달마다 조회해 기간 폭만큼 쿼리가 나갔다
        // (0001~9999 면 12만 번 — 보안 점검 DAM-2). 예산은 사용자당 (달 × 카테고리) 수준이라 작다.
        // 정렬은 이른 달부터 — 같은 달 안의 순서는 저장소가 준 순서를 그대로 둔다(stable sort).
        expenseBudgetRepository.findByUser(userRowId, null, null).stream()
            .filter(b -> {
                YearMonth ym = YearMonth.of(b.getBudgetYear(), b.getBudgetMonth());
                return !ym.isBefore(from) && !ym.isAfter(to);
            })
            .sorted(Comparator.comparing(ExpenseBudget::getBudgetYear)
                .thenComparing(ExpenseBudget::getBudgetMonth))
            .forEach(b -> {
                ExpenseBudgetServiceDto.BudgetInfo i = ExpenseBudgetServiceDto.BudgetInfo.from(b);
                rows.add(List.of(
                    cell(i.budgetYear()),
                    cell(i.budgetMonth()),
                    cell(i.categoryName()),
                    money(mask, i.budgetAmount())
                ));
            });
        return new ExportTable(ExportType.BUDGET, headers, rows);
    }

    private ExportTable categoryTable(Long userRowId) {
        List<String> headers = List.of("카테고리명", "유형", "상위 카테고리", "정렬순서");
        var categories = expenseCategoryRepository.findAllByUser(userRowId);
        Map<Long, String> nameById = new HashMap<>();
        categories.forEach(c -> nameById.put(c.getRowId(), c.getCategoryName()));
        List<List<String>> rows = new ArrayList<>();
        categories.forEach(c -> {
            ExpenseCategoryServiceDto.CategoryInfo i = ExpenseCategoryServiceDto.CategoryInfo.from(c);
            String parentName = i.parentRowId() != null
                ? nameById.getOrDefault(i.parentRowId(), String.valueOf(i.parentRowId()))
                : "";
            rows.add(List.of(
                cell(i.categoryName()),
                cell(i.expenseType()),
                parentName,
                cell(i.sortOrder())
            ));
        });
        return new ExportTable(ExportType.CATEGORY, headers, rows);
    }

    private ExportTable memoTable(Long userRowId) {
        List<String> headers = List.of("제목", "내용", "고정", "생성일");
        List<List<String>> rows = new ArrayList<>();
        ZoneId zone = userClock.zoneOf(userRowId);
        memoRepository.findAllByUser(userRowId, null).forEach(m -> {
            MemoServiceDto.MemoInfo i = MemoServiceDto.MemoInfo.from(m);
            rows.add(List.of(
                cell(i.title()),
                cell(i.content()),
                yn(i.isPinned()),
                utcAt(i.createAt(), zone)
            ));
        });
        return new ExportTable(ExportType.MEMO, headers, rows);
    }

    private ExportTable calendarTable(Long userRowId, LocalDate start, LocalDate end, boolean mask) {
        List<String> headers = List.of("제목", "시작", "종료", "종일", "유형", "라벨", "캘린더", "장소", "설명");
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.atTime(LocalTime.MAX);
        List<List<String>> rows = new ArrayList<>();
        calendarEventRepository.findByUserAndDateRange(userRowId, startDt, endDt).forEach(e -> {
            CalendarEventServiceDto.EventInfo i = CalendarEventServiceDto.EventInfo.from(e);
            rows.add(List.of(
                cell(i.title()),
                wallClock(i.startDate()),
                wallClock(i.endDate()),
                yn(i.isAllDay()),
                cell(i.eventType()),
                cell(i.labelName()),
                cell(i.calendarName()),
                cell(i.location()),
                cell(i.description())
            ));
        });
        return new ExportTable(ExportType.CALENDAR, headers, rows);
    }

    private ExportTable todoTable(Long userRowId, LocalDate start, LocalDate end, boolean mask) {
        List<String> headers = List.of("제목", "유형", "상태", "우선순위", "카테고리", "마감일", "완료일", "내용");
        List<List<String>> rows = new ArrayList<>();
        ZoneId zone = userClock.zoneOf(userRowId);
        todoRepository.findByUserAndDueDateBetween(userRowId, start, end).forEach(t -> {
            TodoServiceDto.TodoInfo i = TodoServiceDto.TodoInfo.from(t);
            rows.add(List.of(
                cell(i.title()),
                cell(i.type()),
                cell(i.status()),
                cell(i.priority()),
                cell(i.category()),
                cell(i.dueDate()),
                utcAt(i.completedAt(), zone),
                cell(i.content())
            ));
        });
        return new ExportTable(ExportType.TODO, headers, rows);
    }

    // ── 셀 헬퍼 ──────────────────────────────────────────────

    private static String cell(Object o) {
        if (o == null) return "";
        if (o instanceof Enum<?> e) return e.name();
        return String.valueOf(o);
    }

    /** 내보내기 공통 일시 형식 — 전 파일·전 열 동일. */
    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * {@code [userClock]} 벽시계 열 — 변환하지 않고 형식만 통일한다.
     * (LocalDateTime.toString() 은 초·나노가 0 이면 생략해 같은 열에서 자릿수가 들쭉날쭉했다.)
     */
    private static String wallClock(LocalDateTime v) {
        return v == null ? "" : v.format(EXPORT_TS);
    }

    /** {@code [UTC]} 시스템 열 — 사용자 타임존으로 바꾼 뒤 형식 통일. zone 은 UserClock 이 폴백까지 마친 값. */
    private static String utcAt(LocalDateTime utc, ZoneId zone) {
        if (utc == null) return "";
        return TimeUtils.toUserZone(utc, zone.getId()).format(EXPORT_TS);
    }

    /** 금융 민감 금액 — 마스킹 ON 이면 가린다. */
    private static String money(boolean mask, Object amount) {
        if (amount == null) return "";
        return mask ? MASKED : String.valueOf(amount);
    }

    private static String yn(YNType v) {
        if (v == null) return "";
        return v == YNType.Y ? "예" : "아니오";
    }
}
