package io.groo.damda.expense.controller.dto;

import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.common.validation.AmountLimits;
import io.groo.damda.expense.service.dto.ExpenseServiceDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import io.groo.damda.common.validation.InstallmentLimits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class ExpenseApiDto {

    /**
     * 거래 한 건의 금액 상한(100억원). DB 는 bigint 라 99조도 들어가지만 0 을 몇 개 더 찍는
     * 오타를 막을 곳이 없었다(QA 2026-09-02). 거래처·설명 길이도 컬럼(100 · 500)에 맞춰
     * 여기서 400 으로 거절한다 — 종전엔 DB 제약에 걸려 500 으로 터졌다.
     *
     * <p>값은 {@link AmountLimits#MAX_TX_AMOUNT} 하나에서 온다 — 예산·저축목표·반복거래도
     * 같은 층이라 리터럴이 갈리면 다음 사람이 틀린 쪽을 본다(QA 2026-09-03 #48 #52 #54).
     * 여기 이름을 남겨 두는 건 이미 이 상수를 참조하는 코드가 있어서다.
     */
    public static final long MAX_AMOUNT = AmountLimits.MAX_TX_AMOUNT;

    /**
     * {@code expense_type} · {@code amount} · {@code expense_date} 는 셋 다 NOT NULL 이고,
     * 수정 경로도 받은 값을 그대로 덮는다. 하나만 빠져도 종전엔 저장이 409 "다른 곳에서 먼저
     * 수정됐어요" 로 튕겼다(QA #81) — 돈이 걸린 화면에서 가장 오해가 큰 답이다.
     * 웹·앱 모두 생성·수정에서 셋을 항상 함께 보낸다(2026-09-07 양쪽 코드로 확인).
     *
     * <p><b>{@code categoryRowId} 도 같은 층이다</b>(QA 2026-09-07 #85). 서비스는 생성·수정
     * 양쪽에서 이 값을 <b>조건 없이</b> 카테고리 조회에 넘기는데, QueryDSL 은 {@code eq(null)} 을
     * {@code IllegalArgumentException} 으로 거절하고 그것이 {@code @Repository} 프록시에서
     * {@code InvalidDataAccessApiUsageException} 으로 번역돼 매핑이 없는 채로 <b>500</b> 이 됐다.
     * 웹은 {@code ExpenseFormValues.categoryRowId} 가 필수 필드고 앱도
     * {@code required int categoryRowId} 라 필수로 걸어도 쓰던 화면이 막히지 않는다.
     */
    @Schema(name = "ExpenseCreateRequest")
    public record CreateRequest(
        @NotNull(message = "카테고리를 골라주세요")
        Long categoryRowId,
        Long assetRowId,
        @NotNull(message = "거래 종류를 골라주세요")
        ExpenseType expenseType,
        @NotNull(message = "금액을 입력해주세요")
        @Max(value = MAX_AMOUNT, message = "금액은 100억원까지 입력할 수 있어요")
        Long amount,
        @Size(max = 500, message = "설명은 500자까지 입력할 수 있어요")
        String description,
        // "yyyy-MM-dd" 또는 "yyyy-MM-ddTHH:mm[:ss]" 양쪽 모두 허용 — 서비스 layer 에서 유연 파싱
        @NotBlank(message = "거래 일시를 입력해주세요")
        String expenseDate,
        @Size(max = 100, message = "거래처는 100자까지 입력할 수 있어요")
        String merchant,
        String paymentMethod,
        /** 할부 개월 (null·1 = 일시불). 신용카드 결제에만 의미. */
        @Max(value = InstallmentLimits.MAX_MONTHS, message = "할부는 60개월까지 입력할 수 있어요")
        Integer installmentMonths,
        /**
         * <b>폐기된 칸.</b> 환불은 이제 원거래에 찍는 표식이라(`POST /expense/{id}/refund`)
         * 수입 행을 만들지 않는다. 값이 실려 오면 400 {@code EXP_044} 로 <b>눈에 보이게</b>
         * 막는다 — 조용히 일반 수입으로 저장되면 사용자는 환불한 줄 알고 통계는 부푼다.
         * 옛 앱이 이 키를 보내므로 칸 자체는 남겨 둔다(없으면 Jackson 이 무시해 버린다).
         */
        @Deprecated
        Long refundOfExpenseRowId,
        /** 원 통화 금액 (해외 결제 시). null 이면 원화 결제. */
        java.math.BigDecimal originalAmount,
        /** 원 통화 (ISO 4217, 예: USD). */
        String originalCurrency,
        /** 적용 환율 (원 통화 1단위당 원화). */
        java.math.BigDecimal exchangeRate,
        Long calendarEventRowId,
        Long todoRowId,
        /** 고정 수입·지출 (null = 변동). 옛 앱은 이 키를 안 보내므로 변동으로 만든다. */
        Boolean isFixed
    ) {}

    /**
     * 수정 본문 — <b>실린 칸만 바꾼다</b>(QA #96, 사용자 결정 2026-09-07).
     *
     * <p>{@code Optional} 참조가 {@code null} 이면 키가 없었던 것(유지),
     * {@code Optional.empty()} 면 {@code null} 이 실린 것(지움)이다
     * ({@code AbsentAwareOptionalModule}). 종전엔 안 보낸 {@code merchant}·{@code description}·
     * {@code assetRowId} 가 null 로 덮여 사라졌다.
     *
     * <p>NOT NULL 칸({@code categoryRowId}·{@code expenseType}·{@code amount}·{@code expenseDate})은
     * "지운다" 가 성립하지 않는다 — 제약을 {@code Optional} 안에 그대로 두어 <b>실렸을 때만</b>
     * 검사한다. 안 보내면 지금 값을 지키고, 명시적 {@code null} 은 종전처럼 400 이다.
     *
     * <p>{@code splits} 만 {@code Optional} 이 아니다 — 이 칸은 종전부터
     * "{@code null}=분할 미변경, 리스트=교체" 였고 그 계약을 그대로 둔다.
     */
    @Schema(name = "ExpenseUpdateRequest")
    public record UpdateRequest(
        Optional<@NotNull(message = "카테고리를 골라주세요") Long> categoryRowId,
        Optional<Long> assetRowId,
        Optional<@NotNull(message = "거래 종류를 골라주세요") ExpenseType> expenseType,
        Optional<@NotNull(message = "금액을 입력해주세요")
                 @Max(value = MAX_AMOUNT, message = "금액은 100억원까지 입력할 수 있어요")
                 Long> amount,
        Optional<@Size(max = 500, message = "설명은 500자까지 입력할 수 있어요") String> description,
        // "yyyy-MM-dd" 또는 "yyyy-MM-ddTHH:mm[:ss]" 양쪽 모두 허용 — 서비스 layer 에서 유연 파싱
        Optional<@NotBlank(message = "거래 일시를 입력해주세요") String> expenseDate,
        Optional<@Size(max = 100, message = "거래처는 100자까지 입력할 수 있어요") String> merchant,
        Optional<String> paymentMethod,
        /** 할부 개월 (null·1 = 일시불). 신용카드 결제에만 의미. */
        Optional<@Max(value = InstallmentLimits.MAX_MONTHS, message = "할부는 60개월까지 입력할 수 있어요") Integer> installmentMonths,
        /**
         * <b>폐기된 칸.</b> 환불은 이제 원거래에 찍는 표식이라(`POST /expense/{id}/refund`)
         * 수입 행을 만들지 않는다. 값이 실려 오면 400 {@code EXP_044} 로 <b>눈에 보이게</b>
         * 막는다 — 조용히 일반 수입으로 저장되면 사용자는 환불한 줄 알고 통계는 부푼다.
         * 옛 앱이 이 키를 보내므로 칸 자체는 남겨 둔다(없으면 Jackson 이 무시해 버린다).
         */
        @Deprecated
        Optional<Long> refundOfExpenseRowId,
        /** 원 통화 금액 (해외 결제 시). null 이면 원화 결제. */
        Optional<java.math.BigDecimal> originalAmount,
        /** 원 통화 (ISO 4217, 예: USD). */
        Optional<String> originalCurrency,
        /** 적용 환율 (원 통화 1단위당 원화). */
        Optional<java.math.BigDecimal> exchangeRate,
        Optional<Long> calendarEventRowId,
        Optional<Long> todoRowId,
        // 분할 내역 동시 수정(선택). null = 분할 미변경, 리스트 = 새 분할로 교체(금액과 합 일치 필요).
        List<ExpenseSplitApiDto.SplitRequest> splits,
        /** 고정 수입·지출 — 안 보내면 유지, null 을 보내면 변동으로 되돌린다. */
        Optional<Boolean> isFixed
    ) {}

    @Schema(name = "ExpenseResponse")
    public record Response(
        Long rowId,
        Long userRowId,
        Long categoryRowId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        Long assetRowId,
        String assetName,
        ExpenseType expenseType,
        Long amount,
        String description,
        LocalDateTime expenseDate,
        String merchant,
        String paymentMethod,
        /** 할부 개월 (null·1 = 일시불). 신용카드 결제에만 의미. */
        Integer installmentMonths,
        /** 환불 처리 시각 (null = 환불 아님). 화면이 취소선·"환불됨" 배지로 그린다. */
        LocalDateTime refundedAt,
        /** 환불 마크가 만든 카드→결제계좌 환급 이체 (null = 없음). */
        Long refundTransferRowId,
        /** 원 통화 금액 (해외 결제 시). null 이면 원화 결제. */
        java.math.BigDecimal originalAmount,
        /** 원 통화 (ISO 4217, 예: USD). */
        String originalCurrency,
        /** 적용 환율 (원 통화 1단위당 원화). */
        java.math.BigDecimal exchangeRate,
        Long calendarEventRowId,
        Long todoRowId,
        /**
         * 시스템이 만든 거래의 출처 (TRADE_REALIZED / TRANSFER_INTEREST). null 이면 손으로 쓴 거래.
         * 값이 있으면 금액·날짜·자산이 잠긴다 — 화면이 입력을 막을 수 있게 내려 준다.
         */
        String autoSource,
        LocalDateTime createAt,
        LocalDateTime modifyAt,
        // 활성 분할 항목들의 카테고리 id (없으면 빈 리스트). 목록 카테고리 필터 split-aware 용.
        List<Long> splitCategoryRowIds,
        /**
         * 이 저장이 <b>방금 만든</b> 카드 환급액 (없으면 null) — 거래의 속성이 아니라
         * 그 요청의 결과다. 조회에는 늘 null 이다(설계 13-1).
         */
        Long refundedAmount,
        /**
         * 이 날짜(회차 말일)까지의 카드 회차분은 계좌 이체 없이 정리된 기록용 (null = 정상).
         * 화면이 "기록만" 배지를 단다(닫힌 회차 규칙 R2).
         */
        java.time.LocalDate cardSettledThrough,
        /** 기록만 남긴 금액 — 할부는 지난 회차분만이라 거래 금액보다 작을 수 있다 (null = 없음). */
        Long recordOnlyAmount,
        /**
         * 돈 칸 잠금(D12) — true 면 금액·날짜·시간·자산·할부·유형·통화 3칸·결제수단을 못 고친다.
         * 카테고리·가맹점·메모만 고치고, 돈 칸은 {@code POST /expense/{id}/replace}(고쳐 쓰기)로 바꾼다.
         */
        boolean moneyLocked,
        /**
         * [고쳐 쓰기]를 띄울 수 있는가 — 잠겼고 환불·시스템 거래·중도 정리 할부가 아닌 거래.
         * false 인 잠긴 거래(중도 정리한 할부)는 돈 칸을 바꿀 길이 없다.
         */
        boolean replaceable,
        /** 고정 수입·지출 여부 — 통계가 전체·고정·변동으로 가른다. */
        boolean isFixed,
        /** 지금 사용자가 직접 지울 수 있는가 — 손으로 쓴 거래는 true, 자동 생성은 그 증권 자산이 삭제된 실현손익일 때만 true. 삭제 버튼은 이것으로 가른다. */
        boolean deletable
    ) {
        public static Response from(ExpenseServiceDto.ExpenseInfo info) {
            return new Response(
                info.rowId(),
                info.userRowId(),
                info.categoryRowId(),
                info.categoryName(),
                info.categoryIcon(),
                info.categoryColor(),
                info.assetRowId(),
                info.assetName(),
                info.expenseType(),
                info.amount(),
                info.description(),
                info.expenseDate(),
                info.merchant(),
                info.paymentMethod(),
                info.installmentMonths(),
                info.refundedAt(),
                info.refundTransferRowId(),
                info.originalAmount(),
                info.originalCurrency(),
                info.exchangeRate(),
                info.calendarEventRowId(),
                info.todoRowId(),
                info.autoSource(),
                info.createAt(),
                info.modifyAt(),
                info.splitCategoryRowIds(),
                info.refundedAmount(),
                info.cardSettledThrough(),
                info.recordOnlyAmount(),
                info.moneyLocked(),
                info.replaceable(),
                info.isFixed(),
                info.deletable()
            );
        }
    }

    @Schema(name = "ExpenseListResponse")
    public record ListResponse(
        List<Response> expenses
    ) {
        public static ListResponse from(List<ExpenseServiceDto.ExpenseInfo> infos) {
            List<Response> responses = infos.stream()
                .map(Response::from)
                .toList();
            return new ListResponse(responses);
        }
    }

    public record DailySummaryResponse(
        LocalDate date,
        Long totalIncome,
        Long totalExpense
    ) {
        public static DailySummaryResponse from(ExpenseServiceDto.DailySummary summary) {
            return new DailySummaryResponse(
                summary.date(),
                summary.totalIncome(),
                summary.totalExpense()
            );
        }
    }

    public record RangeSummaryResponse(
        LocalDate startDate,
        LocalDate endDate,
        Long totalIncome,
        Long totalExpense,
        /** 합계 중 고정인 몫 — 변동 = 합계 − 고정. */
        Long fixedIncome,
        Long fixedExpense,
        List<CategoryBreakdownResponse> categoryBreakdown,
        List<RangeMonthlyBucketResponse> monthlyBuckets
    ) {
        public static RangeSummaryResponse from(ExpenseServiceDto.RangeSummary summary) {
            List<CategoryBreakdownResponse> breakdowns = summary.categoryBreakdown().stream()
                .map(CategoryBreakdownResponse::from)
                .toList();
            List<RangeMonthlyBucketResponse> buckets = summary.monthlyBuckets().stream()
                .map(RangeMonthlyBucketResponse::from)
                .toList();
            return new RangeSummaryResponse(
                summary.startDate(),
                summary.endDate(),
                summary.totalIncome(),
                summary.totalExpense(),
                summary.fixedIncome(),
                summary.fixedExpense(),
                breakdowns,
                buckets
            );
        }
    }

    public record RangeMonthlyBucketResponse(
        Integer year,
        Integer month,
        Long totalIncome,
        Long totalExpense,
        Long fixedIncome,
        Long fixedExpense,
        // 그 달의 카테고리별 지출(EXPENSE만, split-aware) — 카테고리 월별 추이 차트용
        List<CategoryAmountResponse> categoryExpenses
    ) {
        public static RangeMonthlyBucketResponse from(ExpenseServiceDto.RangeMonthlyBucket b) {
            return new RangeMonthlyBucketResponse(
                b.year(), b.month(), b.totalIncome(), b.totalExpense(), b.fixedIncome(), b.fixedExpense(),
                b.categoryExpenses().stream().map(CategoryAmountResponse::from).toList());
        }
    }

    public record CategoryAmountResponse(
        Long categoryRowId,
        Long amount,
        /** 그 금액 중 고정인 몫. */
        Long fixedAmount
    ) {
        public static CategoryAmountResponse from(ExpenseServiceDto.CategoryAmount c) {
            return new CategoryAmountResponse(c.categoryRowId(), c.amount(), c.fixedAmount());
        }
    }

    public record MonthlyTrendResponse(
        Integer year,
        Integer month,
        Long totalIncome,
        Long totalExpense,
        Long fixedIncome,
        Long fixedExpense
    ) {
        public static MonthlyTrendResponse from(ExpenseServiceDto.MonthlyTrend t) {
            return new MonthlyTrendResponse(t.year(), t.month(), t.totalIncome(), t.totalExpense(),
                t.fixedIncome(), t.fixedExpense());
        }
    }

    public record MonthlyTrendListResponse(List<MonthlyTrendResponse> trends) {
        public static MonthlyTrendListResponse from(List<ExpenseServiceDto.MonthlyTrend> trends) {
            return new MonthlyTrendListResponse(trends.stream().map(MonthlyTrendResponse::from).toList());
        }
    }

    public record CategoryBreakdownResponse(
        Long categoryRowId,
        String categoryName,
        Long parentCategoryRowId,
        String parentCategoryName,
        ExpenseType expenseType,
        Long totalAmount,
        /** 그 금액 중 고정인 몫. */
        Long fixedAmount
    ) {
        public static CategoryBreakdownResponse from(ExpenseServiceDto.CategoryBreakdown breakdown) {
            return new CategoryBreakdownResponse(
                breakdown.categoryRowId(),
                breakdown.categoryName(),
                breakdown.parentCategoryRowId(),
                breakdown.parentCategoryName(),
                breakdown.expenseType(),
                breakdown.totalAmount(),
                breakdown.fixedAmount()
            );
        }
    }

    public record MerchantSummaryResponse(String merchant, Long totalAmount, Integer count) {
        public static MerchantSummaryResponse from(ExpenseServiceDto.MerchantSummary s) {
            return new MerchantSummaryResponse(s.merchant(), s.totalAmount(), s.count());
        }
    }

    public record MerchantSummaryListResponse(List<MerchantSummaryResponse> merchants) {
        public static MerchantSummaryListResponse from(List<ExpenseServiceDto.MerchantSummary> list) {
            return new MerchantSummaryListResponse(list.stream().map(MerchantSummaryResponse::from).toList());
        }
    }

    @Schema(name = "ExpenseAssetSummaryResponse")
    public record AssetSummaryResponse(Long assetRowId, String assetName, Long totalAmount, Integer count) {
        public static AssetSummaryResponse from(ExpenseServiceDto.AssetSummary s) {
            return new AssetSummaryResponse(s.assetRowId(), s.assetName(), s.totalAmount(), s.count());
        }
    }

    public record AssetSummaryListResponse(List<AssetSummaryResponse> assets) {
        public static AssetSummaryListResponse from(List<ExpenseServiceDto.AssetSummary> list) {
            return new AssetSummaryListResponse(list.stream().map(AssetSummaryResponse::from).toList());
        }
    }

    public record HeatmapCellResponse(
        Integer dayOfWeek,
        Integer hour,
        Long totalAmount
    ) {
        public static HeatmapCellResponse from(ExpenseServiceDto.HeatmapCell cell) {
            return new HeatmapCellResponse(cell.dayOfWeek(), cell.hour(), cell.totalAmount());
        }
    }

    public record HeatmapResponse(List<HeatmapCellResponse> cells) {
        public static HeatmapResponse from(List<ExpenseServiceDto.HeatmapCell> cells) {
            return new HeatmapResponse(cells.stream().map(HeatmapCellResponse::from).toList());
        }
    }

    /**
     * 삭제 응답 — 결제계좌로 돌려준 금액(없으면 null).
     *
     * <p>종전엔 {@code ApiResponse<Void>} 였다. 결제 완료 회차의 카드 거래를 지우면 돈이
     * 실제로 움직이는데 화면이 그걸 알 방법이 없었다(설계 13-1의 사후 토스트).
     */
    public record DeleteResponse(Long refundedAmount) {
        public static DeleteResponse of(Long refundedAmount) {
            return new DeleteResponse(refundedAmount);
        }
    }

    /**
     * 환급 미리보기 응답 — 삭제·수정 확인창이 금액을 예고할 재료(설계 13-1).
     *
     * <p>{@code applies=false} 면 돌려줄 돈이 없다. {@code reason} 으로 화면이 문구를 고른다:
     * {@code OK}(금액 줄) · {@code ALREADY_REFUNDED}("이미 환급된 거래") · 나머지(줄 없음).
     */
    /**
     * 카드 정산 미리보기.
     *
     * <p>{@code reason}: OK · RECORD_ONLY_OK(기록용 몫이 기한 안에 빠져 전액 돌아감) ·
     * REFUND_WINDOW_CLOSED(결제한 달이 지나 기록만 정리) · NO_PAYMENT_ASSET · NOT_PAID_CYCLE ·
     * ALREADY_REFUNDED · NOT_CARD.
     */
    @Schema(name = "ExpenseRefundPreviewResponse")
    public record RefundPreviewResponse(boolean applies, long refundAmount, String reason,
                                        /** 이번 저장으로 기록만 남는 금액 — 닫힌 회차(R2). */
                                        long newRecordAmount,
                                        /** 오늘이 결제일이라 결제계좌에서 추가로 빠질 금액(R3). */
                                        long sameDayExtraPayment) {
        public static RefundPreviewResponse from(ExpenseServiceDto.RefundPreviewInfo info) {
            return new RefundPreviewResponse(info.applies(), info.refundAmount(), info.reason(),
                info.newRecordAmount(), info.sameDayExtraPayment());
        }
    }

    /**
     * 고쳐 쓰기 본문(D13) — 새 거래 생성 본문과 같은 칸 + 선택 {@code splits}.
     *
     * <p>{@code splits} 가 없으면 옛 거래의 분할을 옮긴다(합이 새 금액과 안 맞으면 400).
     */
    public record ReplaceRequest(
        @NotNull(message = "카테고리를 골라주세요")
        Long categoryRowId,
        Long assetRowId,
        @NotNull(message = "거래 종류를 골라주세요")
        ExpenseType expenseType,
        @NotNull(message = "금액을 입력해주세요")
        @Max(value = MAX_AMOUNT, message = "금액은 100억원까지 입력할 수 있어요")
        Long amount,
        @Size(max = 500, message = "설명은 500자까지 입력할 수 있어요")
        String description,
        @NotBlank(message = "거래 일시를 입력해주세요")
        String expenseDate,
        @Size(max = 100, message = "거래처는 100자까지 입력할 수 있어요")
        String merchant,
        String paymentMethod,
        @Max(value = InstallmentLimits.MAX_MONTHS, message = "할부는 60개월까지 입력할 수 있어요")
        Integer installmentMonths,
        java.math.BigDecimal originalAmount,
        String originalCurrency,
        java.math.BigDecimal exchangeRate,
        Long calendarEventRowId,
        Long todoRowId,
        List<ExpenseSplitApiDto.SplitRequest> splits,
        /** 고정 수입·지출 — 안 보내면 옛 거래의 표식을 잇는다. */
        Boolean isFixed
    ) {}

    /** 고정 표식 일괄 변경 — 고른 거래를 한꺼번에 고정(true) 또는 변동(false) 으로. */
    public record BulkFixedRequest(
        @NotEmpty(message = "거래를 하나 이상 골라주세요")
        @Size(max = 500, message = "한 번에 500건까지 바꿀 수 있어요")
        List<Long> expenseIds,
        @NotNull(message = "고정 여부를 골라주세요")
        Boolean isFixed
    ) {}

    /** 일괄 삭제 요청 — 고른 거래 아이디. */
    public record BulkDeleteRequest(
        @NotEmpty(message = "거래를 하나 이상 골라주세요")
        @Size(max = 500, message = "한 번에 500건까지 지울 수 있어요")
        List<Long> expenseIds
    ) {}

    /** 일괄 삭제 결과 — 지운 건수와 결제계좌로 돌려준 금액 합(없으면 null). */
    public record BulkDeleteResponse(int deletedCount, Long refundedAmount) {}

    /** 일괄 변경 결과 — 실제로 바뀐 건수. */
    public record BulkFixedResponse(int updatedCount) {}

    /** 환불 마크 요청 — 환불일만 받는다. 비우면 지금. */
    public record RefundRequest(
        /** 환불 처리 시각 (ISO, 예 2026-09-18T10:00:00). 비우면 서버의 지금. */
        String refundedAt
    ) {}
}
