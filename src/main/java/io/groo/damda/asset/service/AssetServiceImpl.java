package io.groo.damda.asset.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetHolding;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetHoldingRepository;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.repository.AssetTransferRepository;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetSignPolicy;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.asset.type.HoldingType;
import io.groo.damda.card.domain.CardCatalog;
import io.groo.damda.card.domain.CardBilling;
import io.groo.damda.card.repository.CardBillingRepository;
import io.groo.damda.card.repository.CardCatalogRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.subscription.service.SubscriptionEntitlementService;
import io.groo.damda.securities.service.SecuritiesCredentialService;
import io.groo.damda.securities.service.SecuritiesPriceProvider;
import io.groo.damda.securities.service.SecuritiesPriceProviders;
import io.groo.damda.securities.service.dto.InstrumentRef;
import io.groo.damda.securities.service.dto.PriceQuote;
import io.groo.damda.stock.service.StockMasterResolver;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.ExpenseAggregates;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.expense.type.ExpenseType;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import io.groo.core.time.UserClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AssetServiceImpl implements AssetService {
    private static final String FEATURE_SECURITIES = "SECURITIES";

    private final AssetRepository assetRepository;
    private final AssetHoldingRepository assetHoldingRepository;
    private final AssetTransferRepository assetTransferRepository;
    private final UserRepository userRepository;
    private final CardCatalogRepository cardCatalogRepository;
    // 청구 회차 정합용 — 카드 서비스가 아니라 리포지토리만 참조한다(서비스끼리는 순환).
    private final CardBillingRepository cardBillingRepository;
    private final ExpenseRepository expenseRepository;
    private final AssetBalanceHistoryService balanceHistoryService;
    private final UserClock userClock;
    private final SubscriptionEntitlementService entitlementService;
    private final SecuritiesCredentialService securitiesCredentialService;
    private final SecuritiesPriceProviders priceProviders;
    // 저장할 시장코드 확정용 — 조회 규칙과 달리 모호하면 비워 둔다(confirmMarketCode).
    private final StockMasterResolver stockMasterResolver;
    // 신용카드 회차별 결제일(D5) — 결제일 변경은 다음 회차부터, 닫힌 회차 판정도 이것으로.
    private final io.groo.damda.card.service.PaymentScheduleService paymentScheduleService;
    // 닫힌 회차 판정의 "오늘" — 자정 배치와 같은 서울 시계(D11).
    private final io.groo.core.time.ServiceClock serviceClock;

    @Override
    @Transactional
    public AssetServiceDto.AssetInfo createAsset(AssetServiceDto.CreateAssetCommand command) {
        log.debug("자산 등록 시작: userRowId={}, assetName={}", command.userRowId(), command.assetName());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        validateHoldings(command.assetType(), command.holdings());
        // 저장 전에 한 번 다듬는다 — 종목코드는 대문자·앞뒤공백 제거, 항목명은 trim,
        // 그리고 같은 종목이 두 줄로 온 요청은 여기서 한 줄로 접힌다.
        List<AssetServiceDto.HoldingCommand> holdingCommands = normalizeHoldings(command.holdings());

        // 신용카드는 결제일이 있어야 회차가 선다(D8) — 없으면 닫힌 회차 판정·결제 배치가 전부 비껴간다.
        if (command.assetType() == AssetType.CREDIT_CARD && command.paymentDay() == null) {
            throw new InvalidValueException(DeskErrorCode.ASSET_CARD_PAYMENT_DAY_REQUIRED);
        }
        validateCurrency(command.assetType(), command.currency(), command.exchangeRate());

        CardCatalog cardCatalog = resolveCardCatalog(command.cardCatalogRowId());
        Asset paymentAsset = resolvePaymentAssetOrNone(command.paymentAssetRowId(), command.userRowId());

        // 보유가 있는 투자 자산은 평가액을 서버가 산정한다 — 클라이언트가 보낸 balance 를 쓰지 않는다.
        // 연동 시세를 못 구하면 미연동 합만 잡고, 나머지는 일 1회 스냅샷 배치가 채운다.
        //
        // 평가액은 예수금(balance)이 아니라 HOLDING 채널 앵커로 따로 찍는다. 한 칸에 담으면
        // 다음 평가 갱신이 그 사이 들어온 이체를 덮어써 돈이 사라진다.
        // 부호는 사용자가 아니라 종류가 정한다(QA #19) — 부채군은 음수, 마이너스 통장도 음수,
        // 나머지 자산군은 양수. isOverdraft 를 안 보내는 옛 클라이언트에게는 보낸 부호를
        // 그대로 존중한다(AssetSignPolicy.normalizeBalance 참조).
        // asset.initial_balance 에도 이 값이 그대로 들어가 recordInit 의 앵커와 어긋나지 않는다.
        Long balance = command.balance() == null ? null
            : AssetSignPolicy.normalizeBalance(command.assetType(), command.isOverdraft(), command.balance());
        Long holdingValuation = null;
        if (command.assetType() == AssetType.INVESTMENT
            && holdingCommands != null && !holdingCommands.isEmpty()) {
            Long computed = computeInvestmentBalance(command.userRowId(), holdingCommands);
            holdingValuation = computed != null ? computed : manualHoldingsSum(holdingCommands);
            balance = 0L; // 예수금은 비어서 시작 — 증권계좌에 넣은 돈은 이체로 들어온다
        }

        Asset asset = Asset.createAsset(
            user,
            command.assetName(),
            command.assetType(),
            balance,
            command.currency() != null ? command.currency() : "KRW",
            command.exchangeRate(),
            command.color(),
            command.institution(),
            command.memo(),
            command.sortOrder() != null ? command.sortOrder() : 0,
            command.isIncludedInTotal(),
            cardCatalog,
            command.creditLimit(),
            command.paymentDay(),
            paymentAsset
        );
        // 인자로 안 받는 칸 — 15개짜리 생성자를 더 늘리지 않으려는 것이다(Asset 주석).
        asset.updateAmountHidden(command.isAmountHidden());

        assetRepository.save(asset);
        // 신용카드는 처음부터 이 결제일 — 나중에 바꾸면 그 뒤 회차부터만 바뀐다(D5).
        paymentScheduleService.initialize(asset);
        List<AssetServiceDto.HoldingInfo> holdings = saveHoldings(asset, holdingCommands);
        // 잔액 이력: 초기 예수금 절대 앵커 + (보유가 있으면) 평가금액 앵커
        LocalDateTime createdAt = userClock.now(command.userRowId());
        balanceHistoryService.recordInit(asset, createdAt);
        if (holdingValuation != null) {
            balanceHistoryService.recordValuation(asset, holdingValuation, createdAt);
        }
        // 신용카드의 사용액은 앵커가 아니라 **거래 한 건**이다(D4). 그래야 한도 사용과
        // 청구가 같은 재료에서 나오고, 이 거래가 든 회차에 자연히 청구된다.
        syncCardCarryover(asset, balance, createdAt);
        // 결제를 기다리던 지난달 청구분은 그 회차(지난달 말)에 따로 둔다 — 다가오는 결제일에 청구된다.
        syncDueCarryover(asset, command.dueCarryoverAmount(), userClock.today(command.userRowId()));
        log.info("자산 등록 완료: assetId={}, userRowId={}", asset.getRowId(), command.userRowId());

        return withCardState(AssetServiceDto.AssetInfo.from(asset, holdings,
            balanceHistoryService.balanceAt(asset, userClock.now(command.userRowId()))), asset);
    }

    /**
     * 신용카드 상태를 응답에 붙인다 — 이월 금액(D7), 이월 칸 잠금(D15), 닫힌 회차의 끝(D5·D12).
     *
     * <p>클라이언트가 회차를 직접 세지 않게 서버가 내린다 — 결제일을 바꾼 카드는 회차마다 결제일이
     * 달라 화면이 지금 결제일로 세면 틀린다.
     */
    private AssetServiceDto.AssetInfo withCardState(AssetServiceDto.AssetInfo info, Asset asset) {
        if (asset.getAssetType() != AssetType.CREDIT_CARD) {
            return info;
        }
        java.time.LocalDate today = serviceClock.today();
        io.groo.damda.card.service.PaymentSchedule schedule = paymentScheduleService.scheduleOf(asset);
        Expense carryover = expenseRepository
            .findActiveByAssetAndAutoSource(asset.getRowId(), Expense.AUTO_SOURCE_CARD_CARRYOVER)
            .orElse(null);
        return info.withCardState(
            carryover != null ? carryover.getAmount() : 0L,
            carryoverLocked(carryover, schedule, today),
            schedule != null ? schedule.closedThrough(today) : null,
            schedule != null ? schedule.firstOpenPaymentDate(today) : null,
            dueCarryoverState(asset, schedule, today));
    }

    /**
     * 결제 대기 청구분 칸 — 청구분이 있으면 그 회차로, 없으면 등록한 달의 그 회차가 아직 열려 있을 때만.
     * 둘 다 아니면 null(폼이 칸을 안 그린다).
     */
    private AssetServiceDto.DueCarryover dueCarryoverState(
            Asset asset, io.groo.damda.card.service.PaymentSchedule schedule, LocalDate today) {
        if (schedule == null || !schedule.hasDay()) {
            return null;
        }
        Expense due = expenseRepository
            .findActiveByAssetAndAutoSource(asset.getRowId(), Expense.AUTO_SOURCE_CARD_CARRYOVER_DUE)
            .orElse(null);
        LocalDate dueCycle = due != null && due.getExpenseDate() != null
            ? io.groo.damda.card.service.CardCycleMath.cycleStartOf(due.getExpenseDate().toLocalDate())
            : dueCycleFor(registeredDate(asset), schedule);
        if (dueCycle == null) {
            return null;
        }
        boolean closed = schedule.isClosed(dueCycle, today);
        if (due == null && closed) {
            return null;
        }
        return new AssetServiceDto.DueCarryover(
            due != null ? due.getAmount() : 0L, closed, schedule.paymentDateOf(dueCycle));
    }

    /** 카드를 등록한 날(사용자 달력) — 생성 시각은 UTC 로 저장된다. 모르면 null. */
    private LocalDate registeredDate(Asset asset) {
        if (asset.getCreateAt() == null || asset.getUser() == null) {
            return null;
        }
        return asset.getCreateAt().atZone(java.time.ZoneOffset.UTC)
            .withZoneSameInstant(userClock.zoneOf(asset.getUser().getRowId()))
            .toLocalDate();
    }

    /**
     * 등록한 날 결제를 기다리던 회차 — 등록한 달의 전달 회차이고, 그 결제일이 등록한 날보다 뒤일 때만.
     * 결제일이 지났거나 당일이면 그 회차는 이미 결제됐다(D2 — 결제일 당일 닫힘) → null.
     */
    private static LocalDate dueCycleFor(LocalDate registered,
                                         io.groo.damda.card.service.PaymentSchedule schedule) {
        if (registered == null || schedule == null || !schedule.hasDay()) {
            return null;
        }
        LocalDate cycle = registered.withDayOfMonth(1).minusMonths(1);
        LocalDate pay = schedule.paymentDateOf(cycle);
        return pay != null && pay.isAfter(registered) ? cycle : null;
    }

    /** 이월 거래가 든 회차의 결제일이 됐는가 — 됐으면 그 금액은 이미 결제에 들어갔다(D15). */
    private static boolean carryoverLocked(Expense carryover,
                                           io.groo.damda.card.service.PaymentSchedule schedule,
                                           java.time.LocalDate today) {
        return carryover != null && schedule != null && carryover.getExpenseDate() != null
            && schedule.isClosed(
                io.groo.damda.card.service.CardCycleMath.cycleStartOf(carryover.getExpenseDate().toLocalDate()),
                today);
    }

    @Override
    public List<AssetServiceDto.AssetInfo> getAssets(Long userRowId) {
        log.debug("자산 목록 조회: userRowId={}", userRowId);

        List<Asset> assets = assetRepository.findByUser(userRowId);
        if (assets.isEmpty()) {
            return List.of();
        }
        // 보유는 in-query 1회 일괄 로딩 — 자산별 N+1 금지.
        List<Long> ids = assets.stream().map(Asset::getRowId).toList();
        Map<Long, List<AssetServiceDto.HoldingInfo>> holdingsByAsset =
            assetHoldingRepository.findActiveByAssets(ids).stream()
                .collect(Collectors.groupingBy(h -> h.getAsset().getRowId(),
                    Collectors.mapping(AssetServiceDto.HoldingInfo::from, Collectors.toList())));

        // 잔액은 캐시 컬럼이 아니라 이력에서 산정한다 — 요약·순자산·추이가 쓰는 것과 같은 계산이라
        // 목록 금액과 총합이 어긋날 여지가 없다(캐시가 낡아 있어도 스스로 맞춰진다).
        LocalDateTime now = userClock.now(userRowId);
        Map<Long, AssetBalanceHistoryService.Split> balances =
            balanceHistoryService.balancesAt(assets, now);
        Map<Long, Long> checkCardUsed = checkCardMonthlyUsed(assets, userRowId, now);

        return assets.stream()
            .map(a -> withCardState(AssetServiceDto.AssetInfo.from(
                    a,
                    holdingsByAsset.getOrDefault(a.getRowId(), List.of()),
                    balances.getOrDefault(a.getRowId(), AssetBalanceHistoryService.Split.ZERO))
                .withMonthlyUsedAmount(checkCardUsed.get(a.getRowId())), a))
            .toList();
    }

    /**
     * 체크카드별 이번 달(1일~말일 캘린더 월) 사용 합계.
     *
     * <p>체크카드는 결제가 연결 계좌에서 즉시 빠져 잔액이 항상 0 이다 — 카드 화면에
     * 보여줄 금액은 "이번 달 이 카드로 얼마 썼나" 뿐이고, 청구 사이클이 없으니 기간은
     * 달력 그대로가 자연스럽다. 합산은 {@link ExpenseAggregates} 규칙(예정 제외·환불 상계)을
     * 그대로 따른다. 이번 달 거래 1회 조회 후 자바에서 나눠 담는다 — 카드별 쿼리 금지.
     */
    private Map<Long, Long> checkCardMonthlyUsed(List<Asset> assets, Long userRowId, LocalDateTime now) {
        Set<Long> checkCardIds = assets.stream()
            .filter(a -> a.getAssetType() == AssetType.CHECK_CARD)
            .map(Asset::getRowId)
            .collect(Collectors.toSet());
        if (checkCardIds.isEmpty()) {
            return Map.of();
        }
        LocalDate monthStart = now.toLocalDate().withDayOfMonth(1);
        LocalDate monthEnd = now.toLocalDate().withDayOfMonth(now.toLocalDate().lengthOfMonth());
        List<Expense> countable = ExpenseAggregates.countable(
            expenseRepository.findByDateRange(userRowId, monthStart, monthEnd), now);

        Map<Long, Long> used = new HashMap<>();
        for (Expense e : countable) {
            Long assetId = e.getAsset() != null ? e.getAsset().getRowId() : null;
            if (assetId == null || !checkCardIds.contains(assetId)) {
                continue;
            }
            used.merge(assetId, e.expenseContribution(), Long::sum);
        }
        return used;
    }

    @Override
    public AssetServiceDto.AssetInfo getAsset(Long assetId, Long userRowId) {
        log.debug("자산 상세 조회: assetId={}", assetId);

        Asset asset = findAssetOrThrow(assetId);
        validateAssetOwnership(asset, userRowId);
        LocalDateTime now = userClock.now(userRowId);
        return withCardState(AssetServiceDto.AssetInfo.from(asset, activeHoldingInfos(assetId),
                balanceHistoryService.balanceAt(asset, now))
            .withMonthlyUsedAmount(
                checkCardMonthlyUsed(List.of(asset), userRowId, now).get(asset.getRowId())), asset);
    }

    @Override
    @Transactional
    public AssetServiceDto.AssetInfo updateAsset(Long assetId, Long userRowId, AssetServiceDto.UpdateAssetCommand command) {
        log.debug("자산 수정 시작: assetId={}", assetId);

        Asset asset = findAssetOrThrow(assetId);
        validateAssetOwnership(asset, userRowId);

        // 실린 칸만 바꾼다 — 안 온 칸은 지금 값이 그대로 남는다(QA #96).
        // 조회가 필요한 칸(카드 카탈로그·결제 계좌)은 실렸을 때만 찾는다.
        CardCatalog cardCatalog = command.cardCatalogRowId()
            .map(this::resolveCardCatalog)
            .orKeep(asset.getCardCatalog());
        // 지운 결제계좌는 '연결 없음' 이다(QA 30 6). 지우기 전에 연 폼이 그 id 를 그대로 실어 와도
        // 끊고 넘어간다 — 404 로 카드 수정 자체를 막지 않는다.
        Asset paymentAsset = command.paymentAssetRowId()
            .map(rowId -> resolvePaymentAssetOrNone(rowId, userRowId))
            .orKeep(asset.getUsablePaymentAsset());

        // 필수 필드(NOT NULL)는 명시적 null 을 DTO 제약이 400 으로 끊는다 — 여기 오는 값은 안전하다.
        // 선택 필드(color/institution/memo/한도/결제일/결제 계좌)는 명시적 null 이 clear 다.
        // 보유를 함께 보낸 투자 자산은 평가액을 서버가 산정한다(클라이언트 계산값 불신).
        // 연동 시세를 못 구하면 기존 평가금액을 유지한다 — 부분합으로 덮어쓰지 않기 위해서다.
        //
        // 갱신 대상은 HOLDING 채널이다. 예수금은 건드리지 않으므로, 이 자산으로 들어온
        // 이체는 평가액을 몇 번 다시 계산하든 그대로 남는다.
        // 빈 리스트도 받는다 — 보유를 전부 지우면 평가금액이 0 이 돼야 한다.
        AssetType effectiveType = command.assetType().orKeep(asset.getAssetType());
        // 검증·정규화를 자산 필드 수정보다 앞에 둔다. 뒤에 두면 잘못된 보유 입력이 자산 이름·종류만
        // 고쳐 놓고 실패해서, 한 번의 저장이 절반만 반영된 상태로 남는다.
        // (종전에는 아래 asset.updateAsset(...) 뒤에서 검사했다 — 판정에 쓰는 종류는 그때도
        //  '이번 요청이 정하는 종류' 라 effectiveType 과 같다.)
        validateHoldings(effectiveType, command.holdings());
        // 생성 경로와 같은 자리에서 다듬고 접는다. 평가액 산정도 접은 목록을 봐야
        // 같은 종목이 두 줄로 오면 평가액이 두 번 더해지는 일이 없다.
        List<AssetServiceDto.HoldingCommand> holdingCommands = normalizeHoldings(command.holdings());
        boolean investHoldings = holdingCommands != null && effectiveType == AssetType.INVESTMENT;
        // 보유가 남아 있는지 — 빈 리스트(전량 매도·전부 삭제)와 구분해야 한다.
        boolean hasHoldings = investHoldings && !holdingCommands.isEmpty();
        // 지금 잔액은 이력에서 집계한다 — 캐시 컬럼은 낡을 수 있고, 앵커를 찍을지
        // 말지를 낡은 값으로 판단하면 멀쩡한 입력이 버려지거나 헛 앵커가 쌓인다.
        AssetBalanceHistoryService.Split current =
            balanceHistoryService.balanceAt(asset, userClock.now(userRowId));
        Long holdingValuation = null;
        if (investHoldings) {
            Long computed = computeInvestmentBalance(userRowId, holdingCommands);
            holdingValuation = computed != null ? computed : current.holding();
        }
        Long oldPaymentAssetRowId = asset.getPaymentAsset() != null
            ? asset.getPaymentAsset().getRowId() : null;

        // 신용카드는 결제일이 있어야 한다(D8). 결제일을 비우거나, 다른 종류를 결제일 없이 신용카드로 바꾸는
        // 요청만 막는다 — 결제일 없는 옛 카드의 다른 칸 수정(이름·금액 가리기)까지 막으면 안 된다.
        boolean wasCard = asset.getAssetType() == AssetType.CREDIT_CARD;
        Integer effectiveDay = command.paymentDay().orKeep(asset.getPaymentDay());
        if (effectiveType == AssetType.CREDIT_CARD && effectiveDay == null
                && (command.paymentDay().present() || !wasCard)) {
            throw new InvalidValueException(DeskErrorCode.ASSET_CARD_PAYMENT_DAY_REQUIRED);
        }
        // 통화 · 환율 규칙은 생성과 같다 — 실린 값과 지금 값을 합친 결과로 본다.
        validateCurrency(effectiveType,
            command.currency().orKeep(asset.getCurrency()),
            command.exchangeRate().orKeep(asset.getExchangeRate()));
        // 결제일 변경은 다음 회차부터(D5) — 자산을 고치기 전에(옛 결제일을 들고 있을 때) 이력을 적는다.
        if (wasCard && effectiveType == AssetType.CREDIT_CARD && effectiveDay != null
                && !effectiveDay.equals(asset.getPaymentDay())) {
            paymentScheduleService.changePaymentDay(asset, effectiveDay, serviceClock.today());
        }
        asset.updateAsset(
            command.assetName().orKeep(asset.getAssetName()),
            effectiveType,
            command.currency().orKeep(asset.getCurrency()),
            command.exchangeRate().orKeep(asset.getExchangeRate()),
            command.color().orKeep(asset.getColor()),
            command.institution().orKeep(asset.getInstitution()),
            command.memo().orKeep(asset.getMemo()),
            command.isIncludedInTotal().orKeep(asset.getIsIncludedInTotal()),
            cardCatalog,
            command.creditLimit().orKeep(asset.getCreditLimit()),
            command.paymentDay().orKeep(asset.getPaymentDay()),
            paymentAsset
        );
        asset.updateAmountHidden(command.isAmountHidden().orKeep(asset.getIsAmountHidden()));
        if (!wasCard && asset.getAssetType() == AssetType.CREDIT_CARD) {
            // 다른 종류에서 신용카드가 됐다 — 처음부터 이 결제일.
            paymentScheduleService.initialize(asset);
        }

        // 평가금액(HOLDING)과 예수금(CASH)은 서로 다른 칸이라 각각 반영한다.
        // 한쪽 가지가 다른 쪽을 막으면 전량 매도처럼 두 칸이 동시에 바뀌는 상황에서 입력이 버려진다.
        if (investHoldings && !Objects.equals(current.holding(), holdingValuation)) {
            balanceHistoryService.recordValuation(asset, holdingValuation, userClock.now(userRowId));
        }
        // 예수금은 보유가 없을 때만 잔액칸으로 조정한다 — 보유가 있으면 그 값은 평가금액을 포함한
        // 총액이라 예수금 앵커로 찍으면 이중 계상된다.
        //
        // 비교 대상이 총액이 아니라 예수금인 게 핵심이다. 전량 매도로 평가금액이 예수금으로
        // 옮겨오면 총액은 그대로인 채 칸만 바뀌는데, 총액끼리 비교하면 '안 바뀌었다'로 보여
        // 매도 대금이 통째로 사라진다.
        //
        // 부호를 씌운 뒤에 비교한다(QA #19). 정규화 전 값과 비교하면 '값은 그대로인데 부호만
        // 바뀐' 요청에서 앵커가 한 번 더 찍히고, 반대로 정규화 뒤 같은 값이 되는 요청에서
        // 헛 앵커가 남는다. 유형은 이 시점의 asset 것을 본다 — 이번 요청에서 종류를 바꿨으면
        // 새 종류가 부호를 정하는 게 맞다.
        // 잔액은 "지운다" 가 없는 칸이다 — 잔액은 이력에서 집계하고 이 값은 "앵커를 찍어 달라" 는
        // 요청이다. 안 보내도(키 없음) 보낸 값이 null 이어도 앵커를 안 찍는다(종전과 같다).
        Long requestedBalance = command.balance().value();
        Long newBalance = requestedBalance == null ? null
            : AssetSignPolicy.normalizeBalance(asset.getAssetType(), command.isOverdraft().value(), requestedBalance);
        if (asset.getAssetType() == AssetType.CREDIT_CARD) {
            // 카드는 잔액 앵커를 쓰지 않는다(D4). 이월 금액("이전 미결제 사용액")은 제 키로만 고친다(D7) —
            // balance 는 무시한다. 옛 폼이 그 칸을 지금 잔액으로 채워 보내 이월 거래를 덮어썼다.
            Long carryover = command.carryoverAmount().value();
            if (command.carryoverAmount().present() && carryover != null) {
                syncCardCarryover(asset, carryover, userClock.now(userRowId));
            }
            Long dueCarryover = command.dueCarryoverAmount().value();
            if (command.dueCarryoverAmount().present() && dueCarryover != null) {
                syncDueCarryover(asset, dueCarryover, registeredDate(asset));
            }
        } else if (!hasHoldings && newBalance != null
            && !Objects.equals(current.cash(), newBalance)) {
            balanceHistoryService.recordManual(asset, newBalance, userClock.now(userRowId));
        }

        // 체크카드 연결 계좌가 바뀌면 그 카드로 쓴 기존 지출 이력도 새 계좌로 옮긴다 —
        // 신규만 옮기면 과거는 카드에, 신규는 계좌에 쌓여 어느 쪽 잔액도 맞지 않는다.
        if (asset.getAssetType() == AssetType.CHECK_CARD
            && asset.getPaymentAsset() != null
            && !Objects.equals(oldPaymentAssetRowId, asset.getPaymentAsset().getRowId())) {
            balanceHistoryService.relinkCheckCardHistory(asset, asset.getPaymentAsset());
        }

        // 보유 동기화 — null=무변경, 리스트=rowId(없으면 종목 식별자)로 맞춘다.
        // 있으면 제자리 수정, 없으면 신규, 안 온 건 삭제. 통째로 갈아끼우면 row_id 가 매번 바뀌어
        // 거래(asset_trade)를 이름으로 묶을 수밖에 없고, 종목명을 고치는 순간 원가와 이력이 끊긴다.
        List<AssetServiceDto.HoldingInfo> holdings;
        if (holdingCommands != null) {
            holdings = syncHoldings(asset, holdingCommands);
        } else {
            holdings = activeHoldingInfos(assetId);
        }

        log.info("자산 수정 완료: assetId={}", assetId);
        return withCardState(AssetServiceDto.AssetInfo.from(asset, holdings,
            balanceHistoryService.balanceAt(asset, userClock.now(userRowId))), asset);
    }

    @Override
    @Transactional
    public void deleteAsset(Long assetId, Long userRowId) {
        log.debug("자산 삭제 시작: assetId={}", assetId);

        Asset asset = findAssetOrThrow(assetId);
        validateAssetOwnership(asset, userRowId);
        asset.deleteAsset();
        // 이 계좌를 결제·연결 계좌로 쓰던 카드는 연결을 끊는다 — 안 끊으면 그 카드의 결제·수정이
        // 지운 계좌를 찾다 404 로 막혔다(QA 30 6).
        int detached = 0;
        for (Asset card : assetRepository.findByUser(userRowId)) {
            if (card.getPaymentAsset() != null && Objects.equals(card.getPaymentAsset().getRowId(), assetId)) {
                card.detachPaymentAsset();
                detached++;
            }
        }

        log.info("자산 삭제 완료: assetId={}, 연결 끊은 카드={}", assetId, detached);
    }

    /**
     * 보유 입력을 <b>저장 전에 한 자리에서</b> 다듬고, 같은 종목이 두 줄로 온 것을 한 줄로 접는다.
     *
     * <h4>왜 정규화가 먼저인가</h4>
     * 한 자산 안의 종목 유일성은 DB {@code UNIQUE} 로 내려간다. 그 컬럼의 콜레이션은
     * {@code utf8mb4_unicode_ci} 라 {@code "aapl"} 과 {@code "AAPL"} 을 같은 값으로 보는데,
     * 자바 쪽 판정({@code holdingKey().equals(...)})은 대소문자를 가린다. 다듬지 않으면
     * "코드는 없다고 하는데 DB 는 있다고 하는" 상태가 되어 멀쩡한 편집 저장이 통째로 실패한다.
     * <ul>
     *   <li><b>종목코드</b>는 시장이 정한 코드라 대소문자에 뜻이 없다 → 대문자로 <b>저장까지</b> 맞춘다.</li>
     *   <li><b>항목명</b>은 사용자가 붙인 이름이라 친 대로 남아야 한다 → {@code trim} 만 하고
     *       (다른 이름 여덟 곳과 같은 {@code NameNormalizer}), 비교할 때만 대문자로 올린다.</li>
     * </ul>
     *
     * <h4>왜 접는가</h4>
     * 편집 폼은 같은 종목을 두 줄로 만들 수 있고, 지금은 그 배열을 그대로 순회하며 만들기 때문에
     * <b>두 행</b>이 생긴다. 그러면 목록에 같은 종목이 두 번 보이고, 매수·매도는 그중 한 행에만
     * 붙어 평가액과 원가가 갈라진다. 여기서 접어 두면 그 아래 계산(평가액 산정·행 저장)이
     * 전부 한 줄만 본다.
     *
     * <p>겹칠 때 <b>뒤엣것이 이긴다</b> — 같은 {@code rowId} 를 두 줄로 보내면 지금도 뒤엣값으로
     * 덮어쓰기 때문에(같은 행에 {@code updateHolding} 을 두 번 부른다) 규칙을 하나로 맞춘 것이다.
     *
     * @return 입력이 null 이면 null — "보유 무변경" 과 "전부 삭제(빈 리스트)" 는 다른 뜻이다.
     */
    private List<AssetServiceDto.HoldingCommand> normalizeHoldings(List<AssetServiceDto.HoldingCommand> holdings) {
        if (holdings == null) {
            return null;
        }
        Map<String, AssetServiceDto.HoldingCommand> folded = new LinkedHashMap<>();
        for (int i = 0; i < holdings.size(); i++) {
            AssetServiceDto.HoldingCommand hc = holdings.get(i);
            boolean linked = Boolean.TRUE.equals(hc.linked());
            String symbol = AssetHolding.normalizeKey(hc.symbol());
            String holdingName = hc.holdingName() == null
                ? null : NameNormalizer.require(hc.holdingName(), FieldLimits.WIDE_NAME_MAX);
            // 연동인데 종목코드가 비면 유일성 키가 통째로 비어, 같은 종목이 몇 줄이든 들어온다
            // (DB 도 NULL 은 서로 다른 값으로 본다). 저장 전에 끊는다.
            if (linked && symbol == null) {
                throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
            }
            AssetServiceDto.HoldingCommand normalized = new AssetServiceDto.HoldingCommand(
                hc.rowId(), hc.holdingType(), hc.linked(), hc.marketCode(), symbol,
                hc.quantity(), holdingName, hc.holdingValue(), hc.totalCost(), hc.sortOrder());
            String key = AssetHolding.uniquenessKey(linked, linked ? symbol : holdingName);
            // 키를 못 만드는 입력(미연동인데 항목명이 없다)은 접지 않고 자리만 지킨다 —
            // 접으면 validateHoldings 가 거절할 입력이 조용히 사라진다. "?" 로 시작하는 자리표는
            // 실제 키("L:"·"M:" 로 시작)와 절대 겹치지 않는다.
            folded.merge(key != null ? key : "?" + i, normalized, AssetServiceImpl::mergeDuplicateHolding);
        }
        return List.copyOf(folded.values());
    }

    /** 같은 종목으로 접힌 두 줄을 하나로 — 값은 뒤엣것, 행 식별자는 앞엣것. */
    private static AssetServiceDto.HoldingCommand mergeDuplicateHolding(
            AssetServiceDto.HoldingCommand first, AssetServiceDto.HoldingCommand later) {
        // rowId 만 앞엣것을 살린다. 뒤엣줄이 rowId 없이 왔다고 새 행을 만들면 기존 행이 삭제되고
        // 새 행이 생겨 거래(asset_trade)가 가리키던 보유가 끊긴다.
        // 자리(sortOrder)도 앞엣것이다 — 접힌 행은 목록에서 <b>앞엣줄이 있던 자리</b>에 남는다
        // (LinkedHashMap 이 첫 등장 위치를 지킨다). 뒤엣줄 값을 쓰면 화면에서 본 자리와 어긋난다.
        return new AssetServiceDto.HoldingCommand(
            first.rowId() != null ? first.rowId() : later.rowId(),
            later.holdingType(), later.linked(), later.marketCode(), later.symbol(),
            later.quantity(), later.holdingName(), later.holdingValue(), later.totalCost(),
            first.sortOrder() != null ? first.sortOrder() : later.sortOrder());
    }

    /**
     * 보유 입력 검증 — INVESTMENT 전용.
     * linked=Y 는 주식만 가능하며 종목코드+수량 필수, linked=N 은 이름+평가액 필수(수량은 선택).
     */
    private void validateHoldings(AssetType assetType, List<AssetServiceDto.HoldingCommand> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return;
        }
        if (assetType != AssetType.INVESTMENT) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }
        for (AssetServiceDto.HoldingCommand hc : holdings) {
            boolean linked = Boolean.TRUE.equals(hc.linked());
            HoldingType type = hc.holdingType() != null ? hc.holdingType() : HoldingType.STOCK;

            // 수량은 유형·연동 여부와 무관하게 음수를 허용하지 않는다.
            if (hc.quantity() != null && hc.quantity().signum() < 0) {
                throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
            }
            // 평가액이 음수면 투자 자산이 부(−)로 뒤집히고, 원가가 음수면 매도 실현손익이
            // (대금 − 원가)라 그만큼 부풀려진다. 둘 다 0 은 허용한다 — 평가액 0 인 보유와
            // 원가를 모르는 기초 보유가 실제로 있다.
            if (hc.holdingValue() != null && hc.holdingValue() < 0) {
                throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
            }
            if (hc.totalCost() != null && hc.totalCost() < 0) {
                throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
            }

            if (linked) {
                // 토스는 국내·미국 주식 시세만 제공한다 — 금·코인은 연동 대상이 아니다.
                if (type != HoldingType.STOCK) {
                    throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
                }
                if (hc.symbol() == null || hc.symbol().isBlank() || hc.quantity() == null) {
                    throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
                }
            } else {
                if (hc.holdingName() == null || hc.holdingName().isBlank() || hc.holdingValue() == null) {
                    throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
                }
            }
        }
    }

    /** 미연동 보유의 입력 금액 합 — 정수라 오차가 없다. */
    private static long manualHoldingsSum(List<AssetServiceDto.HoldingCommand> holdings) {
        long sum = 0;
        for (AssetServiceDto.HoldingCommand hc : holdings) {
            if (!Boolean.TRUE.equals(hc.linked()) && hc.holdingValue() != null) {
                sum += hc.holdingValue();
            }
        }
        return sum;
    }

    /**
     * 투자 자산의 평가액을 <b>서버가</b> 산정한다.
     *
     * <p>이전에는 클라이언트가 시세×수량을 계산해 balance 로 보냈다. JS·Dart 는 십진 소수를 정확히
     * 담지 못해 저장값이 어긋날 수 있고, 무엇보다 DB 에 남는 금액을 클라이언트가 정하는 구조였다.
     *
     * <p>미연동 보유는 입력 금액 그대로(정수), 연동 보유는 토스 시세×수량을 BigDecimal 로 계산한다.
     * 시세 조회는 종목 다건 1콜 + 외화가 있을 때만 환율 1콜로 끝낸다.
     *
     * @return 연동 시세를 하나라도 못 구하면 null — 부분합으로 자산 금액을 왜곡하지 않기 위해서다.
     *         호출부가 생성이면 미연동 합, 수정이면 기존 잔액을 쓴다.
     */
    private Long computeInvestmentBalance(Long userRowId, List<AssetServiceDto.HoldingCommand> holdings) {
        long manual = manualHoldingsSum(holdings);
        List<AssetServiceDto.HoldingCommand> linked = holdings.stream()
            .filter(hc -> Boolean.TRUE.equals(hc.linked()) && hc.symbol() != null && hc.quantity() != null)
            .toList();
        if (linked.isEmpty()) {
            return manual;
        }
        try {
            SecuritiesPriceProvider provider = priceProviders.forUser(userRowId);
            // 편집 폼이 보낸 값이라 시장코드가 없다 — 해석기 우선순위로 종목을 정한다.
            List<InstrumentRef> instruments = linked.stream()
                .map(AssetServiceDto.HoldingCommand::symbol)
                .distinct()
                .map(InstrumentRef::of)
                .toList();
            Map<String, PriceQuote> priceBySymbol = new HashMap<>();
            for (PriceQuote q : provider.getPrices(userRowId, instruments)) {
                priceBySymbol.put(q.symbol(), q);
            }

            Map<String, BigDecimal> fxByCurrency = new HashMap<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (AssetServiceDto.HoldingCommand hc : linked) {
                PriceQuote q = priceBySymbol.get(hc.symbol());
                if (q == null) {
                    return null;
                }
                BigDecimal krw = toKrw(userRowId, provider, q, fxByCurrency);
                if (krw == null) {
                    return null; // 외화인데 환율 미확보
                }
                sum = sum.add(krw.multiply(hc.quantity()));
            }
            return toWon(sum) + manual;
        } catch (Exception ex) {
            log.warn("투자 평가액 산정 실패 - userRowId={}: {}", userRowId, ex.getMessage());
            return null;
        }
    }

    /**
     * 보유 목록을 맞춘다 — 있으면 제자리 수정, 없으면 신규, 안 온 건 삭제.
     *
     * <p>짝을 찾는 순서는 <b>rowId → 종목 식별자</b> 다. row_id 가 유지돼야 거래(asset_trade)가
     * 보유를 안정적으로 가리킬 수 있다. 통째로 갈아끼우면 id 가 매번 바뀌어 이름으로 묶게 되고,
     * 종목명을 고치는 순간 끊긴다.
     *
     * <p>식별자로 한 번 더 찾는 이유: 편집 폼이 rowId 를 안 실어 보내는 경로(가져오기·구버전 앱·
     * 폼이 줄을 다시 그린 경우)가 있고, 그때 같은 종목을 새 행으로 만들면 한 자산에 같은 종목이
     * 두 줄로 남는다. 입력은 이미 {@link #normalizeHoldings} 가 접어 놓았으므로 여기 들어오는
     * 목록에는 같은 식별자가 두 번 나오지 않는다.
     */
    private List<AssetServiceDto.HoldingInfo> syncHoldings(Asset asset,
                                                           List<AssetServiceDto.HoldingCommand> holdings) {
        List<AssetHolding> existing = assetHoldingRepository.findActiveByAsset(asset.getRowId());
        Map<Long, AssetHolding> byId = existing.stream()
            .filter(h -> h.getRowId() != null)
            .collect(Collectors.toMap(AssetHolding::getRowId, h -> h, (a, b) -> a));
        // 원가는 매수·매도가 쌓은 값이고 편집 폼이 늘 보내오지는 않는다. rowId 로 못 찾는
        // 신규 행이면 종목 식별자로라도 이어 붙인다 — 안 그러면 다음 매도에서 대금 전액이 이익이 된다.
        Map<String, Long> costByKey = existing.stream()
            .filter(h -> h.uniquenessKey() != null && h.getTotalCost() != null)
            .collect(Collectors.toMap(AssetHolding::uniquenessKey, AssetHolding::getTotalCost, (a, b) -> a));
        // 원가만이 아니라 <b>행 자체</b>를 이어 붙일 자리. rowId 를 안 실어 보내는 요청
        // (가져오기·구버전 앱·폼이 줄을 다시 그린 경우)이 오면 종전에는 같은 종목이 새 행으로
        // 생기고 옛 행은 삭제됐다 — row_id 가 바뀌어 거래 연결이 끊기고, 삭제가 커밋되기 전에
        // 새 행이 먼저 들어가면 DB 유일성에 걸려 저장 전체가 실패한다.
        Map<String, AssetHolding> byKey = new LinkedHashMap<>();
        for (AssetHolding h : existing) {
            if (h.uniquenessKey() != null) {
                byKey.putIfAbsent(h.uniquenessKey(), h);
            }
        }

        List<AssetServiceDto.HoldingInfo> result = new ArrayList<>(holdings.size());
        Set<AssetHolding> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < holdings.size(); i++) {
            AssetServiceDto.HoldingCommand hc = holdings.get(i);
            boolean linked = Boolean.TRUE.equals(hc.linked());
            AssetHolding found = hc.rowId() != null ? byId.get(hc.rowId()) : null;
            // 같은 행을 두 줄이 가져가면(같은 rowId 를 두 번 보낸 요청) 뒤엣줄은 못 찾은 것으로 본다 —
            // 한 행에 두 줄을 겹쳐 쓰면 응답에는 두 줄인데 DB 에는 한 행만 남는다.
            if (found != null && kept.contains(found)) {
                found = null;
            }
            if (found == null) {
                AssetHolding sameKey = byKey.get(
                    AssetHolding.uniquenessKey(linked, linked ? hc.symbol() : hc.holdingName()));
                if (sameKey != null && !kept.contains(sameKey)) {
                    found = sameKey;
                }
            }
            if (found != null) {
                found.updateHolding(
                    hc.holdingType() != null ? hc.holdingType() : HoldingType.STOCK,
                    linked ? YNType.Y : YNType.N,
                    confirmMarketCode(hc),
                    linked ? hc.symbol() : null,
                    hc.quantity(),
                    linked ? null : hc.holdingName(),
                    linked ? null : hc.holdingValue(),
                    hc.totalCost(),
                    holdingOrder(hc, i));
                kept.add(found);
                result.add(AssetServiceDto.HoldingInfo.from(found));
                continue;
            }
            AssetHolding created = AssetHolding.create(
                asset,
                hc.holdingType() != null ? hc.holdingType() : HoldingType.STOCK,
                linked ? YNType.Y : YNType.N,
                confirmMarketCode(hc),
                linked ? hc.symbol() : null,
                hc.quantity(),
                linked ? null : hc.holdingName(),
                linked ? null : hc.holdingValue(),
                resolveCost(hc, costByKey),
                holdingOrder(hc, i));
            assetHoldingRepository.save(created);
            result.add(AssetServiceDto.HoldingInfo.from(created));
        }
        // 목록에서 빠진 보유만 지운다. id 로 매칭되지 않은 것도 여기 걸린다.
        for (AssetHolding h : existing) {
            if (!kept.contains(h)) {
                h.deleteHolding();
            }
        }
        return result;
    }

    /**
     * 보유 한 줄의 자리 — <b>보내온 값이 이기고</b>, 안 보냈으면 배열 인덱스다.
     *
     * <p>종전엔 인덱스만 썼다. 클라이언트가 {@code sortOrder} 를 실어 보내도 요청 DTO 에 그 필드가
     * 없어 Jackson 이 조용히 버렸기 때문이다(QA 2026-09-07 #91). 이제 값이 오면 그 값을 쓴다.
     *
     * <p>안 보냈을 때 0 이 아니라 <b>인덱스</b>로 가는 이유: 보유는 순서 변경 API 가 따로 없고
     * 목록을 통째로 보내는 것이 곧 순서 지정이다. 전부 0 이면 정렬이 {@code rowId asc} 로 떨어져
     * 끌어 옮긴 순서가 사라진다. 앱은 이 필드를 일부러 안 싣고 배열 순서에 기댄다.
     */
    private static int holdingOrder(AssetServiceDto.HoldingCommand hc, int index) {
        return hc.sortOrder() != null ? hc.sortOrder() : index;
    }

    /** 보유 신규 저장 — 자리는 {@link #holdingOrder}. 생성 경로 전용. */
    private List<AssetServiceDto.HoldingInfo> saveHoldings(Asset asset, List<AssetServiceDto.HoldingCommand> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            return List.of();
        }
        // 편집은 보유 목록을 통째로 갈아끼운다. 원가는 매수·매도가 쌓은 값이고 편집 폼은
        // 그걸 입력받지 않으므로, 안 보내오면 종목 식별자로 이어 붙인다 — 안 그러면 편집을
        // 저장하는 순간 원가가 0 이 되고 다음 매도에서 대금 전액이 이익으로 잡힌다.
        Map<String, Long> costByKey = assetHoldingRepository.findActiveByAsset(asset.getRowId()).stream()
            .filter(h -> h.uniquenessKey() != null && h.getTotalCost() != null)
            .collect(Collectors.toMap(AssetHolding::uniquenessKey, AssetHolding::getTotalCost, (a, b) -> a));
        List<AssetServiceDto.HoldingInfo> result = new ArrayList<>(holdings.size());
        for (int i = 0; i < holdings.size(); i++) {
            AssetServiceDto.HoldingCommand hc = holdings.get(i);
            boolean linked = Boolean.TRUE.equals(hc.linked());
            AssetHolding holding = AssetHolding.create(
                asset,
                hc.holdingType() != null ? hc.holdingType() : HoldingType.STOCK,
                linked ? YNType.Y : YNType.N,
                confirmMarketCode(hc),
                linked ? hc.symbol() : null,
                // 미연동도 수량을 남긴다 — 몇 주·몇 g·몇 개인지는 평가액과 별개로 기록 가치가 있다(선택 입력).
                hc.quantity(),
                linked ? null : hc.holdingName(),
                linked ? null : hc.holdingValue(),
                resolveCost(hc, costByKey),
                holdingOrder(hc, i)
            );
            assetHoldingRepository.save(holding);
            result.add(AssetServiceDto.HoldingInfo.from(holding));
        }
        return result;
    }

    /**
     * 저장할 시장코드 — 미연동 보유는 종목이 없으니 null, 연동이면 클라가 보낸 값을 쓰고
     * 안 보냈으면 심볼로 1회 해석한다. 여러 시장에 걸리는 심볼은 비운 채 남긴다.
     */
    private String confirmMarketCode(AssetServiceDto.HoldingCommand hc) {
        if (!Boolean.TRUE.equals(hc.linked())) {
            return null;
        }
        return stockMasterResolver.confirmMarketCode(hc.marketCode(), hc.symbol());
    }

    /** 원가 — 보내왔으면 그 값, 아니면 같은 종목의 기존 원가를 잇는다. */
    private Long resolveCost(AssetServiceDto.HoldingCommand hc, Map<String, Long> costByKey) {
        if (hc.totalCost() != null) {
            return hc.totalCost();
        }
        boolean linked = Boolean.TRUE.equals(hc.linked());
        String key = AssetHolding.uniquenessKey(linked, linked ? hc.symbol() : hc.holdingName());
        return key != null ? costByKey.get(key) : null;
    }

    private List<AssetServiceDto.HoldingInfo> activeHoldingInfos(Long assetId) {
        return assetHoldingRepository.findActiveByAsset(assetId).stream()
            .map(AssetServiceDto.HoldingInfo::from)
            .toList();
    }

    @Override
    @Transactional
    public void snapshotSecuritiesValuations() {
        List<Asset> investments = assetRepository.findAllByType(AssetType.INVESTMENT);
        if (investments.isEmpty()) {
            return;
        }
        // 자산별 유효 평가 소스: 활성 linked 보유(holdings)가 있으면 그 합산,
        // 없으면 레거시 단일 연동(toss_symbol×toss_quantity). 이관 전후 모두 안전.
        Map<Long, List<AssetHolding>> linkedHoldingsByAsset =
            assetHoldingRepository.findActiveByAssets(investments.stream().map(Asset::getRowId).toList())
                .stream()
                .filter(AssetHolding::isLinked)
                .collect(Collectors.groupingBy(h -> h.getAsset().getRowId()));

        List<Asset> linked = investments.stream()
            .filter(a -> linkedHoldingsByAsset.containsKey(a.getRowId()) || a.isSecuritiesLinked())
            .toList();
        if (linked.isEmpty()) {
            return;
        }
        // 사용자별로 묶어 시세를 1회 조회 → 종목별 (현재가 × 보유수량)을 VALUATION 앵커로 적재.
        Map<Long, List<Asset>> byUser = linked.stream()
            .collect(Collectors.groupingBy(a -> a.getUser().getRowId()));

        for (Map.Entry<Long, List<Asset>> entry : byUser.entrySet()) {
            Long userRowId = entry.getKey();
            List<Asset> userAssets = entry.getValue();
            try {
                // 게이트: 구독 만료/토스 해제된 사용자는 스냅샷 생략.
                if (!entitlementService.hasFeature(userRowId, FEATURE_SECURITIES)) {
                    continue;
                }
                if (!securitiesCredentialService.hasAnyConnection(userRowId)) {
                    continue;
                }
                SecuritiesPriceProvider provider = priceProviders.forUser(userRowId);
                // 저장된 시장코드를 그대로 태운다 — 같은 티커가 여러 시장에 걸려도 종목이 확정된다.
                // 저장은 연결·편집·매매 세 경로에서 한다(confirmMarketCode). 확정 못 한 행은
                // null 로 나가고, 그때는 provider 쪽 우선순위 해석에 맡긴다.
                List<InstrumentRef> instruments = userAssets.stream()
                    .flatMap(a -> valuationPairs(a, linkedHoldingsByAsset).stream())
                    .map(p -> InstrumentRef.of(p.marketCode(), p.symbol()))
                    .distinct()
                    .toList();
                Map<String, PriceQuote> priceBySymbol = new HashMap<>();
                for (PriceQuote q : provider.getPrices(userRowId, instruments)) {
                    priceBySymbol.put(q.symbol(), q);
                }
                // 외화가 섞여 있으면 통화마다 환율을 받는다. 예전엔 USD 하나만 받아 곱했는데,
                // 나무를 붙이며 JPY·HKD·CNY 보유가 가능해져 엔화에 달러 환율을 곱하는 일이 생긴다.
                Map<String, BigDecimal> fxByCurrency = new HashMap<>();
                LocalDateTime now = userClock.now(userRowId);
                for (Asset a : userAssets) {
                    // 보유(linked) 합산 평가 — 한 종목이라도 시세/환율 미확보면 자산 전체 생략(부분합 왜곡 방지).
                    // 소수 수량이 섞여도 어긋나지 않게 합산을 BigDecimal 로 한다(원 단위 반올림은 마지막에 1회).
                    BigDecimal sum = BigDecimal.ZERO;
                    boolean complete = true;
                    List<SymbolQty> pairs = valuationPairs(a, linkedHoldingsByAsset);
                    if (pairs.isEmpty()) {
                        continue;
                    }
                    for (SymbolQty pair : pairs) {
                        PriceQuote q = priceBySymbol.get(pair.symbol());
                        if (q == null) {
                            complete = false;
                            break;
                        }
                        BigDecimal krw = toKrw(userRowId, provider, q, fxByCurrency);
                        if (krw == null) {
                            complete = false; // 외화인데 환율 미확보
                            break;
                        }
                        sum = sum.add(krw.multiply(pair.qty()));
                    }
                    if (!complete) {
                        continue;
                    }
                    balanceHistoryService.recordValuation(a, toWon(sum), now);
                }
            } catch (Exception ex) {
                // 사용자 단위 격리 — 한 명의 토스 조회 실패가 전체를 막지 않게.
                log.warn("토스 평가액 스냅샷 실패 - userRowId={}: {}", userRowId, ex.getMessage());
            }
        }
    }

    /** 자산의 평가 대상 (종목코드, 수량) 목록 — 활성 linked 보유 우선, 없으면 레거시 단일 연동. */
    private List<SymbolQty> valuationPairs(Asset asset, Map<Long, List<AssetHolding>> linkedHoldingsByAsset) {
        List<AssetHolding> holdings = linkedHoldingsByAsset.get(asset.getRowId());
        if (holdings != null && !holdings.isEmpty()) {
            return holdings.stream()
                .filter(h -> h.getSymbol() != null && h.getQuantity() != null)
                .map(h -> new SymbolQty(h.getMarketCode(), h.getSymbol(), h.getQuantity()))
                .toList();
        }
        if (asset.isSecuritiesLinked() && asset.getQuantity() != null) {
            return List.of(new SymbolQty(asset.getMarketCode(), asset.getSymbol(),
                BigDecimal.valueOf(asset.getQuantity())));
        }
        return List.of();
    }

    /**
     * marketCode 는 없을 수 있다 — 시장코드 컬럼이 생기기 전에 만들어진 연동 행,
     * 그리고 같은 심볼이 여러 시장에 걸려 확정을 보류한 행이 그렇다.
     */
    private record SymbolQty(String marketCode, String symbol, BigDecimal qty) {}

    /**
     * 시세를 원화로 환산한다. 환율은 <b>견적의 실제 통화</b>로 받는다.
     *
     * <p>예전엔 "KRW 가 아니면 USD" 로 보고 달러 환율을 곱했다. 토스만 있던 시절엔 통화가
     * KRW·USD 뿐이라 맞았지만, 나무를 붙이며 JPY·HKD·CNY 보유가 가능해졌다 — 엔화 종목에
     * 달러 환율을 곱하면 평가액이 백 배 넘게 부풀어 오른다.
     *
     * <p>{@code fxByCurrency} 는 호출 단위 캐시다. 같은 통화를 여러 번 묻지 않는다.
     * 못 구한 통화는 null 을 캐시해 재조회도 막는다 — 나무는 환율 조회가 잔고 2콜이라 비싸다.
     *
     * @return 원화 환산가. 외화인데 환율을 못 구하면 null(호출부가 그 자산 평가를 접는다)
     */
    private BigDecimal toKrw(Long userRowId, SecuritiesPriceProvider provider,
                             PriceQuote quote, Map<String, BigDecimal> fxByCurrency) {
        if (quote.price() == null) {
            return null;
        }
        if (quote.isKrw()) {
            return quote.price();
        }
        String currency = quote.currency();
        // computeIfAbsent 는 null 을 저장하지 않아 실패한 통화를 매번 다시 묻는다 —
        // 나무는 환율 조회가 잔고 2콜이라 그게 비싸다. containsKey 로 직접 캐시한다.
        if (!fxByCurrency.containsKey(currency)) {
            BigDecimal fetched = null;
            try {
                fetched = provider.getFxRate(userRowId, currency, "KRW");
            } catch (Exception ex) {
                log.warn("환율 조회 실패 - userRowId={}, {}→KRW: {}", userRowId, currency, ex.getMessage());
            }
            fxByCurrency.put(currency, fetched);
        }
        BigDecimal rate = fxByCurrency.get(currency);
        return rate == null || rate.signum() <= 0 ? null : quote.price().multiply(rate);
    }

    /** 기본 소스 증권사가 이 종목 시세를 주는지 — 유효 종목 검증(미인식/조회실패 시 false). */
    private boolean isPriceAvailable(Long userRowId, String marketCode, String symbol) {
        try {
            return priceProviders.forUser(userRowId)
                .getPrices(userRowId, List.of(InstrumentRef.of(marketCode, symbol))).stream()
                .anyMatch(q -> symbol.equalsIgnoreCase(q.symbol()) && q.price() != null);
        } catch (Exception ex) {
            log.warn("종목 시세 검증 실패 - symbol={}: {}", symbol, ex.getMessage());
            return false;
        }
    }

    /**
     * 현재가(외화면 환율 환산) × 수량 = 원화 평가액. 시세 미수신/조회 실패 시 null.
     *
     * <p>수량이 소수(코인·소수점 주식)일 수 있어 곱셈을 전부 BigDecimal 로 한다 —
     * double 로 넘기면 0.1 같은 값이 이진수로 정확히 떨어지지 않아 평가액이 어긋난다.
     */
    private Long computeValuationKrw(Long userRowId, String marketCode, String symbol, BigDecimal quantity) {
        try {
            SecuritiesPriceProvider provider = priceProviders.forUser(userRowId);
            PriceQuote q = provider.getPrices(userRowId, List.of(InstrumentRef.of(marketCode, symbol))).stream()
                .filter(x -> symbol.equalsIgnoreCase(x.symbol()))
                .findFirst().orElse(null);
            if (q == null || q.price() == null) {
                return null;
            }
            BigDecimal krw = toKrw(userRowId, provider, q, new HashMap<>());
            if (krw == null) {
                return null;
            }
            return toWon(krw.multiply(quantity));
        } catch (Exception ex) {
            log.warn("평가액 계산 실패 - symbol={}: {}", symbol, ex.getMessage());
            return null;
        }
    }


    /** 원화 평가액 반올림 — 화폐 단위(원)로 떨어뜨린다. */
    private static long toWon(BigDecimal krw) {
        return krw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public AssetServiceDto.AssetSummary getAssetSummary(Long userRowId, Integer year, Integer month) {
        log.debug("자산 요약 조회: userRowId={}, year={}, month={}", userRowId, year, month);

        List<Asset> included = includedAssets(userRowId);
        LocalDate today = userClock.today(userRowId);
        boolean isPastPeriod = year != null && month != null
            && !(year == today.getYear() && month == today.getMonthValue());

        LocalDateTime asOf;
        LocalDateTime prevAsOf;
        if (!isPastPeriod) {
            // 현재 월(또는 year/month 미지정): 지금 시각 기준
            asOf = userClock.now(userRowId);
            prevAsOf = today.withDayOfMonth(1).minusDays(1).atTime(LocalTime.MAX);
        } else {
            // 과거 월: 선택 월 말 / 전월 말 시점
            LocalDate selectedMonthEnd = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());
            asOf = selectedMonthEnd.atTime(LocalTime.MAX);
            prevAsOf = selectedMonthEnd.minusMonths(1)
                .with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
        }
        return buildSummaryAt(included, asOf, prevAsOf);
    }

    /** 기준시각(asOf) 잔액으로 요약을 구성하고, 전월 시점(prevAsOf) 순자산과의 증감을 계산. */
    private AssetServiceDto.AssetSummary buildSummaryAt(List<Asset> included,
                                                        LocalDateTime asOf, LocalDateTime prevAsOf) {
        // 두 시점을 한 번씩 — DB 가 집계한다(이력을 앱으로 가져오지 않는다).
        Map<Long, AssetBalanceHistoryService.Split> at = balanceHistoryService.balancesAt(included, asOf);
        Map<Long, AssetBalanceHistoryService.Split> prev = balanceHistoryService.balancesAt(included, prevAsOf);
        long totalBalance = 0, totalAssets = 0, totalDebt = 0;
        Map<AssetType, long[]> byTypeAcc = new EnumMap<>(AssetType.class); // long[2] = { sumBalance, count }
        for (Asset a : included) {
            // 외화 자산은 원화로 환산해 더한다 — 환산하지 않으면 USD 1,000 잔고가
            // 순자산에 1,000원으로 들어가 합계가 무너진다(원화 자산은 환산율 1이라 그대로).
            long bal = krwOf(at, a);
            totalBalance += bal;
            // 부호가 아니라 <b>유형</b>으로 가른다(QA #21). 부호로 가르면 잔액이 음수인
            // 입출금(마이너스 통장)이 부채로 넘어가, 유형으로 가르는 자산 화면과 총자산이
            // 그 금액만큼 어긋난다(실측 차이 50,000).
            //
            // 자산군은 음수도 그대로 더한다 — 마이너스 통장은 총자산을 깎는 게 맞다.
            // 부채군은 부호를 뒤집어 더하므로 선결제해 양수가 된 카드는 <b>부채를 줄인다</b>
            // (그래서 totalDebt 는 음수가 될 수 있다). abs() 로 묶으면 그 카드가 자산에서
            // 빠지고 부채로도 더해져 두 번 깎인다.
            if (AssetSignPolicy.isDebtType(a.getAssetType())) {
                totalDebt += -bal;
            } else {
                totalAssets += bal;
            }
            long[] acc = byTypeAcc.computeIfAbsent(a.getAssetType(), k -> new long[]{0, 0});
            acc[0] += bal;
            acc[1] += 1;
        }
        // 새 식에서도 totalAssets - totalDebt = Σ자산군 + Σ부채군 = totalBalance 라 항등이 유지된다.
        long netWorth = totalAssets - totalDebt;

        long lastMonthNetWorth = netWorthOf(included, prev);
        long changeAmount = netWorth - lastMonthNetWorth;
        double changePercent = lastMonthNetWorth == 0
            ? 0.0
            : Math.round(((double) changeAmount / Math.abs(lastMonthNetWorth)) * 1000.0) / 10.0;

        List<AssetServiceDto.AssetTypeSummary> byType = byTypeAcc.entrySet().stream()
            .map(e -> new AssetServiceDto.AssetTypeSummary(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
            .toList();

        return new AssetServiceDto.AssetSummary(
            totalBalance, totalAssets, totalDebt, netWorth,
            lastMonthNetWorth, changeAmount, changePercent, byType
        );
    }

    @Override
    public List<AssetServiceDto.NetWorthTrendPoint> getNetWorthTrend(Long userRowId, Integer months) {
        if (months != null && months < 0) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }
        int n = (months == null || months < 1) ? 12 : Math.min(months, 36);
        log.debug("순자산 추이 조회: userRowId={}, months={}", userRowId, n);

        List<Asset> included = includedAssets(userRowId);
        LocalDate today = userClock.today(userRowId);
        LocalDateTime now = userClock.now(userRowId);

        // 시점마다 쿼리를 날리면 12개월에 12번이다. 시점 목록을 한 번에 넘겨
        // 집계 2회(시작 시점 + 일자별 변동)로 끝낸다.
        List<LocalDate> labels = new ArrayList<>(n);
        List<LocalDateTime> asOfs = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            LocalDate m = today.minusMonths(i);
            labels.add(m);
            // 현재 월(i=0)은 지금 시각, 과거 월은 월말 23:59:59.999999 → 현재 점 = summary netWorth 와 동일
            asOfs.add(i == 0 ? now : m.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX));
        }
        Map<Long, List<AssetBalanceHistoryService.Split>> series =
            balanceHistoryService.balancesAtPoints(included, asOfs);

        List<AssetServiceDto.NetWorthTrendPoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long assets = 0, debt = 0;
            for (Asset a : included) {
                List<AssetBalanceHistoryService.Split> seq = series.get(a.getRowId());
                long bal = krwOf(a, seq == null ? AssetBalanceHistoryService.Split.ZERO : seq.get(i));
                // 요약과 같은 규칙 — 부호로 가른다(선결제 카드가 두 번 깎이지 않게).
                if (bal < 0) {
                    debt += -bal;
                } else {
                    assets += bal;
                }
            }
            LocalDate m = labels.get(i);
            points.add(new AssetServiceDto.NetWorthTrendPoint(m.getYear(), m.getMonthValue(), assets - debt));
        }
        return points;
    }

    @Override
    public List<AssetServiceDto.AssetBalancePoint> getAssetBalanceTrend(Long assetId, Long userRowId, Integer weeks) {
        if (weeks != null && weeks < 0) {
            throw new InvalidValueException(DeskErrorCode.INVALID_INPUT);
        }
        int n = (weeks == null || weeks < 1) ? 12 : Math.min(weeks, 104);
        log.debug("자산 잔액 추이 조회: assetId={}, weeks={}", assetId, n);

        Asset asset = findAssetOrThrow(assetId);
        validateAssetOwnership(asset, userRowId);

        // window: 이번 주 월요일 기준 n-1주 전 ~ 이번 주
        LocalDate today = userClock.today(userRowId);
        LocalDate currentMonday = today.with(DayOfWeek.MONDAY);
        LocalDate firstMonday = currentMonday.minusWeeks(n - 1);
        LocalDateTime now = userClock.now(userRowId);

        // 주마다 쿼리를 날리면 26주에 26번이다 — 시점 목록을 한 번에 넘겨 집계 2회로 끝낸다.
        List<LocalDate> weekStarts = new ArrayList<>(n);
        List<LocalDateTime> asOfs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate weekStart = firstMonday.plusWeeks(i);
            weekStarts.add(weekStart);
            // 그 주 일요일 끝 시점(미래면 지금) 기준 잔액
            LocalDateTime asOf = weekStart.plusDays(6).atTime(LocalTime.MAX);
            asOfs.add(asOf.isAfter(now) ? now : asOf);
        }
        List<AssetBalanceHistoryService.Split> series = balanceHistoryService
            .balancesAtPoints(List.of(asset), asOfs)
            .getOrDefault(asset.getRowId(), List.of());

        List<AssetServiceDto.AssetBalancePoint> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long balance = i < series.size() ? series.get(i).total() : 0L;
            points.add(new AssetServiceDto.AssetBalancePoint(weekStarts.get(i), balance));
        }
        return points;
    }

    /**
     * 기준시각의 순자산 = 모든 자산 잔액의 합. summary/trend 가 공유.
     *
     * <p>부채는 잔액이 음수라 그냥 더하면 빠진다. 유형으로 가르고 abs() 를 씌우면
     * 선결제한 카드(양수)가 자산에서 빠지고 부채로도 더해져 두 번 깎인다.
     *
     * <p>외화 자산은 원화로 환산해 더한다(예수금만 환율을 곱하고 보유 평가는 원화 그대로. 환산하지 않으면 통화 단위가 섞여 합계가 무의미해진다).
     */
    private long netWorthOf(List<Asset> included, Map<Long, AssetBalanceHistoryService.Split> balances) {
        long net = 0;
        for (Asset a : included) {
            net += krwOf(balances, a);
        }
        return net;
    }

    /** 집계 결과에서 그 자산의 원화 환산 합계. 이력이 없으면 0. */
    private static long krwOf(Map<Long, AssetBalanceHistoryService.Split> balances, Asset a) {
        return krwOf(a, balances.getOrDefault(a.getRowId(), AssetBalanceHistoryService.Split.ZERO));
    }

    /**
     * 원화 환산 합계 = round(예수금 × 환율) + 보유 평가(원화). 요약·전월·추이·자산 응답이 모두 이 한 식을 쓴다.
     * 총잔액에 환율을 곱하면 원화인 보유 평가에 환율이 이중으로 곱해진다.
     */
    private static long krwOf(Asset a, AssetBalanceHistoryService.Split split) {
        return a.balanceInKrw(split.cash(), split.holding());
    }

    private List<Asset> includedAssets(Long userRowId) {
        return assetRepository.findByUser(userRowId).stream()
            .filter(a -> a.getIsIncludedInTotal() == YNType.Y)
            .toList();
    }

    @Override
    @Transactional
    public void reorderAssets(Long userRowId, List<AssetServiceDto.ReorderItem> items) {
        log.debug("자산 정렬 변경: userRowId={}, count={}", userRowId, items.size());

        for (AssetServiceDto.ReorderItem item : items) {
            Asset asset = findAssetOrThrow(item.assetId());
            validateAssetOwnership(asset, userRowId); // 남의 자산 순서 조작 차단
            asset.updateSortOrder(item.sortOrder());
        }

        log.info("자산 정렬 변경 완료: userRowId={}", userRowId);
    }

    @Override
    @Transactional
    public AssetServiceDto.TransferInfo createTransfer(AssetServiceDto.CreateTransferCommand command) {
        log.debug("자산 이체 시작: from={}, to={}, amount={}", command.fromAssetRowId(), command.toAssetRowId(), command.amount());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        TransferParties parties = validateTransfer(command.userRowId(), command.fromAssetRowId(),
            command.toAssetRowId(), command.amount(), command.fee(), command.interestAmount());
        Asset fromAsset = parties.from();
        Asset toAsset = parties.to();
        long interest = parties.interest();

        AssetTransfer transfer = AssetTransfer.createTransfer(
            user, fromAsset, toAsset,
            command.amount(), command.fee(), interest, command.description(), command.transferDate()
        );

        // 금액 · 수수료 · 이자는 보내는 계좌 통화다. 통화가 다른 두 자산이면 받는 쪽 금액(받는 쪽 통화)을
        // 박아 둔다 — 같으면 NULL(보낸 금액과 같음).
        transfer.resolveToAmount(command.toAmount());
        if (command.autoSource() != null) {
            transfer.markAutoGenerated(command.autoSource());
        }
        assetTransferRepository.save(transfer);

        // 이자는 부채를 줄이지 않고 은행으로 나가는 비용 — 지출 거래로 따로 잡아
        // 카테고리·예산·통계에 들어가게 한다. 이체를 지우면 이 거래도 함께 지운다.
        if (transfer.hasInterest()) {
            Expense interestExpense = interestExpenseOf(user, fromAsset, interest,
                command.description(), command.transferDate(), null);
            interestExpense.markAutoGenerated("TRANSFER_INTEREST");
            expenseRepository.save(interestExpense);
            transfer.linkInterestExpense(interestExpense.getRowId());
            log.debug("대출 이자 지출 생성: transferId={}, expenseId={}, interest={}",
                transfer.getRowId(), interestExpense.getRowId(), interest);
        }

        // 자산 잔액 이력: 출금/입금 flow 2건 적재 — 잔액은 조회할 때 집계한다
        // 이자 지출은 잔액에 또 반영하지 않는다 — 출금액(amount)에 이미 포함돼 있어 이중 차감이 된다.
        balanceHistoryService.recordTransfer(transfer);
        log.info("자산 이체 완료: transferId={}", transfer.getRowId());

        return AssetServiceDto.TransferInfo.from(transfer);
    }

    @Override
    public List<AssetServiceDto.TransferInfo> getTransfers(Long userRowId, LocalDate startDate, LocalDate endDate) {
        log.debug("자산 이체 목록 조회: userRowId={}", userRowId);

        return assetTransferRepository.findByUser(userRowId, startDate, endDate).stream()
            .map(AssetServiceDto.TransferInfo::from)
            .toList();
    }

    /**
     * 잠긴 이체의 안내 문구를 출처에 맞춰 고른다.
     *
     * <p>카드 결제를 무르려는 사용자에게 "원래 매수를 취소하면 사라진다" 는 엉뚱한 안내다.
     * 카드는 카드 결제 취소로 가라고 알려 준다.
     */
    private static DeskErrorCode autoTransferErrorOf(AssetTransfer transfer) {
        return switch (String.valueOf(transfer.getAutoSource())) {
            case "CARD_PAYMENT" -> DeskErrorCode.ASSET_TRANSFER_CARD_PAYMENT_READONLY;
            // 환급은 카드 잔액을 0 으로 맞추려고 만든 이체다. 이것만 고치면 잔액이 다시
            // 양수로 뜨고, 다음 자정에 또 환급이 나간다. 잘못된 환급은 잔액 수동 보정으로
            // 정정하면 청구 캡·환급 스윕이 잔액 기준이라 다음 실행에서 알아서 수렴한다.
            case "CARD_REFUND" -> DeskErrorCode.ASSET_TRANSFER_CARD_REFUND_READONLY;
            default -> DeskErrorCode.ASSET_TRANSFER_AUTO_GENERATED_READONLY;
        };
    }

    /**
     * 이체 이자 지출 — 이자는 보내는 계좌 통화라, 지출 금액(원화 칸)은 {@code 계좌.toKrw(이자)} 로 두고
     * 외화 계좌면 원 통화 금액 · 통화 · 환율을 함께 남긴다. 원화 계좌는 지금과 같다(이자 그대로).
     *
     * @param keep 돈 칸이 그대로인 수정이면 옛 이자 지출 — 그 원화 금액 · 원 통화 정보를 그대로 쓴다
     */
    private static Expense interestExpenseOf(User user, Asset from, long interest, String description,
                                             LocalDateTime at, Expense keep) {
        long krw;
        java.math.BigDecimal original = null;
        String originalCurrency = null;
        java.math.BigDecimal rate = null;
        if (keep != null && keep.getAmount() != null) {
            krw = keep.getAmount();
            original = keep.getOriginalAmount();
            originalCurrency = keep.getOriginalCurrency();
            rate = keep.getExchangeRate();
        } else {
            krw = from.toKrw(interest);
            if (from.isForeignCurrency()) {
                original = java.math.BigDecimal.valueOf(interest);
                originalCurrency = from.getCurrency();
                rate = from.getExchangeRate();
            }
        }
        // 잔액 이력은 남기지 않는다 — 이자는 출금액(amount)에 이미 들어 있다.
        return Expense.createExpense(user, null, from, ExpenseType.EXPENSE, krw,
            description, at, null, "TRANSFER", null, original, originalCurrency, rate);
    }

    /**
     * 자산 통화 규칙 — 생성 · 수정이 같은 규칙을 쓴다.
     * <ul>
     *   <li>신용 · 체크카드는 원화만. 청구 · 상계 · 결제 · 이월이 모두 원화 장부라 카드 통화를 바꾸면
     *       카드 흐름과 청구가 다른 단위가 되어 과납 환급이 없는 돈을 만든다. 해외 결제는 거래의 통화 칸으로.</li>
     *   <li>외화 자산은 환율이 0 보다 커야 한다. 비우면 서버가 1 로 저장해 $1 이 1원으로 잡혔다.</li>
     * </ul>
     */
    static void validateCurrency(AssetType type, String currency, java.math.BigDecimal exchangeRate) {
        boolean foreign = currency != null && !currency.isBlank() && !"KRW".equalsIgnoreCase(currency.trim());
        if (!foreign) {
            return;
        }
        if (type == AssetType.CREDIT_CARD || type == AssetType.CHECK_CARD) {
            throw new InvalidValueException(DeskErrorCode.ASSET_CARD_FOREIGN_CURRENCY);
        }
        if (exchangeRate == null || exchangeRate.signum() <= 0) {
            throw new InvalidValueException(DeskErrorCode.ASSET_FOREIGN_RATE_REQUIRED);
        }
    }

    /** 이체 양쪽 자산과 검증된 이자. 생성·수정이 같은 규칙을 쓴다. */
    private record TransferParties(Asset from, Asset to, long interest) {}

    private TransferParties validateTransfer(Long userRowId, Long fromAssetRowId, Long toAssetRowId,
                                             Long amount, Long fee, Long interestAmount) {
        // 값 규칙은 반복·프리셋 예약과 한 벌을 쓴다(AssetTransferRules).
        long interest = AssetTransferRules.validateAmounts(fromAssetRowId, toAssetRowId, amount, fee, interestAmount);

        Asset fromAsset = findAssetOrThrow(fromAssetRowId);
        validateAssetOwnership(fromAsset, userRowId);
        Asset toAsset = findAssetOrThrow(toAssetRowId);
        validateAssetOwnership(toAsset, userRowId);

        // 체크카드는 잔액을 들지 않는다 — 긁는 즉시 연결 계좌에서 빠지므로 이체할 잔액이 없다.
        // 신용카드는 반대로 결제일 자동이체의 대상이라 그대로 허용한다.
        if (fromAsset.getAssetType() == AssetType.CHECK_CARD
            || toAsset.getAssetType() == AssetType.CHECK_CARD) {
            throw new InvalidValueException(DeskErrorCode.ASSET_TRANSFER_CHECK_CARD);
        }

        return new TransferParties(fromAsset, toAsset, interest);
    }

    /**
     * 이체 수정 — 부수효과를 되돌렸다가 새 값으로 다시 만든다.
     *
     * <p>이체 하나가 이자 지출·잔액 이력 2건·카드 청구 회차를 끌고 다닌다. 필드만 바꾸면
     * 그것들이 옛 값으로 남아 잔액이 어긋난다. 그래서 지웠다 만드는 것과 같은 일을 하되
     * <b>rowId 는 유지</b>한다 — 이 이체를 가리키던 참조가 끊기지 않는다.
     */
    @Override
    @Transactional
    public AssetServiceDto.TransferInfo updateTransfer(Long transferId,
                                                       AssetServiceDto.CreateTransferCommand command) {
        log.debug("자산 이체 수정 시작: transferId={}", transferId);

        AssetTransfer transfer = assetTransferRepository.findById(transferId)
            .filter(t -> t.getIsDeleted() == YNType.N)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.ASSET_TRANSFER_NOT_FOUND));
        validateTransferOwnership(transfer, command.userRowId());

        // 매수가 만든 충당 이체는 금액이 그 매수와 묶여 있다 — 여기서 고치면 앞뒤가 안 맞는다.
        if (transfer.isAutoGenerated()) {
            throw new InvalidValueException(autoTransferErrorOf(transfer));
        }

        TransferParties parties = validateTransfer(command.userRowId(), command.fromAssetRowId(),
            command.toAssetRowId(), command.amount(), command.fee(), command.interestAmount());

        // 돈 칸(두 계좌 · 금액 · 수수료 · 이자)이 그대로면 받은 금액 · 잔액 기록 · 이자 원화액을 만들 때 값
        // 그대로 둔다. 다시 계산하면 그 사이 계좌 환율을 고친 외화 이체가 메모만 고쳐도 잔액이 바뀐다
        // (원 → USD 1,300,000원: 1,000 → 환율 1,350 이면 963).
        boolean moneySame = transfer.sameMoneyAs(parties.from(), parties.to(),
            command.amount(), command.fee(), parties.interest());

        // ── 옛 값이 남긴 것들을 먼저 걷어낸다 ──────────────────────────
        Expense oldInterest = transfer.getInterestExpenseRowId() != null
            ? expenseRepository.findById(transfer.getInterestExpenseRowId()).orElse(null) : null;
        if (oldInterest != null) {
            oldInterest.deleteExpense();
        }
        if (!moneySame) {
            balanceHistoryService.removeTransfer(transferId);
        }

        // ── 새 값으로 다시 만든다 ────────────────────────────────────
        transfer.updateTransfer(parties.from(), parties.to(), command.amount(), command.fee(),
            parties.interest(), command.description(), command.transferDate());

        if (transfer.hasInterest()) {
            Expense interestExpense = interestExpenseOf(transfer.getUser(), parties.from(), parties.interest(),
                command.description(), command.transferDate(), moneySame ? oldInterest : null);
            interestExpense.markAutoGenerated("TRANSFER_INTEREST");
            expenseRepository.save(interestExpense);
            transfer.linkInterestExpense(interestExpense.getRowId());
        }
        if (moneySame && balanceHistoryService.retimeTransfer(transferId, command.transferDate())) {
            // to_amount 는 updateTransfer 가 건드리지 않는다 — 저장값 그대로.
            log.debug("이체 돈 칸 그대로 — 받은 금액 · 잔액 기록 유지: transferId={}", transferId);
        } else {
            if (moneySame) {
                balanceHistoryService.removeTransfer(transferId); // 옮길 행이 없었다 — 새로 적는다
            }
            // 받는 금액을 새 값 · 새 계좌로 다시 정한다(지금 계좌 환율). 사용자 수정이라 덮어쓸 값은 없다.
            transfer.resolveToAmount(null);
            balanceHistoryService.recordTransfer(transfer);
        }

        log.info("자산 이체 수정 완료: transferId={}", transferId);
        return AssetServiceDto.TransferInfo.from(transfer);
    }

    /**
     * 사용자가 누른 삭제 — 시스템이 만든 이체는 막는다.
     *
     * <p>{@link #deleteTransfer} 자체는 막지 않는다. 매수를 취소하면 그 매수가 만든 충당
     * 이체도 지워야 하고, 카드 결제를 무르면 그 이체도 지워야 한다 — 막아 두면 그쪽이 깨진다.
     * 잠글 곳은 <b>사용자 경로</b>뿐이다.
     */
    @Override
    @Transactional
    public void deleteTransferByUser(Long transferId, Long userRowId) {
        AssetTransfer transfer = assetTransferRepository.findById(transferId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.ASSET_TRANSFER_NOT_FOUND));
        validateTransferOwnership(transfer, userRowId);
        if (transfer.isAutoGenerated()) {
            throw new InvalidValueException(autoTransferErrorOf(transfer));
        }
        deleteTransfer(transferId, userRowId);
    }

    @Override
    @Transactional
    public void deleteTransfer(Long transferId, Long userRowId) {
        log.debug("자산 이체 삭제 시작: transferId={}", transferId);

        AssetTransfer transfer = assetTransferRepository.findById(transferId)
            .orElseThrow(() -> {
                log.warn("자산 이체 조회 실패: transferId={}", transferId);
                return new EntityNotFoundException(DeskErrorCode.ASSET_TRANSFER_NOT_FOUND);
            });
        validateTransferOwnership(transfer, userRowId);

        transfer.deleteTransfer();
        // 이자로 만들어 둔 지출 거래도 함께 무른다 — 남겨두면 이체는 사라졌는데
        // 그 달 지출에 이자만 유령처럼 남는다.
        if (transfer.getInterestExpenseRowId() != null) {
            expenseRepository.findById(transfer.getInterestExpenseRowId())
                .ifPresent(Expense::deleteExpense);
        }
        // 자산 잔액 이력: 양쪽 flow soft-delete — 잔액은 조회할 때 집계한다
        balanceHistoryService.removeTransfer(transferId);
        // 카드 결제로 만들어진 이체였다면 그 청구 회차도 함께 무른다. COMPLETED 로 남겨두면
        // '이미 낸 회차' 로 집계돼(선결제 차감) 다음 청구액이 0 이 되고, 잔액만 되돌아온 채
        // 카드 부채가 영원히 안 갚아진다.
        // 환급 이체 하나가 여러 회차의 REFUNDED 행에 묶일 수 있다 — 전부 무른다.
        cardBillingRepository.findAllActiveByTransfer(transferId).forEach(CardBilling::cancel);
        log.info("자산 이체 삭제 완료: transferId={}", transferId);
    }

    private CardCatalog resolveCardCatalog(Long cardCatalogRowId) {
        if (cardCatalogRowId == null) {
            return null;
        }
        return cardCatalogRepository.findById(cardCatalogRowId)
            .orElseThrow(() -> {
                log.warn("카드 카탈로그 조회 실패 - 존재하지 않는 카드: rowId={}", cardCatalogRowId);
                return new EntityNotFoundException(DeskErrorCode.CARD_CATALOG_NOT_FOUND);
            });
    }

    /**
     * 결제계좌 id 를 자산으로 — 내가 지운 계좌면 '연결 없음'(null) 이다(QA 30 6).
     *
     * <p>지운 계좌는 화면 목록에 안 나오므로 그 id 는 지우기 전에 연 폼에서만 온다. 삭제가 카드의
     * 연결을 이미 끊었으니 여기서도 없는 것으로 받는다. 남의 자산·없는 id 는 종전대로 막는다.
     */
    private Asset resolvePaymentAssetOrNone(Long paymentAssetRowId, Long userRowId) {
        if (paymentAssetRowId != null && assetRepository.existsDeletedOwnedBy(paymentAssetRowId, userRowId)) {
            return null;
        }
        return resolvePaymentAsset(paymentAssetRowId, userRowId);
    }

    private Asset resolvePaymentAsset(Long paymentAssetRowId, Long userRowId) {
        if (paymentAssetRowId == null) {
            return null;
        }
        Asset paymentAsset = findAssetOrThrow(paymentAssetRowId);
        validateAssetOwnership(paymentAsset, userRowId);
        return paymentAsset;
    }

    private void validateAssetOwnership(Asset asset, Long userRowId) {
        if (!asset.getUser().getRowId().equals(userRowId)) {
            log.warn("자산 소유권 검증 실패 - assetId={}, ownerRowId={}, requestUserRowId={}",
                asset.getRowId(), asset.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.ASSET_ACCESS_DENIED);
        }
    }

    private void validateTransferOwnership(AssetTransfer transfer, Long userRowId) {
        if (!transfer.getUser().getRowId().equals(userRowId)) {
            log.warn("자산 이체 소유권 검증 실패 - transferId={}, ownerRowId={}, requestUserRowId={}",
                transfer.getRowId(), transfer.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.ASSET_ACCESS_DENIED);
        }
    }

    private Asset findAssetOrThrow(Long assetId) {
        return assetRepository.findById(assetId)
            .orElseThrow(() -> {
                log.warn("자산 조회 실패 - 존재하지 않는 자산: assetId={}", assetId);
                return new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND);
            });
    }

    /**
     * 신용카드의 "이전 미결제 사용액" 거래를 만들거나 고치거나 지운다(D4).
     *
     * <p>카드를 만들 때 적는 사용액은 "그 시점 미결제 스냅샷" 이다. 종전엔 그걸 잔액 앵커로
     * 찍었는데, 앵커는 <b>그 뒤 흐름만</b> 세므로 나중에 적은 지난 날짜 지출이 청구에는
     * 들어가고 잔액에는 안 잡혔다. 거래로 두면 두 숫자가 같은 재료에서 나오고, 이 거래가
     * 속한 회차에 자연히 청구된다.
     *
     * <p>시스템이 만든 거래라 가계부에서 직접 고칠 수 없다({@code CARD_CARRYOVER}) —
     * 고치는 자리는 카드 편집 폼의 그 칸 하나다. 0 이면 지운다.
     *
     * @param signedAmount 부호가 씌워진 잔액(카드는 음수) — 사용액은 그 절댓값이다
     */
    private void syncCardCarryover(Asset asset, Long signedAmount, LocalDateTime at) {
        if (asset.getAssetType() != AssetType.CREDIT_CARD) {
            return;
        }
        long usage = signedAmount == null ? 0L : Math.abs(signedAmount);
        Expense existing = expenseRepository
            .findActiveByAssetAndAutoSource(asset.getRowId(), Expense.AUTO_SOURCE_CARD_CARRYOVER)
            .orElse(null);
        // 이월 거래가 든 회차의 결제일이 됐으면 그 금액은 이미 결제에 들어갔다 — 고치면 결제와 기록이
        // 갈린다(D15). 같은 값은 통과(폼이 그대로 되돌려 보낸다).
        if (existing != null && !Objects.equals(existing.getAmount(), usage)
                && carryoverLocked(existing, paymentScheduleService.scheduleOf(asset), serviceClock.today())) {
            throw new InvalidValueException(DeskErrorCode.ASSET_CARD_CARRYOVER_LOCKED);
        }

        if (usage <= 0L) {
            if (existing != null) {
                existing.deleteExpense();
                balanceHistoryService.removeExpense(existing.getRowId());
                log.info("카드 이월 거래 삭제: assetId={}, expenseId={}",
                    asset.getRowId(), existing.getRowId());
            }
            return;
        }

        if (existing != null) {
            if (Objects.equals(existing.getAmount(), usage)) {
                return;
            }
            existing.updateAmountOnly(usage);
            // 흐름은 지웠다 다시 깐다 — 금액만 고치는 경로가 따로 없다(수정과 같은 순서).
            balanceHistoryService.removeExpense(existing.getRowId());
            balanceHistoryService.recordExpense(asset, existing.getRowId(),
                ExpenseType.EXPENSE, usage, existing.getExpenseDate());
            log.info("카드 이월 거래 금액 변경: assetId={}, expenseId={}, amount={}",
                asset.getRowId(), existing.getRowId(), usage);
            return;
        }

        Expense carryover = Expense.createExpense(
            asset.getUser(), null, asset, ExpenseType.EXPENSE, usage,
            CARD_CARRYOVER_LABEL, at, CARD_CARRYOVER_LABEL, null, null, null, null, null);
        carryover.markAutoGenerated(Expense.AUTO_SOURCE_CARD_CARRYOVER);
        expenseRepository.save(carryover);
        balanceHistoryService.recordExpense(asset, carryover.getRowId(),
            ExpenseType.EXPENSE, usage, at);
        log.info("카드 이월 거래 생성: assetId={}, expenseId={}, amount={}",
            asset.getRowId(), carryover.getRowId(), usage);
    }

    /**
     * 결제를 기다리던 지난달 청구분 거래를 만들거나 고치거나 지운다(2026-09-22 사용자 결정).
     *
     * <p>결제일 전에 카드를 등록하면 실제 카드사는 지난달 청구분을 다가오는 결제일에, 이번 달 쓴
     * 금액을 그다음 결제일에 뺀다. 한 건({@link #syncCardCarryover})으로 두면 등록한 날의 회차에
     * 한꺼번에 청구돼 한 달 동안 통장 잔액이 실제보다 많았다. 그래서 이 몫은 <b>그 회차의 말일</b>
     * 거래로 둔다 — 결제 스케줄러가 다가오는 결제일에 그 회차로 청구한다(카드는 잔액 앵커가 먼 과거라
     * 등록 전 날짜의 거래도 잔액·빚에 잡힌다, D4).
     *
     * <p>넣고 고칠 수 있는 건 <b>등록한 달의 그 회차 결제일 전까지</b>다. 지나면 이미 결제에
     * 들어갔으므로 고치면 결제와 기록이 갈린다(D15 와 같다). 0 이면 지운다.
     *
     * @param requested  청구분(양수로 받는다 — 부호는 여기서 정하지 않는다). null 이면 무변경
     * @param registered 카드를 등록한 날(사용자 달력) — 그 달의 전달 회차가 대상이다
     */
    private void syncDueCarryover(Asset asset, Long requested, LocalDate registered) {
        if (asset.getAssetType() != AssetType.CREDIT_CARD || requested == null) {
            return;
        }
        long due = Math.abs(requested);
        io.groo.damda.card.service.PaymentSchedule schedule = paymentScheduleService.scheduleOf(asset);
        LocalDate today = serviceClock.today();
        Expense existing = expenseRepository
            .findActiveByAssetAndAutoSource(asset.getRowId(), Expense.AUTO_SOURCE_CARD_CARRYOVER_DUE)
            .orElse(null);

        if (existing != null) {
            if (Objects.equals(existing.getAmount(), due)) {
                return; // 같은 값은 통과 — 폼이 그대로 되돌려 보낸다
            }
            if (carryoverLocked(existing, schedule, today)) {
                throw new InvalidValueException(DeskErrorCode.ASSET_CARD_CARRYOVER_LOCKED);
            }
            if (due <= 0L) {
                existing.deleteExpense();
                balanceHistoryService.removeExpense(existing.getRowId());
                log.info("카드 결제 대기 청구분 삭제: assetId={}, expenseId={}", asset.getRowId(), existing.getRowId());
                return;
            }
            existing.updateAmountOnly(due);
            balanceHistoryService.removeExpense(existing.getRowId());
            balanceHistoryService.recordExpense(asset, existing.getRowId(),
                ExpenseType.EXPENSE, due, existing.getExpenseDate());
            log.info("카드 결제 대기 청구분 변경: assetId={}, expenseId={}, amount={}",
                asset.getRowId(), existing.getRowId(), due);
            return;
        }
        if (due <= 0L) {
            return;
        }

        LocalDate dueCycle = dueCycleFor(registered, schedule);
        if (dueCycle == null || schedule.isClosed(dueCycle, today)) {
            throw new InvalidValueException(DeskErrorCode.ASSET_CARD_DUE_CARRYOVER_CLOSED);
        }
        LocalDateTime at = io.groo.damda.card.service.CardCycleMath.cycleEndOf(dueCycle).atStartOfDay();
        Expense carryover = Expense.createExpense(
            asset.getUser(), null, asset, ExpenseType.EXPENSE, due,
            CARD_CARRYOVER_LABEL, at, CARD_CARRYOVER_LABEL, null, null, null, null, null);
        carryover.markAutoGenerated(Expense.AUTO_SOURCE_CARD_CARRYOVER_DUE);
        expenseRepository.save(carryover);
        balanceHistoryService.recordExpense(asset, carryover.getRowId(), ExpenseType.EXPENSE, due, at);
        log.info("카드 결제 대기 청구분 생성: assetId={}, expenseId={}, amount={}, 회차={}",
            asset.getRowId(), carryover.getRowId(), due, dueCycle);
    }

    /** 그 거래가 내역에서 보일 이름 — 카테고리는 두지 않는다(사용자 분류가 아니다). */
    private static final String CARD_CARRYOVER_LABEL = "이전 미결제 사용액";
}
