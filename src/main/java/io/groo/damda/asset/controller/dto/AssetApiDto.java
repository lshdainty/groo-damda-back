package io.groo.damda.asset.controller.dto;

import java.math.BigDecimal;
import io.groo.damda.asset.type.HoldingType;
import io.groo.core.type.YNType;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.common.validation.AmountLimits;
import io.groo.damda.common.validation.ColorFormat;
import io.groo.damda.common.validation.FieldLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class AssetApiDto {

    /*
     * 잔액·한도 상한은 AmountLimits.MAX_BALANCE(1,000억원)다. 거래(100억)와 자리수가 다른 건
     * 의도다 — 잔액은 예금·대출 원금이라 거래 한 건보다 크다. 종전엔 상한이 없어 99조가 그대로
     * 저장됐다(QA 2026-09-03 #17).
     *
     * 하한을 −1,000억으로 두는 이유: 부호는 AssetSignPolicy 가 정하므로 여기서는 크기만 본다.
     * 마이너스 통장·대출이 음수로 들어오는 걸 400 으로 막으면 안 된다.
     *
     * 별칭(assetName)은 FieldLimits.ALIAS_MAX(30자)다. 컬럼은 varchar(100) 이라 DB 는 안 막아
     * 웹·앱 입력칸에만 있던 상한이었고, API 를 직접 부르면 31자가 그대로 저장됐다
     * (QA 2026-09-03 #65).
     */

    // === Asset ===
    public record CreateAssetRequest(
        @Size(max = FieldLimits.ALIAS_MAX, message = "별칭은 30자까지 입력할 수 있어요")
        String assetName,
        AssetType assetType,
        @Min(value = -AmountLimits.MAX_BALANCE, message = "잔액은 1,000억원까지 입력할 수 있어요")
        @Max(value = AmountLimits.MAX_BALANCE, message = "잔액은 1,000억원까지 입력할 수 있어요")
        Long balance,
        /**
         * 마이너스 통장 여부 — true 면 잔액을 <b>음수로</b> 저장한다(새 AssetType 없이
         * {@code BANK_ACCOUNT} + 음수 잔액). 화면은 "사용 중인 금액" 을 양수로 받고
         * 부호는 서버가 씌운다.
         *
         * <p>안 보내면(옛 클라이언트) 보낸 부호를 그대로 존중한다 — 여기서 abs() 를 강제하면
         * 옛 앱이 마이너스 통장을 열어 저장만 해도 부호가 뒤집힌다.
         */
        Boolean isOverdraft,
        String currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. */
        java.math.BigDecimal exchangeRate,
        @Pattern(regexp = ColorFormat.HEX_RGB, message = ColorFormat.MESSAGE)
        String color,
        String institution,
        String memo,
        Integer sortOrder,
        YNType isIncludedInTotal,
        /** 이 자산의 금액만 가린다. 안 보내면 가리지 않는다(N). */
        YNType isAmountHidden,
        Long cardCatalogRowId,
        /** 신용카드 한도 겸 마이너스 통장 약정 한도 — 같은 컬럼을 쓴다(한도 게이지는 카드에서만 그린다). */
        @Min(value = 0, message = "한도는 0원 이상이어야 해요")
        @Max(value = AmountLimits.MAX_BALANCE, message = "한도는 1,000억원까지 입력할 수 있어요")
        Long creditLimit,
        Integer paymentDay,
        Long paymentAssetRowId,
        // 투자 보유 목록 (INVESTMENT 전용)
        List<HoldingRequest> holdings,
        /**
         * 신용카드: 사용액 중 <b>결제를 기다리던 지난달 청구분</b>(양수) — 다가오는 결제일에 청구된다.
         * {@code balance} 는 그 뒤 쓴 금액이다. 결제 대기 청구분이 없을 때 0 보다 크면 400(AST_035).
         * 안 보내면(옛 클라이언트) 없음.
         */
        @Min(value = 0, message = "결제될 금액은 0원 이상이어야 해요")
        @Max(value = AmountLimits.MAX_BALANCE, message = "결제될 금액은 1,000억원까지 입력할 수 있어요")
        Long dueCarryoverAmount
    ) {}

    /**
     * 수정 본문 — <b>실린 칸만 바꾼다</b>(QA #96, 사용자 결정 2026-09-07).
     *
     * <p>{@code Optional} 참조가 {@code null} 이면 키가 없었던 것(유지),
     * {@code Optional.empty()} 면 {@code null} 이 실린 것(지움)이다
     * ({@code AbsentAwareOptionalModule}). 종전엔 안 보낸 {@code institution}·{@code memo}·
     * {@code color} 가 null 로 덮여 사라졌다.
     *
     * <p>NOT NULL 칸({@code assetName}·{@code assetType}·{@code currency}·
     * {@code isIncludedInTotal})은 "지운다" 가 성립하지 않아 명시적 {@code null} 을 400 으로 끊는다.
     * 안 보내면 지금 값을 지킨다(종전과 같다).
     *
     * <p>{@code balance} 는 지우는 칸이 아니다 — 잔액은 이력에서 집계하고 이 값은 "앵커를 찍어
     * 달라" 는 요청이다. 안 보내거나 {@code null} 이면 앵커를 안 찍는다(종전과 같다).
     *
     * <p>{@code holdings} 만 {@code Optional} 이 아니다 — 종전부터 "{@code null}=무변경,
     * 리스트=전체 교체" 였고 그 계약을 그대로 둔다.
     */
    public record UpdateAssetRequest(
        Optional<@NotBlank(message = "자산 이름을 입력해주세요")
                 @Size(max = FieldLimits.ALIAS_MAX, message = "별칭은 30자까지 입력할 수 있어요")
                 String> assetName,
        Optional<@NotNull(message = "자산 종류를 골라주세요") AssetType> assetType,
        Optional<@Min(value = -AmountLimits.MAX_BALANCE, message = "잔액은 1,000억원까지 입력할 수 있어요")
                 @Max(value = AmountLimits.MAX_BALANCE, message = "잔액은 1,000억원까지 입력할 수 있어요")
                 Long> balance,
        /**
         * 마이너스 통장 여부 — true 면 잔액을 <b>음수로</b> 저장한다(새 AssetType 없이
         * {@code BANK_ACCOUNT} + 음수 잔액). 화면은 "사용 중인 금액" 을 양수로 받고
         * 부호는 서버가 씌운다.
         *
         * <p>안 보내면(옛 클라이언트) 보낸 부호를 그대로 존중한다 — 여기서 abs() 를 강제하면
         * 옛 앱이 마이너스 통장을 열어 저장만 해도 부호가 뒤집힌다.
         */
        Optional<Boolean> isOverdraft,
        Optional<@NotBlank(message = "통화를 골라주세요") String> currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. */
        Optional<java.math.BigDecimal> exchangeRate,
        Optional<@Pattern(regexp = ColorFormat.HEX_RGB, message = ColorFormat.MESSAGE)
                 String> color,
        Optional<String> institution,
        Optional<String> memo,
        Optional<@NotNull(message = "합계에 포함할지 골라주세요") YNType> isIncludedInTotal,
        /** 키가 없으면 유지, 명시적 {@code null} 은 400 — 위와 같은 규칙. */
        Optional<@NotNull(message = "금액을 가릴지 골라주세요") YNType> isAmountHidden,
        Optional<Long> cardCatalogRowId,
        /** 신용카드 한도 겸 마이너스 통장 약정 한도 — 같은 컬럼을 쓴다(한도 게이지는 카드에서만 그린다). */
        Optional<@Min(value = 0, message = "한도는 0원 이상이어야 해요")
                 @Max(value = AmountLimits.MAX_BALANCE, message = "한도는 1,000억원까지 입력할 수 있어요")
                 Long> creditLimit,
        Optional<Integer> paymentDay,
        Optional<Long> paymentAssetRowId,
        // 투자 보유 목록 — null=무변경, 리스트=전체 교체
        List<HoldingRequest> holdings,
        /**
         * 신용카드의 이월 금액("이전 미결제 사용액", D7) — 키가 없으면 유지. 신용카드는 {@code balance} 를
         * 무시한다(옛 앱이 보내는 지금 잔액이 이월 거래를 덮어쓰던 결함). 결제가 끝난 회차의 이월은 못 고친다(D15).
         */
        Optional<@Min(value = 0, message = "이월 금액은 0원 이상이어야 해요")
                 @Max(value = AmountLimits.MAX_BALANCE, message = "이월 금액은 1,000억원까지 입력할 수 있어요")
                 Long> carryoverAmount,
        /**
         * 신용카드: 결제를 기다리던 지난달 청구분 — 키가 없으면 유지, 0 이면 지운다. 카드를 등록한 달의
         * 그 회차 결제일 전까지만 넣고 고친다(지나면 AST_034 · 없던 칸을 새로 넣으면 AST_035).
         */
        Optional<@Min(value = 0, message = "결제될 금액은 0원 이상이어야 해요")
                 @Max(value = AmountLimits.MAX_BALANCE, message = "결제될 금액은 1,000억원까지 입력할 수 있어요")
                 Long> dueCarryoverAmount
    ) {}

    /**
     * 투자 보유 입력 — linked=true: tossSymbol+quantity / false: holdingName+holdingValue(quantity 선택).
     * holdingType 미지정은 STOCK 으로 본다(구버전 클라이언트 하위호환).
     */
    public record HoldingRequest(
        /** 기존 보유 행 아이디 — 보내면 제자리 수정돼 원가와 매매 이력 연결이 유지된다. */
        Long rowId,
        HoldingType holdingType,
        Boolean linked,
        /**
         * stock_master 기준 시장코드 — <b>선택</b>. 종목 검색 응답이 준 값을 그대로 돌려주면 된다.
         *
         * <p>안 보내도 저장은 된다(구버전 클라이언트가 그렇다). 그때는 서버가 심볼로 한 번
         * 해석하고, 같은 심볼이 여러 시장에 걸리면 비워 둔 채 남긴다 — 추측한 값을 눌러 두면
         * 다시 물을 기회가 사라진다.
         */
        String marketCode,
        String tossSymbol,
        BigDecimal quantity,
        String holdingName,
        Long holdingValue,
        /** 총 매수원가 (원화). 실현손익의 기준 — 미지정이면 0 으로 시작한다. */
        Long totalCost,
        /**
         * 목록에서의 자리 — <b>선택</b>. 안 보내면 <b>보낸 배열의 인덱스</b>가 자리가 된다.
         *
         * <p>종전엔 이 필드가 아예 없어 실어 보내도 Jackson 이 조용히 버렸다(QA 2026-09-07 #91).
         * 지금은 보내온 값이 이긴다.
         *
         * <p><b>기본값이 0 이 아니라 배열 인덱스인 이유.</b> 보유는 순서 변경 API 가 따로 없고
         * 목록을 통째로 실어 보내는 것이 곧 순서 지정이다. 안 보낸 줄을 전부 0 으로 두면 여러 줄이
         * 같은 값이 되어 정렬이 {@code rowId asc} 로 떨어지고, 줄을 끌어 옮긴 뒤 저장한 순서가
         * 조용히 사라진다. 앱은 이 필드를 <b>일부러 안 싣고</b> 배열 순서에 기댄다
         * ({@code asset_repository.dart} 의 {@code _holdingBody}) — 그 계약을 그대로 지킨다.
         */
        Integer sortOrder
    ) {
        public AssetServiceDto.HoldingCommand toCommand() {
            return new AssetServiceDto.HoldingCommand(
                rowId, holdingType, linked, marketCode, tossSymbol, quantity,
                holdingName, holdingValue, totalCost, sortOrder);
        }

        public static List<AssetServiceDto.HoldingCommand> toCommands(List<HoldingRequest> requests) {
            return requests == null ? null : requests.stream().map(HoldingRequest::toCommand).toList();
        }
    }

    public record HoldingResponse(
        Long rowId,
        HoldingType holdingType,
        boolean linked,
        /** 저장된 시장코드. 확정 못 한 행은 null — 편집 폼은 그대로 돌려보내면 된다. */
        String marketCode,
        String tossSymbol,
        /** 소수 정밀도 보존 — JS number 로 내려가면 코인 0.00012345 같은 값이 흔들린다. */
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        @Schema(type = "string", format = "decimal", example = "0.00012345")
        BigDecimal quantity,
        String holdingName,
        Long holdingValue,
        /** 총 매수원가 (원화, 수수료 포함). 평가액과의 차이가 평가손익이다. */
        Long totalCost,
        /** 평단가 — 총원가 / 수량. */
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
        @Schema(type = "string", format = "decimal", example = "72150.5")
        BigDecimal avgPrice,
        Integer sortOrder
    ) {
        public static HoldingResponse from(AssetServiceDto.HoldingInfo h) {
            return new HoldingResponse(
                h.rowId(), h.holdingType(), h.linked(), h.marketCode(), h.symbol(), h.quantity(),
                h.holdingName(), h.holdingValue(), h.totalCost(), h.avgPrice(), h.sortOrder());
        }
    }

    public record CardCatalogBriefResponse(
        Long rowId,
        String cardName,
        String imgUrl,
        String companyName,
        String companyLogoUrl
    ) {
        public static CardCatalogBriefResponse from(AssetServiceDto.CardCatalogBrief b) {
            if (b == null) return null;
            return new CardCatalogBriefResponse(b.rowId(), b.cardName(), b.imgUrl(), b.companyName(), b.companyLogoUrl());
        }
    }

    public record AssetResponse(
        Long rowId,
        Long userRowId,
        String assetName,
        AssetType assetType,
        Long balance,
        /** 예수금·현금 잔액 (투자 자산의 매수 대기 자금). balance = cashBalance + holdingBalance */
        Long cashBalance,
        /** 보유 종목 평가금액. 보유가 없으면 0. */
        Long holdingBalance,
        
        String currency,
        /** 원화 환산율 (통화 1단위당 원화). KRW 는 1. */
        java.math.BigDecimal exchangeRate,
        String color,
        String institution,
        String memo,
        Integer sortOrder,
        YNType isIncludedInTotal,
        YNType isAmountHidden,
        CardCatalogBriefResponse cardCatalog,
        Long creditLimit,
        Integer paymentDay,
        Long paymentAssetRowId,
        /** 레거시 단일 연동의 시장코드. 확정 못 했으면 null. */
        String marketCode,
        String tossSymbol,
        Long tossQuantity,
        // 투자 보유 목록 (INVESTMENT 외/보유 없음 = 빈 배열)
        List<HoldingResponse> holdings,
        LocalDateTime createAt,
        LocalDateTime modifyAt,
        /** 체크카드의 이번 달(1일~) 사용 합계. 체크카드만 값, 그 외 null — 잔액이 항상 0 이라 화면 표시용. */
        Long monthlyUsedAmount,
        /** 신용카드만: 이월 금액("이전 미결제 사용액"), 없으면 0. 그 외 null. */
        Long carryoverAmount,
        /** 신용카드: 이월 거래가 든 회차의 결제일이 됐으면 true — 이월 금액 칸 읽기 전용(D15). */
        boolean carryoverLocked,
        /** 결제일 있는 신용카드: 이 날짜 이하 거래는 닫힌 회차. 그 외 null. */
        java.time.LocalDate cardClosedThrough,
        /**
         * 결제일 있는 신용카드: 결제일이 오늘보다 뒤인 첫 회차의 실제 결제일(결제일 이력 반영). 그 회차는
         * 이 날짜의 전달 한 달, 그 뒤 회차는 {@code paymentDay} 로 결제된다. 그 외 null.
         */
        java.time.LocalDate nextPaymentDate,
        /**
         * 신용카드: 결제 대기 청구분 칸 — 청구분이 있거나, 등록한 달의 그 회차 결제일이 아직 안 왔으면
         * 값(폼이 칸을 그린다). 그 외 null.
         */
        DueCarryoverResponse dueCarryover,
        /**
         * 원화 환산 합계 = round(cashBalance × exchangeRate) + holdingBalance. 예수금만 자산 통화, 보유 평가는 원화.
         * 순자산·요약과 같은 식이다. 원화 자산은 balance 와 같다. (기존 칸은 그대로 — 더하기만 했다.)
         */
        Long balanceInKrw
    ) {
        public static AssetResponse from(AssetServiceDto.AssetInfo info) {
            return new AssetResponse(
                info.rowId(), info.userRowId(), info.assetName(), info.assetType(),
                info.balance(), info.cashBalance(), info.holdingBalance(),
                info.currency(), info.exchangeRate(), info.color(),
                info.institution(), info.memo(), info.sortOrder(), info.isIncludedInTotal(),
                info.isAmountHidden(),
                CardCatalogBriefResponse.from(info.cardCatalog()),
                info.creditLimit(), info.paymentDay(), info.paymentAssetRowId(),
                info.marketCode(), info.symbol(), info.quantity(),
                info.holdings().stream().map(HoldingResponse::from).toList(),
                info.createAt(), info.modifyAt(),
                info.monthlyUsedAmount(),
                info.carryoverAmount(), info.carryoverLocked(), info.cardClosedThrough(),
                info.nextPaymentDate(),
                DueCarryoverResponse.from(info.dueCarryover()),
                info.balanceInKrw()
            );
        }
    }

    /**
     * 결제 대기 청구분 칸.
     *
     * @param amount      지금 청구분(없으면 0)
     * @param locked      그 회차 결제일이 됐다 — 읽기 전용
     * @param paymentDate 그 회차의 결제일 — 칸 이름에 쓴다("9월 25일에 결제될 금액")
     */
    public record DueCarryoverResponse(long amount, boolean locked, java.time.LocalDate paymentDate) {
        static DueCarryoverResponse from(AssetServiceDto.DueCarryover due) {
            return due == null ? null : new DueCarryoverResponse(due.amount(), due.locked(), due.paymentDate());
        }
    }


    public record AssetListResponse(List<AssetResponse> assets) {
        public static AssetListResponse from(List<AssetServiceDto.AssetInfo> infos) {
            return new AssetListResponse(infos.stream().map(AssetResponse::from).toList());
        }
    }

    @Schema(name = "AssetReorderRequest")
    public record ReorderRequest(List<ReorderItem> items) {}

    @Schema(name = "AssetReorderItem")
    public record ReorderItem(Long assetId, Integer sortOrder) {}

    @Schema(name = "AssetSummaryResponse")
    public record AssetSummaryResponse(
        Long totalBalance,
        Long totalAssets,
        Long totalDebt,
        Long netWorth,
        Long lastMonthNetWorth,
        Long changeAmount,
        Double changePercent,
        List<AssetTypeSummaryResponse> byType
    ) {
        public static AssetSummaryResponse from(AssetServiceDto.AssetSummary summary) {
            return new AssetSummaryResponse(
                summary.totalBalance(),
                summary.totalAssets(),
                summary.totalDebt(),
                summary.netWorth(),
                summary.lastMonthNetWorth(),
                summary.changeAmount(),
                summary.changePercent(),
                summary.byType().stream().map(AssetTypeSummaryResponse::from).toList()
            );
        }
    }

    public record AssetTypeSummaryResponse(AssetType assetType, Long totalBalance, Integer count) {
        public static AssetTypeSummaryResponse from(AssetServiceDto.AssetTypeSummary s) {
            return new AssetTypeSummaryResponse(s.assetType(), s.totalBalance(), s.count());
        }
    }

    public record NetWorthTrendPointResponse(Integer year, Integer month, Long netWorth) {
        public static NetWorthTrendPointResponse from(AssetServiceDto.NetWorthTrendPoint p) {
            return new NetWorthTrendPointResponse(p.year(), p.month(), p.netWorth());
        }
    }

    public record NetWorthTrendResponse(List<NetWorthTrendPointResponse> trend) {
        public static NetWorthTrendResponse from(List<AssetServiceDto.NetWorthTrendPoint> points) {
            return new NetWorthTrendResponse(points.stream().map(NetWorthTrendPointResponse::from).toList());
        }
    }

    /** balance = 예수금 + 보유(옛 뜻 그대로), balanceInKrw = 원화 합계(한 식). */
    public record AssetBalancePointResponse(java.time.LocalDate weekStart, Long balance, Long balanceInKrw) {
        public static AssetBalancePointResponse from(AssetServiceDto.AssetBalancePoint p) {
            return new AssetBalancePointResponse(p.weekStart(), p.balance(), p.balanceInKrw());
        }
    }

    public record AssetBalanceTrendResponse(List<AssetBalancePointResponse> trend) {
        public static AssetBalanceTrendResponse from(List<AssetServiceDto.AssetBalancePoint> points) {
            return new AssetBalanceTrendResponse(points.stream().map(AssetBalancePointResponse::from).toList());
        }
    }

    // === Asset Transfer ===

    /**
     * 이체는 <b>양쪽 자산이 다 있어야</b> 성립한다 — 하나라도 빠지면 종전엔 500 이었다
     * (QA 2026-09-07 #85). 검증이 두 자산을 조건 없이 조회하는데, QueryDSL 은 {@code eq(null)} 을
     * {@code IllegalArgumentException} 으로 거절하고 그것이 {@code @Repository} 프록시에서
     * {@code InvalidDataAccessApiUsageException} 으로 번역돼 매핑이 없는 채로 500 이 됐다(H2 로 재현).
     *
     * <p>이 record 는 생성과 수정이 함께 쓴다 — 수정도 두 자산으로 부수효과(이자 지출 · 잔액 이력)를
     * 다시 만들므로 요구가 같다. 웹({@code AssetTransferFormValues} 필수 필드)·앱
     * ({@code required int fromAssetRowId/toAssetRowId}) 모두 항상 둘을 보낸다
     * (2026-09-07 양쪽 코드로 확인).
     */
    public record CreateTransferRequest(
        @NotNull(message = "보내는 자산을 골라주세요")
        Long fromAssetRowId,
        @NotNull(message = "받는 자산을 골라주세요")
        Long toAssetRowId,
        Long amount,
        Long fee,
        /** 이자 (대출 상환 시). amount 중 이 금액은 부채를 줄이지 않고 지출로 잡힌다. */
        Long interestAmount,
        String description,
        /**
         * 이체 일시 — <b>문자열로 받아 컨트롤러가 파싱한다</b>({@link io.groo.damda.common.time.WallClockDateTimeParser}).
         *
         * <p>종전엔 {@code LocalDateTime} 이라 Jackson 이 {@code "2026-09-08"} 을 못 읽고 그 자리에서
         * 끊었다(400). 같은 화면의 거래({@code ExpenseApiDto.expenseDate})는 날짜만 보내도 받는데
         * 이체만 시각을 요구한 셈이다(QA #101) — 실제로 웹 이체 폼
         * ({@code AssetTransferForm} 의 {@code InputDatePicker})이 날짜만 보낸다.
         * 이제 날짜만 오면 그 날 00:00 로 읽는다.
         *
         * <p><b>응답({@link TransferResponse#transferDate()})은 {@code LocalDateTime} 그대로다</b> —
         * 클라이언트가 파싱해 쓰고 있고, 바꿀 이유가 요청 쪽에만 있다.
         *
         * <p><b>{@code @NotBlank} 는 일부러 안 단다.</b> 거래({@code expenseDate})는 달고 있지만
         * 여기는 종전에도 없던 제약이고(QA #85 가 이체에 건 {@code @NotNull} 은
         * {@code fromAssetRowId}·{@code toAssetRowId} 다), 빼먹은 요청은 이미
         * core 의 {@code GlobalExceptionHandler} 가 400 "요청에 빠진 값이 있어요" 로 받는다
         * ({@code transfer_date} 가 NOT NULL 이라 하이버네이트가 저장 전에 끊는다 — 실측 확인).
         * 형식만 고치는 자리에서 제약을 새로 걸면 <b>이 PR 이 무엇을 바꿨는지</b>가 흐려진다.
         */
        String transferDate
    ) {}

    public record TransferResponse(
        Long rowId,
        Long userRowId,
        Long fromAssetRowId,
        String fromAssetName,
        Long toAssetRowId,
        String toAssetName,
        Long amount,
        Long fee,
        /** 이자 (대출 상환 시). */
        Long interestAmount,
        /** 원금 = amount − interestAmount. */
        Long principalAmount,
        String description,
        /** 시스템이 만든 이체의 출처 (TRADE_SETTLEMENT). null 이면 사용자가 만든 이체 — 값이 있으면 수정·삭제가 잠긴다. */
        String autoSource,
        LocalDateTime transferDate,
        LocalDateTime createAt,
        /** 받는 쪽 자산 통화로 받은 금액. null 이면 amount 와 같은 값을 받았다(같은 통화 · 기존 이체). */
        Long toAmount,
        /** 지금 사용자가 직접 지울 수 있는가 — 손으로 만든 이체는 true, 자동 생성은 받는 증권 자산이 삭제된 충당 이체일 때만 true. */
        boolean deletable
    ) {
        public static TransferResponse from(AssetServiceDto.TransferInfo info) {
            return new TransferResponse(
                info.rowId(), info.userRowId(),
                info.fromAssetRowId(), info.fromAssetName(),
                info.toAssetRowId(), info.toAssetName(),
                info.amount(), info.fee(), info.interestAmount(), info.principalAmount(), info.description(),
                info.autoSource(),
                info.transferDate(), info.createAt(),
                info.toAmount(),
                info.deletable()
            );
        }
    }

    public record TransferListResponse(List<TransferResponse> transfers) {
        public static TransferListResponse from(List<AssetServiceDto.TransferInfo> infos) {
            return new TransferListResponse(infos.stream().map(TransferResponse::from).toList());
        }
    }

    /** 잔액 재산정 결과 — 다시 계산한 자산 수. */
}
