package io.groo.damda.card.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.domain.AssetTransfer;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.asset.service.AssetBalanceHistoryService;
import io.groo.damda.asset.service.AssetService;
import io.groo.damda.asset.service.dto.AssetServiceDto;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.card.domain.CardBilling;
import io.groo.damda.card.repository.CardBillingRepository;
import io.groo.damda.card.service.dto.CardPaymentServiceDto;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.type.YNType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.type.ExpenseType;
import jakarta.persistence.EntityManager;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.groo.damda.card.type.BillingStatus;
import java.util.Objects;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CardPaymentServiceImpl implements CardPaymentService {

    private final CardBillingRepository cardBillingRepository;
    private final UserClock userClock;
    private final AssetRepository assetRepository;
    private final AssetService assetService;
    /** 결제계좌 없이 카드만 정리할 때 쓴다 — 이체를 못 만드니 카드에 직접 상계 flow 를 쌓는다. */
    private final AssetBalanceHistoryService balanceHistoryService;
    private final EntityManager entityManager;
    /** 닫힌 회차 판정의 "오늘" — 자정 배치와 같은 서울 시계(D11). */
    private final ServiceClock serviceClock;
    /** 회차별 결제일(D5) — 결제일을 바꿔도 지난 회차의 결제일은 그대로다. */
    private final PaymentScheduleService paymentScheduleService;

    /**
     * 자정 배치에서 <b>카드 한 장마다</b> 새 트랜잭션을 여는 템플릿.
     *
     * <p>{@code @RequiredArgsConstructor} 를 버리고 생성자를 손으로 쓴 이유가 이것 하나다.
     * 자세한 이유는 {@link #processDueCardPayments} 주석에 있다.
     */
    private final TransactionTemplate newTransaction;

    public CardPaymentServiceImpl(CardBillingRepository cardBillingRepository,
                                  UserClock userClock,
                                  AssetRepository assetRepository,
                                  AssetService assetService,
                                  AssetBalanceHistoryService balanceHistoryService,
                                  EntityManager entityManager,
                                  PlatformTransactionManager transactionManager,
                                  ServiceClock serviceClock,
                                  PaymentScheduleService paymentScheduleService) {
        this.cardBillingRepository = cardBillingRepository;
        this.userClock = userClock;
        this.assetRepository = assetRepository;
        this.assetService = assetService;
        this.balanceHistoryService = balanceHistoryService;
        this.entityManager = entityManager;
        this.serviceClock = serviceClock;
        this.paymentScheduleService = paymentScheduleService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public CardPaymentServiceDto.CardBillingInfo getCardBilling(Long cardRowId, Long userRowId) {
        log.debug("카드 청구 조회: cardRowId={}, userRowId={}", cardRowId, userRowId);

        Asset card = findAssetOrThrow(cardRowId);
        validateOwnership(card, userRowId);
        validateCreditCard(card);

        // 결제일 당일부터 그 회차는 닫힌 회차다(D2) — 다가오는 결제는 결제일이 오늘보다 뒤인 첫 회차.
        LocalDate today = cycleToday();
        PaymentSchedule schedule = scheduleOf(card);
        LocalDate nextPaymentDate = schedule.firstOpenPaymentDate(today);
        BillingCycle cycle = upcomingCycle(card, nextPaymentDate);

        List<CardPaymentServiceDto.BillingInfo> history = cardBillingRepository
            .findByCardAssetRowId(cardRowId).stream()
            .map(CardPaymentServiceDto.BillingInfo::from)
            .toList();

        // 다음 회차 — 지금 쌓이는 이용분. 결제일이 지나기 전에도 이번 달에 얼마 썼는지,
        // 미리 얼마를 낼 수 있는지 보여 주려고 하나 더 내려준다.
        CardPaymentServiceDto.UpcomingCycle nextCycle = null;
        if (nextPaymentDate != null) {
            LocalDate following = schedule.followingPaymentDate(nextPaymentDate);
            BillingCycle c2 = upcomingCycle(card, following);
            nextCycle = new CardPaymentServiceDto.UpcomingCycle(following, c2.periodStart(), c2.periodEnd(),
                c2.amount(), c2.lumpSumAmount(), c2.alreadyPaid(), c2.installments());
        }

        return new CardPaymentServiceDto.CardBillingInfo(
            cardRowId,
            cycle.amount(),
            cycle.lumpSumAmount(),
            cycle.alreadyPaid(),
            cycle.installments(),
            cycle.periodStart(),
            cycle.periodEnd(),
            nextPaymentDate,
            card.getPaymentDay(),
            usablePaymentAsset(card) != null ? usablePaymentAsset(card).getRowId() : null,
            history,
            nextCycle,
            closedCycles(card, schedule, today)
        );
    }

    @Override
    @Transactional
    public CardPaymentServiceDto.BillingInfo payCard(Long cardRowId, Long userRowId, Long amount) {
        return payCard(cardRowId, userRowId, amount, null);
    }

    @Override
    @Transactional
    public CardPaymentServiceDto.BillingInfo payCard(Long cardRowId, Long userRowId, Long amount,
                                                     LocalDate paymentDate) {
        log.debug("카드 수동 결제 시작: cardRowId={}, userRowId={}, amount={}, paymentDate={}",
            cardRowId, userRowId, amount, paymentDate);

        Asset card = findAssetOrThrow(cardRowId);
        validateOwnership(card, userRowId);
        validateCreditCard(card);

        // 결제계좌는 필수가 아니다 — 이건 기록용 앱이라 통장을 안 적고 가계부+카드만 쓰는
        // 사용자가 있다. 계좌가 없으면 이체 없이 카드 사용액만 정리한다(규칙6 과 같은 논리:
        // 등록 안 한 자산은 애초에 순자산에 안 잡혀 있으므로 카드 쪽만 맞추면 된다).
        Asset paymentAsset = usablePaymentAsset(card);

        // 수동 결제 = 선택한 회차의 선결제 — 금액·기간 귀속 모두 그 회차 기준.
        // (종전엔 잔액 전액 + 실행일의 전월 라벨이라 회차·기간·금액이 어긋났음)
        // paymentDate 가 없으면 다가오는 회차, 있으면 다가오는 회차 또는 그 다음 회차(지금 쌓이는
        // 이용분)만 허용한다 — 그 밖의 날짜는 화면이 내려준 회차가 아니므로 거절한다.
        LocalDate today = cycleToday();
        PaymentSchedule schedule = scheduleOf(card);
        LocalDate nextPaymentDate = schedule.firstOpenPaymentDate(today);
        LocalDate target = nextPaymentDate;
        if (paymentDate != null && nextPaymentDate != null && !paymentDate.equals(nextPaymentDate)) {
            LocalDate following = schedule.followingPaymentDate(nextPaymentDate);
            if (!paymentDate.equals(following)) {
                throw new InvalidValueException(DeskErrorCode.CARD_BILLING_INVALID_CYCLE);
            }
            target = following;
        }
        BillingCycle cycle = upcomingCycle(card, target);
        long remaining = cycle.amount();
        LocalDate periodStart = cycle.periodStart();
        LocalDate periodEnd = cycle.periodEnd();

        // 부분 선결제 — 남은 청구액 안에서만. 청구액은 이미 '사용액 − 이미 결제한 금액' 이라
        // 나머지는 다음 결제일에 정상적으로 빠진다.
        long payAmount = amount != null ? amount : remaining;
        if (amount != null && (amount <= 0L || amount > remaining)) {
            throw new InvalidValueException(DeskErrorCode.CARD_BILLING_INVALID_AMOUNT);
        }

        if (payAmount == 0L) {
            CardBilling billing = cardBillingRepository.save(
                CardBilling.skipped(card, paymentAsset, periodStart, periodEnd, today));
            log.info("카드 수동 결제 건너뜀(청구액 0): cardRowId={}", cardRowId);
            return CardPaymentServiceDto.BillingInfo.from(billing);
        }

        // 잔액 부족을 막지 않는다 — 기록용 앱이라 통장 잔액을 안 맞춰 둔 사용자가 많고,
        // 실제로는 결제됐는데 앱에서만 결제가 안 되는 상태를 만들 이유가 없다(마이너스 통장도 있다).
        // 이체 시각은 누른 시각이다 — 결제일 00:00 으로 찍으면 같은 날 그 뒤에 만든 통장 INIT 이나
        // 카드 잔액 수정(MANUAL) 앵커보다 과거가 되어 양쪽 잔액 집계에서 사라진다(이체 행은 있는데
        // 통장·카드가 그대로). 자동 결제는 결제일 자정에 도는 배치라 그날 시작이 곧 실행 시각이다.
        CardBilling billing = cardBillingRepository.save(payoff(
            card, paymentAsset, payAmount, periodStart, periodEnd, today, userRowId,
            userClock.now(userRowId)));

        log.info("카드 수동 결제 완료: cardRowId={}, amount={}, 남은청구액={}",
            cardRowId, payAmount, remaining - payAmount);
        return CardPaymentServiceDto.BillingInfo.from(billing);
    }

    @Override
    @Transactional
    public void cancelPayment(Long billingRowId, Long userRowId) {
        log.debug("카드 결제 취소 시작: billingRowId={}", billingRowId);

        CardBilling billing = cardBillingRepository.findById(billingRowId)
            .filter(b -> b.getIsDeleted() == YNType.N)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.CARD_BILLING_NOT_FOUND));
        if (!Objects.equals(billing.getCardAsset().getUser().getRowId(), userRowId)) {
            throw new InvalidValueException(DeskErrorCode.ASSET_ACCESS_DENIED);
        }
        // 이미 무른 회차(취소·건너뜀)는 되돌릴 게 없다.
        if (billing.getStatus() != BillingStatus.COMPLETED) {
            throw new InvalidValueException(DeskErrorCode.CARD_BILLING_NOT_CANCELABLE);
        }
        // 결제일이 된 회차는 무를 수 없다(D6) — 되돌리면 그 회차를 다시 낼 길이 없어 빚이 떠돈다.
        Asset card = billing.getCardAsset();
        if (billing.getPeriodStart() != null
                && scheduleOf(card).isClosed(billing.getPeriodStart(), cycleToday())) {
            throw new InvalidValueException(DeskErrorCode.CARD_BILLING_CYCLE_CLOSED);
        }
        // 그 회차에서 환급이 나갔으면 무를 수 없다(D6) — 결제만 되돌리면 환급은 통장에 남아 유령 빚이 된다.
        if (billing.getPeriodStart() != null && cardBillingRepository.sumRefundedAmountByCardAndPeriod(
                card.getRowId(), billing.getPeriodStart(), billing.getPeriodEnd()) > 0L) {
            throw new InvalidValueException(DeskErrorCode.CARD_BILLING_REFUNDED);
        }

        // 결제로 만든 이체를 무른다. 그 안에서 잔액 이력이 되돌아가고 청구 회차도 cancel 된다
        // (deleteTransfer 가 findActiveByTransfer 로 찾아 취소한다) — 여기서 또 부르지 않는다.
        if (billing.getTransfer() != null) {
            assetService.deleteTransfer(billing.getTransfer().getRowId(), userRowId);
        } else {
            // 이체 없이 기록만 남은 회차 — 청구만 되돌린다.
            billing.cancel();
        }

        log.info("카드 결제 취소 완료: billingRowId={}, cardRowId={}",
            billingRowId, billing.getCardAsset().getRowId());
    }

    /**
     * 자정 배치 — 신용카드를 <b>한 장씩 따로</b> 처리한다.
     *
     * <p>배치 전체가 트랜잭션 하나면 한 장의 실패가 그날 처리분을 통째로 되돌린다. 아래
     * {@code catch} 는 예외를 삼키지만 트랜잭션은 그것과 별개로 이미 <b>rollback-only</b> 로
     * 찍혀 있어(프록시를 지나는 {@code assetService.createTransfer} 에서 터지면 특히 그렇다)
     * 마지막 커밋이 {@code UnexpectedRollbackException} 으로 끝난다. 결제계좌를 지워 둔 카드
     * 한 장 때문에 남의 카드 청구 회차·이체까지 안 남는데, 로그에는 "성공 N, 실패 1" 로
     * 적혀 아무도 눈치채지 못한다.
     *
     * <p>그래서 이 메서드는 트랜잭션을 들지 않고({@code NOT_SUPPORTED}), 카드 한 장을
     * {@link #newTransaction} 안에서 돌린다. 실패한 장만 롤백되고 나머지는 그대로 커밋된다.
     * 목록은 rowId 만 들고 나온다 — 트랜잭션 밖에서 읽은 엔티티는 준영속이라 그대로 쓰면
     * 잔액 이력·청구가 엉뚱한 세션에 붙는다.
     *
     * <p>반복 거래 배치({@code RecurringTransactionServiceImpl.executeDueTransactions})와 같은
     * 모양이다 — 같은 실패 방식을 두 배치가 공유하고 있었다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processDueCardPayments(LocalDate today) {
        log.debug("자동 카드 결제 처리 시작: today={}", today);

        List<Long> cardRowIds = assetRepository.findAllByType(AssetType.CREDIT_CARD).stream()
            .map(Asset::getRowId)
            .toList();
        int success = 0, failed = 0, skipped = 0;

        for (Long cardRowId : cardRowIds) {
            // 각 건 격리 — 한 카드 실패가 다른 카드의 커밋을 되돌리지 않도록 트랜잭션까지 가른다.
            try {
                PayOutcome outcome =
                    newTransaction.execute(status -> processOneCard(cardRowId, today));
                if (outcome == PayOutcome.PAID) {
                    success++;
                } else if (outcome == PayOutcome.SKIPPED) {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.error("자동 카드 결제 실패: cardRowId={}", cardRowId, e);
            }
        }

        log.info("자동 카드 결제 처리 완료: 대상={}건, 성공={}, 실패={}, 건너뜀={}",
            cardRowIds.size(), success, failed, skipped);
    }

    /**
     * 카드 한 장 — {@link #newTransaction} 이 연 트랜잭션 안에서만 부른다.
     *
     * @return 결제일 처리 결과. 결제일이 아니거나 이미 처리됐으면 집계에 안 든다
     */
    private PayOutcome processOneCard(Long cardRowId, LocalDate today) {
        Asset card = findAssetOrThrow(cardRowId);

        PayOutcome outcome = null;
        // 오늘이 결제일인 회차 — 결제일을 바꿨어도 그 회차에 적용된 결제일로 본다(D5).
        if (scheduleOf(card).cycleDueOn(today) != null) {
            outcome = payDueCard(card, today);
        }
        // 환급은 결제일과 무관하게 매일 본다 — 환불은 아무 날에나 들어오고,
        // 결제일까지 기다리면 그동안 잔액이 양수로 떠 있게 된다.
        refundOverpaymentIfAny(card, today);
        return outcome;
    }

    /** 결제일 처리 결과 — 집계용. */
    private enum PayOutcome { PAID, SKIPPED, ALREADY_DONE }

    /** 결제일 당일 청구 결제. 루프에서 환급 스윕과 나란히 두려고 분리했다. */
    private PayOutcome payDueCard(Asset card, LocalDate today) {
        // 멱등성 — 이번 결제일에 이미 COMPLETED 가 있으면 skip
        if (cardBillingRepository.existsCompletedByCardAndPaymentDate(card.getRowId(), today)) {
            log.debug("자동 카드 결제 멱등 skip: cardRowId={}, date={}", card.getRowId(), today);
            return PayOutcome.ALREADY_DONE;
        }

        Long userRowId = card.getUser().getRowId();
        Asset paymentAsset = usablePaymentAsset(card);
        // 결제일 당일 회차 = 전월 1일~말일 사용분(선결제 차감) — 잔액 전액 아님.
        BillingCycle cycle = upcomingCycle(card, today);
        long amount = cycle.amount();

        if (amount == 0L) {
            cardBillingRepository.save(
                CardBilling.skipped(card, paymentAsset, cycle.periodStart(), cycle.periodEnd(), today));
            return PayOutcome.SKIPPED;
        }
        // 결제계좌 미지정·잔액 부족으로 실패시키지 않는다 — 기록용 앱이라 통장을 안 적거나
        // 잔액을 안 맞춰 둔 사용자가 있고, 막으면 카드 부채가 영원히 안 지워진다.
        cardBillingRepository.save(payoff(
            card, paymentAsset, amount, cycle.periodStart(), cycle.periodEnd(), today, userRowId,
            today.atStartOfDay()));

        log.info("자동 카드 결제 완료: cardRowId={}, amount={}, 결제계좌={}",
            card.getRowId(), amount, paymentAsset != null ? paymentAsset.getRowId() : "미지정");
        return PayOutcome.PAID;
    }

    /**
     * 카드 잔액이 양수(과납·환불 초과분)면 실제 카드사처럼 결제계좌로 돌려준다.
     *
     * <p>결제를 다 낸 뒤 환불이 들어오면 카드 잔액이 양수가 된다. 실제 카드사는 그 돈을
     * 결제계좌로 환급하거나 다음 청구에서 뺀다 — 카드에 잔고가 쌓여 있는 상태로 두지 않는다.
     *
     * <p>멱등은 따로 기록할 필요가 없다. 환급하면 잔액이 0 이 되므로 다음 실행에서 그냥 걸러진다.
     */
    private void refundOverpaymentIfAny(Asset card, LocalDate today) {
        Long userRowId = card.getUser().getRowId();
        long surplus = balanceHistoryService.balanceAt(card, userClock.now(userRowId)).total();
        if (surplus <= 0L) {
            return;
        }

        Asset paymentAsset = usablePaymentAsset(card);
        if (paymentAsset != null) {
            // 카드 → 결제계좌. 결제 이체(createPaymentTransfer)의 거울상이다.
            assetService.createTransfer(new AssetServiceDto.CreateTransferCommand(
                userRowId,
                card.getRowId(),
                paymentAsset.getRowId(),
                surplus,
                0L,
                0L,
                "신용카드 환급",
                today.atStartOfDay(),
                // 환급도 잔액에 묶여 있다 — 이 이체만 따로 고치면 카드 잔액이 다시 어긋난다.
                "CARD_REFUND"
            ));
        } else {
            // 결제계좌를 안 적은 사용자 — 이체를 만들 곳이 없으니 카드 쪽만 0 으로 맞춘다.
            // payoff 의 상계 fallback 과 같은 논리이고 방향만 반대다(+상계 ↔ −상계).
            balanceHistoryService.recordExpense(
                card, null, ExpenseType.EXPENSE, surplus, today.atStartOfDay());
        }

        log.info("카드 과납 환급: cardRowId={}, surplus={}, 결제계좌={}",
            card.getRowId(), surplus, paymentAsset != null ? paymentAsset.getRowId() : "미지정(상계)");
    }

    /**
     * 결제 이체 생성. 회계 정합 필수: 결제는 AssetTransfer 로만 처리(Expense 생성 금지 — 지출 중복계상 방지).
     * from = 결제계좌(차감), to = 카드(잔액 0 복귀). 카드 balance 는 음수(부채)이므로 +amount 로 0 이 된다.
     */
    /**
     * 카드 사용액 정산 — 결제계좌가 있으면 이체로, 없으면 카드에만 상계 flow 로 처리한다.
     *
     * <p>결제계좌를 안 적는 사용자가 있다(가계부+카드만 쓰는 경우). 그때 이체를 못 만든다고
     * 결제를 막으면 카드 사용액이 영원히 안 지워지고 부채로 계속 쌓인다. 등록 안 한 통장은
     * 애초에 순자산에 안 잡혀 있으므로 카드 쪽만 0 으로 맞추면 일관된다(규칙6 과 같은 논리).
     */
    private CardBilling payoff(Asset card, Asset paymentAsset, long amount, LocalDate periodStart,
                               LocalDate periodEnd, LocalDate today, Long userRowId,
                               LocalDateTime transferAt) {
        // 돈은 남은 빚까지만 움직인다 — 청구(기록)는 회차 사용액 전액, 이체는 min(청구, 빚).
        // 잔액을 수동 보정(앵커)해 빚을 지워 둔 카드는 그 지출이 이미 잔액에 정리된 상태라,
        // 전액을 또 이체하면 카드가 양수로 뒤집히고 다음 날 과납 환급 스윕이 도로 돌려보낸다
        // — 아무 일도 아닌 왕복이 기록만 어지럽힌다. 그래서 역전 방지는 표시(upcomingCycle)가
        // 아니라 여기서 건다.
        long move = Math.min(amount, currentDebt(card));
        if (paymentAsset != null && move > 0L) {
            AssetServiceDto.TransferInfo transfer =
                createPaymentTransfer(card, paymentAsset, move, userRowId, transferAt);
            return CardBilling.completed(card, paymentAsset, amount, periodStart, periodEnd, today,
                transferRef(transfer.rowId()));
        }
        if (paymentAsset == null && move > 0L) {
            // 이체 없이 카드 잔액만 되돌린다 — 사용액이 음수로 쌓여 있으므로 +금액 flow.
            balanceHistoryService.recordExpense(
                card, null, ExpenseType.INCOME, move, transferAt);
        }
        // move == 0(빚 없음)이면 돈·잔액을 건드리지 않고 회차 완료만 기록한다.
        return CardBilling.completed(card, paymentAsset, amount, periodStart, periodEnd, today, null);
    }

    private AssetServiceDto.TransferInfo createPaymentTransfer(Asset card, Asset paymentAsset, long amount,
                                                              Long userRowId, LocalDateTime at) {
        // 이체 금액은 보내는 계좌 통화다. 결제 계좌가 외화면 카드 금액(원화)을 그 계좌 통화로 바꿔 보내고,
        // 카드가 받는 금액은 청구액 그대로 못 박는다(왕복 반올림 없이 카드가 정확히 0 이 된다).
        // 카드는 원화만 둔다(AssetServiceImpl.validateCurrency) — 결제 계좌가 외화일 때만 통화가 갈린다.
        boolean sameCurrency = !paymentAsset.isForeignCurrency();
        return assetService.createTransfer(new AssetServiceDto.CreateTransferCommand(
            userRowId,
            paymentAsset.getRowId(),
            card.getRowId(),
            sameCurrency ? amount : paymentAsset.krwToAssetCurrency(amount),
            0L,
            0L, // 카드 결제는 이자 개념이 없다 — 할부 이자는 청구액에 이미 포함돼 들어온다
            "신용카드 자동결제",
            // 수동 결제는 누른 시각, 자동 결제는 결제일 자정(배치 실행 시각) — 호출자가 정한다.
            at,
            // 카드 결제는 청구 회차와 묶여 있다 — 이 이체만 따로 고치면 청구가 어긋난다.
            "CARD_PAYMENT",
            sameCurrency ? null : amount
        ));
    }

    /** 방금 영속화된 AssetTransfer 의 연관 참조 — 동일 트랜잭션이므로 getReference 로 충분. */
    private AssetTransfer transferRef(Long transferRowId) {
        return entityManager.getReference(AssetTransfer.class, transferRowId);
    }

    /** 지금 이 카드에 남은 빚(양수). 잔액이 0 이상이면 빚이 없다는 뜻이라 0. */
    private long currentDebt(Asset card) {
        // 캐시 컬럼이 아니라 이력 집계 — 청구액을 낡은 값으로 잡으면 결제 금액이 틀어진다.
        return Math.max(0L, -balanceHistoryService
            .balanceAt(card, userClock.now(card.getUser().getRowId())).total());
    }

    /** 결제 회차 — 청구 기간(전월 1일~말일)과 그 회차의 결제 필요 잔여액. */
    /**
     * @param amount        결제예정액 = max(0, 일시불 + 할부 회차 합 − 기결제)
     * @param lumpSumAmount 일시불 순사용액(EXPENSE − 환불). 환불이 크면 음수일 수 있다
     * @param alreadyPaid   같은 회차에 이미 낸 금액(선결제 차감분)
     * @param installments  이 회차에 빠지는 할부 회차들 — 명세서가 원금·회차를 그릴 재료
     */
    record BillingCycle(LocalDate periodStart, LocalDate periodEnd, long amount,
                        long lumpSumAmount, long alreadyPaid,
                        List<CardPaymentServiceDto.InstallmentDue> installments) {}

    /**
     * 다가오는 결제 회차 계산. 회차 금액 = 청구 기간(결제일의 전월 1일~말일) 카드 순사용액
     * (지출 − 환불) − 같은 회차에 이미 결제 완료된 금액(선결제 차감), 최소 0.
     *
     * <p><b>현재 카드빚으로 캡하지 않는다.</b> 잔액을 수동 보정(앵커)해 0 으로 맞춘 카드는
     * 빚이 0 으로 계산되는데, 종전엔 그 캡이 여기(표시)에 걸려 있어 사용액이 있어도
     * 결제예정액이 0 이 되고 결제 버튼이 죽었다(문자 수입으로 지출만 기록하는 사용자의
     * 실제 사고). 양수 역전 방지는 표시가 아니라 결제 시점이 맡는다 — {@link #payoff} 가
     * 돈을 남은 빚까지만 움직인다.
     */
    /**
     * 할부 중도 전액 상환.
     *
     * <p>상환일을 "오늘" 이 아니라 <b>다가오는 청구 회차의 시작일</b>로 적는 게 핵심이다.
     * 오늘로 적으면(예: 9/1, 결제일 6일) 남은 원금이 9월 회차 — 10월 6일 청구 — 로 밀려
     * "지금 정리하고 싶다" 는 의도와 한 달 어긋난다. 다가오는 회차(8월분, 9/6 청구)에
     * 몰아야 화면의 예정액이 즉시 커지고 지금 결제로 바로 정리된다.
     */
    @Override
    @Transactional
    public void payoffInstallment(Long cardRowId, Long expenseRowId, Long userRowId) {
        PayoffTarget t = payoffTarget(cardRowId, expenseRowId, userRowId);
        if (t.expense().getInstallmentPayoffDate() != null) {
            throw new InvalidValueException(DeskErrorCode.CARD_INSTALLMENT_ALREADY_PAID_OFF);
        }
        // 다가오는 회차 기준으로도 이미 끝난 할부면 정리할 남은 원금이 없다.
        if (Math.max(1, t.expense().installmentSequenceAt(t.anchor()))
                > t.expense().getInstallmentMonths()) {
            throw new InvalidValueException(DeskErrorCode.CARD_INSTALLMENT_FINISHED);
        }
        t.expense().payoffInstallment(t.anchor());
        log.info("할부 중도 전액 상환: expenseRowId={}, anchor={}", expenseRowId, t.anchor());
    }

    /** 상환 취소 — 정상 분할로 되돌린다. 잘못 누른 상환을 무르는 경로. */
    @Override
    @Transactional
    public void cancelInstallmentPayoff(Long cardRowId, Long expenseRowId, Long userRowId) {
        PayoffTarget t = payoffTarget(cardRowId, expenseRowId, userRowId);
        if (t.expense().getInstallmentPayoffDate() == null) {
            throw new InvalidValueException(DeskErrorCode.CARD_INSTALLMENT_NOT_PAID_OFF);
        }
        // 몰아 받은 회차가 이미 결제됐으면 되돌릴 수 없다 — 남은 회차가 다시 청구돼 두 번 낸다(D6 과 같은 이유).
        LocalDate payoffCycle = CardCycleMath.cycleStartOf(t.expense().getInstallmentPayoffDate());
        if (scheduleOf(t.expense().getAsset()).isClosed(payoffCycle, cycleToday())) {
            throw new InvalidValueException(DeskErrorCode.CARD_INSTALLMENT_PAYOFF_CLOSED);
        }
        t.expense().cancelInstallmentPayoff();
        log.info("할부 상환 취소: expenseRowId={}", expenseRowId);
    }

    private record PayoffTarget(Expense expense, LocalDate anchor) {}

    /** 상환 대상 검증 — 카드 소유·신용카드·그 카드의 살아 있는 할부 거래. */
    private PayoffTarget payoffTarget(Long cardRowId, Long expenseRowId, Long userRowId) {
        Asset card = findAssetOrThrow(cardRowId);
        validateOwnership(card, userRowId);
        validateCreditCard(card);

        Expense expense = entityManager.find(Expense.class, expenseRowId);
        if (expense == null || expense.getIsDeleted() == YNType.Y
                || expense.getAsset() == null
                || !Objects.equals(expense.getAsset().getRowId(), cardRowId)) {
            // 없는 것과 남의 것을 구분해 주지 않는다 — 존재 자체가 정보다.
            throw new EntityNotFoundException(DeskErrorCode.CARD_INSTALLMENT_NOT_FOUND);
        }
        if (!expense.isInstallment()) {
            throw new InvalidValueException(DeskErrorCode.CARD_INSTALLMENT_NOT_INSTALLMENT);
        }

        LocalDate today = cycleToday();
        LocalDate next = scheduleOf(card).firstOpenPaymentDate(today);
        // upcomingCycle 과 같은 규칙 — 결제일이 없으면 당월 1일~말일 회차.
        LocalDate anchor = next == null ? today.withDayOfMonth(1) : periodStartFor(next);
        return new PayoffTarget(expense, anchor);
    }

    /** 회차 판정의 "오늘" — 자정 배치와 같은 서울 시계(D11). 사용자 시간대로 보면 결제일 경계에서 배치와 어긋난다. */
    private LocalDate cycleToday() {
        return serviceClock.today();
    }

    private PaymentSchedule scheduleOf(Asset card) {
        PaymentSchedule s = paymentScheduleService.scheduleOf(card);
        return s != null ? s : PaymentSchedule.of(null);
    }

    /**
     * 거래 변경을 회차 규칙으로 정산한다 — 표식 · 상계 · 열린 회차 선결제 환급.
     *
     * <p>무엇을 할지는 {@link CardCycleMath#plan} 이 정하고, 여기서는 돈의 기록을 봐야 하는 것만 한다.
     * 결제가 덮고 있던 몫이 빠지면 닫힌 회차는 그만큼을 상계로 붙잡고(통장 그대로, D1), 열린 회차는
     * 미리 낸 돈이 남을 때만 결제계좌로 돌려준다(D3). 덮이지 않은 몫(결제가 모자랐던 회차)은 원래
     * 빚이라 빚이 줄 뿐이다 — 붙잡으면 빚이 영원히 남는다.
     */
    @Override
    @Transactional
    public CardPaymentServiceDto.SettlementResult settleExpenseChange(
            CardPaymentServiceDto.SettlementCommand cmd) {
        Long userRowId = cmd.userRowId();
        LocalDate today = cycleToday();
        LocalDateTime now = userClock.now(userRowId);
        Expense expense = cmd.expense();
        Asset cardBefore = cmd.before().isCard() ? findAssetOrThrow(cmd.before().cardRowId()) : null;
        Asset cardAfter = cmd.after().isCard() ? findAssetOrThrow(cmd.after().cardRowId()) : null;
        CardPaymentServiceDto.SettlementMode mode = cmd.mode();

        CardCycleMath.Plan plan = mode == CardPaymentServiceDto.SettlementMode.CANCEL_REFUND
            ? coveragePlan(cardAfter, cmd.after(), expense.getRowId(), today, now)
            : CardCycleMath.plan(cmd.before(), cmd.after(), today);

        // 표식을 먼저 박는다 — 아래 크레딧 계산이 이 거래의 기록용 몫을 청구에서 빼고 센다.
        // 환불은 표식을 그대로 둔다 — 환불된 거래는 어느 청구에도 안 들어가고, 취소하면 그대로 돌아온다.
        if (mode == CardPaymentServiceDto.SettlementMode.CHANGE) {
            expense.markCardSettledThrough(plan.newMark());
        } else if (mode == CardPaymentServiceDto.SettlementMode.CANCEL_REFUND && plan.newMark() != null
                && (expense.getCardSettledThrough() == null
                    || plan.newMark().isAfter(expense.getCardSettledThrough()))) {
            expense.markCardSettledThrough(plan.newMark());
        }

        Refunds before = computeRefunds(cardBefore, removalsOf(plan, cardBefore), now, null, null);
        Refunds after = cardAfter != null && (cardBefore == null || !cardAfter.getRowId().equals(cardBefore.getRowId()))
            ? computeRefunds(cardAfter, removalsOf(plan, cardAfter), now, null, null)
            : Refunds.NONE;

        Long transferRowId = null;
        long refunded = 0L;
        String memo = cmd.refundMemo() != null ? cmd.refundMemo() : "미리 낸 돈 환급";
        if (before.total() > 0L) {
            transferRowId = issueRefund(cardBefore, before, memo, today, now, userRowId);
            refunded += before.total();
        }
        if (after.total() > 0L) {
            Long id = issueRefund(cardAfter, after, memo, today, now, userRowId);
            transferRowId = transferRowId != null ? transferRowId : id;
            refunded += after.total();
        }

        LocalDateTime settleAt = settleTime(expense, now);
        long deltaBefore = plan.settleDeltaBefore() - before.heldBack();
        if (cardBefore != null) {
            if (mode == CardPaymentServiceDto.SettlementMode.REFUND) {
                if (deltaBefore != 0L) {
                    balanceHistoryService.setCardRefundHold(cardBefore, expense.getRowId(), deltaBefore, settleAt);
                }
            } else {
                long current = balanceHistoryService.cardSettledAmount(expense.getRowId(), cardBefore.getRowId());
                // 변화가 없어도 거래 날짜가 옮겨졌으면 상계 행 시각을 따라 옮긴다 — 옛 앵커 카드에서
                // 잔액 시점이 어긋나지 않게(24차 10⑨).
                if (deltaBefore != 0L || current != 0L) {
                    balanceHistoryService.setCardSettled(cardBefore, expense.getRowId(), current + deltaBefore, settleAt);
                }
            }
        }
        if (cardAfter != null && (cardBefore == null || !cardAfter.getRowId().equals(cardBefore.getRowId()))) {
            long deltaAfter = plan.settleDeltaAfter() - after.heldBack();
            if (deltaAfter != 0L) {
                long current = balanceHistoryService.cardSettledAmount(expense.getRowId(), cardAfter.getRowId());
                balanceHistoryService.setCardSettled(cardAfter, expense.getRowId(), current + deltaAfter, settleAt);
            }
        }

        log.info("카드 회차 정산: expenseId={}, 표식={}, 선결제환급={}, 새기록용={}",
            expense.getRowId(), plan.newMark(), refunded, plan.newRecordAmount());
        return new CardPaymentServiceDto.SettlementResult(transferRowId, refunded, plan.newMark(),
            plan.newRecordAmount());
    }

    /**
     * 같은 판정을 <b>DB 를 바꾸지 않고</b> — 옛 앱의 확인창 재료(새 웹·앱은 부르지 않는다, D4).
     *
     * <p>실제 실행과 같은 함수({@link CardCycleMath#plan}, {@link #computeRefunds})를 부르되, 청구액은
     * "이 거래를 뺀 청구 + 바뀐 뒤 이 거래가 청구에 남기는 몫" 으로 센다.
     */
    @Override
    @Transactional(readOnly = true)
    public CardPaymentServiceDto.SettlementPreview previewExpenseChange(
            CardPaymentServiceDto.SettlementCommand cmd) {
        LocalDate today = cycleToday();
        LocalDateTime now = userClock.now(cmd.userRowId());
        Asset cardBefore = cmd.before().isCard() ? findAssetOrThrow(cmd.before().cardRowId()) : null;
        Long exclude = cmd.expense() != null ? cmd.expense().getRowId() : null;

        CardCycleMath.Plan plan = CardCycleMath.plan(cmd.before(), cmd.after(), today);
        Refunds refunds = computeRefunds(cardBefore, removalsOf(plan, cardBefore), now, exclude,
            plan.afterBillable());
        boolean removing = !removalsOf(plan, cardBefore).isEmpty();
        return new CardPaymentServiceDto.SettlementPreview(
            refunds.total(),
            cardBefore != null && usablePaymentAsset(cardBefore) == null && removing,
            plan.newRecordAmount());
    }

    private static List<CardCycleMath.PaidRemoval> removalsOf(CardCycleMath.Plan plan, Asset card) {
        if (card == null) {
            return List.of();
        }
        return plan.paidRemovals().stream().filter(r -> card.getRowId().equals(r.cardRowId())).toList();
    }

    /** 이번 정산에서 한 카드가 돌려줄 것 — 회차별 선결제 환급액과 상계로 붙잡을 몫. */
    private record Refunds(Map<LocalDate, Long> byCycle, long heldBack) {
        static final Refunds NONE = new Refunds(Map.of(), 0L);

        long total() {
            return byCycle.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * 빠진 몫마다 결제가 덮고 있던 금액(크레딧 = 낸 돈 − 그 회차에서 돌려준 돈 − 지금 청구액)을 잰다.
     *
     * <p>닫힌 회차는 덮인 만큼 상계로 붙잡는다(통장 그대로, D1). 열린 회차는 결제계좌가 있으면 덮인 만큼
     * 돌려주고(D3), 없으면 붙잡는다. 회차에 안 묶인 옛 환급(과납 스윕·이 규칙 전 환급)이 있으면 카드 전체
     * 크레딧에서 그만큼을 이미 돌려준 것으로 본다 — 두 번 돌려주지 않는다.
     *
     * @param excludeRowId 미리보기 — 이 거래를 청구에서 빼고 {@code afterBillable} 을 더한다
     */
    private Refunds computeRefunds(Asset card, List<CardCycleMath.PaidRemoval> removals, LocalDateTime at,
                                   Long excludeRowId, Map<LocalDate, Long> afterBillable) {
        if (card == null || removals.isEmpty()) {
            return Refunds.NONE;
        }
        Map<LocalDate, Long> byCycle = new java.util.TreeMap<>();
        List<CardBilling> rows = activeRows(card.getRowId());
        // 결제계좌는 돌려줄 때만 본다 — 지운 계좌·없는 계좌면 붙잡는다(23차 11).
        Asset account = usablePaymentAsset(card);
        long heldBack = 0L;
        long paidTotal = 0L;
        for (CardCycleMath.PaidRemoval r : removals) {
            LocalDate start = r.cycleStart();
            // 닫힌 회차는 "결제로 정리된 청구" 를 본다 — 빚 캡으로 이체가 청구보다 작았어도(잔액 보정·
            // 카드 수입이 나머지를 덮음) 그 회차는 전부 정리됐다. 이체액만 붙잡으면 나머지가 카드 잔액을
            // 양수로 띄워 자정 스윕이 통장으로 보낸다(D1 위반, 인계 25 G2).
            long credit = r.closed()
                ? settledCredit(card.getRowId(), rows, start, at, excludeRowId, afterBillable)
                : cycleCredit(card.getRowId(), rows, start, at, excludeRowId, afterBillable);
            long cover = Math.min(credit, r.amount());
            if (cover <= 0L) {
                continue;
            }
            if (!r.closed() && account != null) {
                byCycle.merge(start, cover, Long::sum);
                paidTotal += cover;
            } else {
                heldBack += cover;
            }
        }
        long unattributed = paidTotal > 0L ? unattributedRefunds(card.getRowId(), rows) : 0L;
        if (unattributed > 0L) {
            long allowed = Math.max(0L,
                totalCredit(card.getRowId(), rows, at, excludeRowId, afterBillable) - unattributed);
            long cut = Math.max(0L, paidTotal - allowed);
            heldBack += cut;
            // 최근 회차부터 깎는다 — 옛 환급은 대개 최근 회차의 크레딧을 먼저 썼다.
            List<LocalDate> keys = new ArrayList<>(byCycle.keySet());
            for (int i = keys.size() - 1; i >= 0 && cut > 0L; i--) {
                LocalDate k = keys.get(i);
                long v = byCycle.get(k);
                long d = Math.min(v, cut);
                cut -= d;
                if (v - d > 0L) {
                    byCycle.put(k, v - d);
                } else {
                    byCycle.remove(k);
                }
            }
        }
        return new Refunds(byCycle, heldBack);
    }

    /** 돌려줄 결제계좌 — 없거나 지운 계좌면 null. */
    private static Asset usablePaymentAsset(Asset card) {
        Asset a = card.getPaymentAsset();
        return a != null && a.getIsDeleted() != YNType.Y ? a : null;
    }

    /** 선결제 환급 이체 한 건 + 회차별 REFUNDED 기록(이체에 묶는다 — 환불 취소가 함께 무른다). */
    private Long issueRefund(Asset card, Refunds refunds, String memo, LocalDate today,
                             LocalDateTime at, Long userRowId) {
        Asset account = usablePaymentAsset(card);
        AssetServiceDto.TransferInfo info = assetService.createTransfer(
            new AssetServiceDto.CreateTransferCommand(
                userRowId, card.getRowId(), account.getRowId(), refunds.total(), 0L, 0L, memo, at,
                // 과납 스윕이 만드는 환급과 같은 출처 — 사용자가 따로 고치면 잔액이 어긋난다.
                "CARD_REFUND"));
        AssetTransfer ref = transferRef(info.rowId());
        refunds.byCycle().forEach((start, amount) -> cardBillingRepository.save(CardBilling.refunded(
            card, account, amount, start, CardCycleMath.cycleEndOf(start), today, ref)));
        log.info("선결제 환급 이체 생성: cardRowId={}, amount={}, 회차={}, transferRowId={}",
            card.getRowId(), refunds.total(), refunds.byCycle().keySet(), info.rowId());
        return info.rowId();
    }

    /**
     * 환불 취소 — 환불 전 모습은 호출자가 이미 되살렸다(환급 이체·붙잡은 몫을 지우고 흐름을 다시
     * 적재). 여기서는 <b>환불 동안 결제일이 된 회차</b>만 챙긴다.
     *
     * <p>그 회차 결제에는 이 거래가 빠져 있었다. 표식이 덮지 않는 닫힌 회차분 가운데 그 회차 결제가
     * 덮지 못하는 만큼은 기록용(상계 +)이 된다. 환불 전에 이미 결제된 회차는 크레딧이 그대로라 덮인다.
     */
    private CardCycleMath.Plan coveragePlan(Asset card, CardCycleMath.Side after, Long expenseRowId,
                                            LocalDate today, LocalDateTime at) {
        if (card == null || !after.isCard()) {
            return CardCycleMath.Plan.none();
        }
        long settle = 0L;
        long newRecord = 0L;
        LocalDate lastRecord = null;
        List<CardBilling> rows = activeRows(card.getRowId());
        for (Map.Entry<LocalDate, Long> e : after.dues().entrySet()) {
            LocalDate start = e.getKey();
            if (CardCycleMath.isRecordOnly(start, after.mark()) || !after.isClosed(start, today)) {
                continue;
            }
            long credit = settledCredit(card.getRowId(), rows, start, at, expenseRowId, null);
            long record = e.getValue() - Math.min(e.getValue(), credit);
            if (record > 0L) {
                settle += record;
                newRecord += record;
                lastRecord = start;
            }
        }
        LocalDate mark = lastRecord == null ? null : CardCycleMath.cycleEndOf(lastRecord);
        return new CardCycleMath.Plan(mark, 0L, settle, List.of(), newRecord, Map.of());
    }

    /** 상계 이력의 효력 시각 — 거래 날짜(미래면 지금). */
    private static LocalDateTime settleTime(Expense e, LocalDateTime now) {
        LocalDateTime d = e.getExpenseDate();
        return d != null && d.isBefore(now) ? d : now;
    }

    /**
     * 회차 하나의 크레딧 — max(0, 실제 낸 돈 − 그 회차에서 돌려준 돈 − 지금 청구액).
     *
     * <p>청구액은 앱이 결제할 몫만 센다(기록용은 뺀다) — 그래야 소급 입력한 거래가 같은 회차의
     * 다른 거래 몫을 깎지 않는다.
     *
     * @param rows 이 카드의 살아 있는 청구 행 — 한 번 읽어 여러 회차에 돌려 쓴다
     */
    private long cycleCredit(Long cardRowId, List<CardBilling> rows, LocalDate start, LocalDateTime at,
                             Long excludeRowId, Map<LocalDate, Long> afterBillable) {
        LocalDate end = CardCycleMath.cycleEndOf(start);
        long bill = grossBill(cardRowId, start, end, at, excludeRowId)
            + (afterBillable != null ? afterBillable.getOrDefault(start, 0L) : 0L);
        return Math.max(0L, paidMoney(rows, start) - statusSum(rows, start, BillingStatus.REFUNDED) - bill);
    }

    /**
     * 닫힌 회차의 크레딧 — max(0, 결제로 정리된 청구 − 돌려준 몫 − 지금 청구액).
     *
     * <p>{@link #cycleCredit} 은 실제로 나간 돈(이체액)을 보지만, 닫힌 회차는 돈을 돌려주지 않고 카드
     * 잔액을 그대로 두는 게 목적이라 <b>정리된 청구액</b>(결제 완료 행의 청구액)을 본다. 빚 캡으로 이체가
     * 적었던 회차·결제계좌 없이 상계로 정리한 회차도 청구 전체가 정리됐다.
     */
    private long settledCredit(Long cardRowId, List<CardBilling> rows, LocalDate start, LocalDateTime at,
                               Long excludeRowId, Map<LocalDate, Long> afterBillable) {
        LocalDate end = CardCycleMath.cycleEndOf(start);
        long bill = grossBill(cardRowId, start, end, at, excludeRowId)
            + (afterBillable != null ? afterBillable.getOrDefault(start, 0L) : 0L);
        long settled = statusSum(rows, start, BillingStatus.COMPLETED)
            - statusSum(rows, start, BillingStatus.REFUNDED)
            - statusSum(rows, start, BillingStatus.RECORD_REFUNDED);
        return Math.max(0L, settled - bill);
    }

    /** 카드 전체 크레딧 — 결제한 회차마다 {@link #cycleCredit} 의 합. */
    private long totalCredit(Long cardRowId, List<CardBilling> rows, LocalDateTime at, Long excludeRowId,
                             Map<LocalDate, Long> afterBillable) {
        java.util.Set<LocalDate> starts = new java.util.TreeSet<>();
        for (CardBilling b : rows) {
            if (b.getStatus() == BillingStatus.COMPLETED && b.getPeriodStart() != null) {
                starts.add(b.getPeriodStart());
            }
        }
        long total = 0L;
        for (LocalDate start : starts) {
            total += cycleCredit(cardRowId, rows, start, at, excludeRowId, afterBillable);
        }
        return total;
    }

    private List<CardBilling> activeRows(Long cardRowId) {
        return cardBillingRepository.findByCardAssetRowId(cardRowId).stream()
            .filter(b -> b.getIsDeleted() == YNType.N)
            .toList();
    }

    /** 그 회차에 실제로 나간 돈 — 결제 완료 행의 이체액 합(이체 없는 기록은 0). */
    private static long paidMoney(List<CardBilling> rows, LocalDate start) {
        long paid = 0L;
        for (CardBilling b : rows) {
            if (b.getStatus() == BillingStatus.COMPLETED && start.equals(b.getPeriodStart())
                    && b.getTransfer() != null && b.getTransfer().getAmount() != null) {
                // 카드(원화)가 받은 금액 — 결제 계좌가 외화면 이체 amount 는 그 계좌 통화라 to_amount 를 본다.
                paid += b.getTransfer().getToAmount() != null
                    ? b.getTransfer().getToAmount() : b.getTransfer().getAmount();
            }
        }
        return paid;
    }

    /** 그 회차에서 이 상태인 행의 청구액 합. */
    private static long statusSum(List<CardBilling> rows, LocalDate start, BillingStatus status) {
        long sum = 0L;
        for (CardBilling b : rows) {
            if (b.getStatus() == status && start.equals(b.getPeriodStart()) && b.getBillingAmount() != null) {
                sum += b.getBillingAmount();
            }
        }
        return sum;
    }

    /** 그 회차의 순 납부 기록 = 결제 완료 − 환급(23차 결함 2). 다가오는 회차 "이미 낸 돈" 의 재료. */
    private long netBilledPaid(Long cardRowId, LocalDate start, LocalDate end) {
        // 결제를 취소하면 그 회차 환급 기록이 남아 음수가 될 수 있다 — 음수면 청구가 부풀므로 0 에서 멈춘다.
        return Math.max(0L, cardBillingRepository.sumCompletedAmountByCardAndPeriod(cardRowId, start, end)
            - cardBillingRepository.sumRefundedAmountByCardAndPeriod(cardRowId, start, end));
    }

    /**
     * 회차에 묶이지 않은 환급 — 이 카드의 CARD_REFUND 이체 합 − 회차에 묶인 환급 기록 합.
     *
     * <p>과납 스윕과 이 규칙 전에 나간 환급(운영 수동 정산 포함)이 여기 든다. 어느 회차 몫인지
     * 모르니 카드 전체 크레딧에서 뺀다 — 두 번 돌려주지 않는 쪽으로 기운다.
     */
    private long unattributedRefunds(Long cardRowId, List<CardBilling> rows) {
        Long transfers = entityManager.createQuery(
            "SELECT COALESCE(SUM(t.amount), 0) FROM AssetTransfer t " +
            "WHERE t.fromAsset.rowId = :cardRowId AND t.autoSource = :autoSource " +
            "AND t.isDeleted = :isDeleted", Long.class)
            .setParameter("cardRowId", cardRowId)
            .setParameter("autoSource", "CARD_REFUND")
            .setParameter("isDeleted", YNType.N)
            .getSingleResult();
        long attributed = 0L;
        for (CardBilling b : rows) {
            if (b.getBillingAmount() != null && (b.getStatus() == BillingStatus.REFUNDED
                    || b.getStatus() == BillingStatus.RECORD_REFUNDED)) {
                attributed += b.getBillingAmount();
            }
        }
        return Math.max(0L, (transfers == null ? 0L : transfers) - attributed);
    }

    /** 그 회차의 청구액 — 일시불 + 할부 회차분(기록용 제외). 이미 낸 금액은 빼지 않는다. */
    private long grossBill(Long cardRowId, LocalDate start, LocalDate end, LocalDateTime at,
                           Long excludeRowId) {
        long lump = lumpSumNet(cardRowId, start, end, at, excludeRowId);
        long inst = installmentDuesIn(cardRowId, start, end, at, excludeRowId).stream()
            .mapToLong(CardPaymentServiceDto.InstallmentDue::amount).sum();
        return lump + inst;
    }

    /**
     * 닫힌 회차 목록 — 결제 기록이 있거나 거래가 있는 회차(R4·D10).
     *
     * <p>머리 금액은 <b>지금 기록 합</b>이다(D10) — 결제한 뒤 적은 거래·지운 거래까지 반영된 그 회차의
     * 명세서. 실제로 계좌에서 나간 돈은 따로 내려 화면이 "계좌에서 나간 돈은 …" 을 말하게 한다. 할부는
     * 거래 날짜가 앞 달이라 이용 내역 목록에 안 나오므로 회차분 구성을 같이 내린다(24차 8).
     */
    private List<CardPaymentServiceDto.ClosedCycle> closedCycles(Asset card, PaymentSchedule schedule,
                                                                LocalDate today) {
        if (!schedule.hasDay()) {
            return List.of();
        }
        LocalDateTime now = userClock.now(card.getUser().getRowId());
        List<Expense> txs = entityManager.createQuery(
            "SELECT e FROM Expense e WHERE e.asset.rowId = :cardRowId " +
            "AND e.refundedAt IS NULL AND e.expenseDate <= :now AND e.isDeleted = :isDeleted", Expense.class)
            .setParameter("cardRowId", card.getRowId())
            .setParameter("now", now)
            .setParameter("isDeleted", YNType.N)
            .getResultList();
        Map<LocalDate, Long> recorded = new java.util.TreeMap<>();
        Map<LocalDate, List<CardPaymentServiceDto.InstallmentDue>> dues = new java.util.TreeMap<>();
        for (Expense e : txs) {
            LocalDate first = CardCycleMath.cycleStartOf(e.getExpenseDate().toLocalDate());
            Integer payoffSeq = e.isInstallment() ? e.installmentPayoffSequence() : null;
            for (Map.Entry<LocalDate, Long> part : CardCycleMath.duesByCycle(e).entrySet()) {
                LocalDate c = part.getKey();
                if (!schedule.isClosed(c, today)) {
                    continue;
                }
                recorded.merge(c, part.getValue(), Long::sum);
                if (e.isInstallment()) {
                    int seq = (int) java.time.temporal.ChronoUnit.MONTHS.between(first, c) + 1;
                    dues.computeIfAbsent(c, k -> new ArrayList<>()).add(new CardPaymentServiceDto.InstallmentDue(
                        e.getRowId(), e.getMerchant(), e.getDescription(), e.getAmount(),
                        e.getInstallmentMonths(), seq, part.getValue(),
                        payoffSeq != null && payoffSeq == seq,
                        CardCycleMath.isRecordOnly(c, e.getCardSettledThrough())));
                }
            }
        }
        java.util.TreeSet<LocalDate> starts = new java.util.TreeSet<>(recorded.keySet());
        List<CardBilling> rows = activeRows(card.getRowId());
        for (CardBilling b : rows) {
            if (b.getStatus() == BillingStatus.COMPLETED && b.getPeriodStart() != null
                    && schedule.isClosed(b.getPeriodStart(), today)) {
                starts.add(b.getPeriodStart());
            }
        }
        LocalDate registered = card.getCreateAt() != null ? card.getCreateAt().toLocalDate() : null;
        List<CardPaymentServiceDto.ClosedCycle> result = new ArrayList<>();
        for (LocalDate start : starts.descendingSet()) {
            LocalDate paymentDate = schedule.paymentDateOf(start);
            long paid = Math.max(0L, statusSum(rows, start, BillingStatus.COMPLETED)
                - statusSum(rows, start, BillingStatus.REFUNDED));
            long rec = recorded.getOrDefault(start, 0L);
            result.add(new CardPaymentServiceDto.ClosedCycle(
                start, CardCycleMath.cycleEndOf(start), paymentDate,
                paid, rec, Math.max(0L, rec - paid),
                registered != null && paymentDate.isBefore(registered),
                null,
                dues.getOrDefault(start, List.of())));
        }
        return result;
    }

    private BillingCycle upcomingCycle(Asset card, LocalDate nextPaymentDate) {
        // 아직 오지 않은 거래는 세지 않는다 — 가계부 합계·자산 잔액과 같은 규칙(D1,
        // 2026-09-18 결정). 종전엔 청구만 기간 전체를 세서, 같은 카드인데 한도 사용과
        // 청구 예정이 다르게 보였다.
        LocalDateTime now = userClock.now(card.getUser().getRowId());
        LocalDate periodStart;
        LocalDate periodEnd;
        if (nextPaymentDate == null) {
            // 결제일 미설정 — 회차를 당월 1일~말일로 본다(체크카드 월 사용액과 같은 기준).
            // 종전엔 잔액 전액을 청구했는데, 절대값이라 잔액이 양수여도 그만큼 또 청구해
            // 결제할수록 더 양수가 되는 결함이 있었다.
            LocalDate today = cycleToday();
            periodStart = today.withDayOfMonth(1);
            periodEnd = today.withDayOfMonth(today.lengthOfMonth());
        } else {
            periodStart = periodStartFor(nextPaymentDate);
            periodEnd = periodEndFor(nextPaymentDate);
        }
        long lumpSum = lumpSumNet(card.getRowId(), periodStart, periodEnd, now);
        List<CardPaymentServiceDto.InstallmentDue> installments =
            installmentDuesIn(card.getRowId(), periodStart, periodEnd, now);
        long installmentSum = installments.stream()
            .mapToLong(CardPaymentServiceDto.InstallmentDue::amount).sum();
        long alreadyPaid = netBilledPaid(card.getRowId(), periodStart, periodEnd);
        return new BillingCycle(periodStart, periodEnd,
            Math.max(0L, lumpSum + installmentSum - alreadyPaid),
            lumpSum, alreadyPaid, installments);
    }

    /**
     * 일시불 순사용액 — EXPENSE 합 − INCOME(환불/취소) 합. 할부 거래는 제외한다.
     *
     * <p><b>아직 오지 않은 거래는 세지 않는다.</b> 상한을 회차 끝이 아니라 {@code min(회차 끝,
     * 지금)} 으로 조인다. 잔액(한도 사용)은 {@code effective_at <= 지금} 인 이력만 세므로,
     * 여기만 기간 전체를 세면 같은 카드인데 두 숫자가 다르게 보인다(D1, 2026-09-18 결정).
     * 반복 거래가 미리 만들어 둔 거래와 "오늘이지만 시각이 뒤인" 거래가 그렇게 샜다.
     */
    private long lumpSumNet(Long cardRowId, LocalDate start, LocalDate end, LocalDateTime now) {
        return lumpSumNet(cardRowId, start, end, now, null);
    }

    /** {@code excludeRowId} 는 미리보기가 "이 거래는 빼고 세라" 를 말하는 자리다(13-1). */
    private long lumpSumNet(Long cardRowId, LocalDate start, LocalDate end, LocalDateTime now,
                            Long excludeRowId) {
        // expenseDate 는 LocalDateTime 이므로 LocalDate 범위를 경계 일시로 변환
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime periodEnd = end.atTime(LocalTime.MAX);
        LocalDateTime to = now.isBefore(periodEnd) ? now : periodEnd;
        if (!from.isBefore(to)) {
            return 0L;
        }
        Long sum = entityManager.createQuery(
            "SELECT COALESCE(SUM(CASE WHEN e.expenseType = :expenseType THEN e.amount ELSE -e.amount END), 0) " +
            "FROM Expense e " +
            "WHERE e.asset.rowId = :cardRowId " +
            "AND e.expenseDate >= :start AND e.expenseDate <= :end " +
            "AND (e.installmentMonths IS NULL OR e.installmentMonths <= 1) " +
            // 환불된 거래는 삭제와 똑같이 뺀다 — 원거래가 빠져 청구가 저절로 줄어든다.
            "AND e.refundedAt IS NULL " +
            // 기록용(앱 밖에서 이미 결제됨)은 청구에 안 얹는다 — 닫힌 회차 규칙 R2·R8.
            "AND (e.cardSettledThrough IS NULL OR e.cardSettledThrough < :periodEndDate) " +
            "AND (:excludeRowId IS NULL OR e.rowId <> :excludeRowId) " +
            "AND e.isDeleted = :isDeleted", Long.class)
            .setParameter("expenseType", ExpenseType.EXPENSE)
            .setParameter("cardRowId", cardRowId)
            .setParameter("start", from)
            .setParameter("end", to)
            .setParameter("periodEndDate", end)
            .setParameter("excludeRowId", excludeRowId)
            .setParameter("isDeleted", YNType.N)
            .getSingleResult();
        return sum == null ? 0L : sum;
    }

    /**
     * 이 청구 기간에 빠질 할부 회차 합.
     *
     * <p>거래가 속한 청구 기간을 1회차로 보고, 이번 기간이 몇 회차인지를 <b>기간 시작월의 차이</b>로
     * 센다(결제일 말일 보정과 무관하게 월 단위로 세어야 회차가 밀리지 않는다).
     * 회차가 1..N 범위 밖이면 이 기간엔 청구되지 않는다.
     */
    /**
     * 이 청구 기간에 빠질 할부 회차 목록 — 합계가 아니라 <b>구성</b>을 돌려준다.
     *
     * <p>명세서에 "원금 얼마짜리 할부의 몇 번째 회차가 얼마 빠진다" 를 그리려면 합계로는
     * 부족하다. 합만 내리던 시절에는 할부 거래가 있는 달의 예정액이 이용 내역 합과 달라
     * "이 숫자가 어디서 왔는지" 를 화면이 설명할 수 없었다.
     */
    private List<CardPaymentServiceDto.InstallmentDue> installmentDuesIn(
            Long cardRowId, LocalDate start, LocalDate end, LocalDateTime now) {
        return installmentDuesIn(cardRowId, start, end, now, null);
    }

    /** {@code excludeRowId} 는 미리보기 몫이다 — 그 할부를 빼고 센 뒤 가정값으로 다시 얹는다. */
    private List<CardPaymentServiceDto.InstallmentDue> installmentDuesIn(
            Long cardRowId, LocalDate start, LocalDate end, LocalDateTime now,
            Long excludeRowId) {
        // 아직 회차가 남아 있을 수 있는 할부 거래만 — 이 기간보다 미래 거래는 볼 필요가 없다.
        // 상한은 **지금** 까지다(D1) — 아직 사지도 않은 할부를 청구에 세면 안 된다.
        LocalDateTime periodEnd = end.atTime(LocalTime.MAX);
        LocalDateTime upTo = now.isBefore(periodEnd) ? now : periodEnd;
        List<Expense> installments = entityManager.createQuery(
            "SELECT e FROM Expense e " +
            "WHERE e.asset.rowId = :cardRowId " +
            "AND e.expenseType = :expenseType " +
            "AND e.installmentMonths > 1 " +
            "AND e.expenseDate <= :end " +
            // 환불된 할부는 남은 회차가 통째로 사라진다(설계 결정 8).
            "AND e.refundedAt IS NULL " +
            "AND (:excludeRowId IS NULL OR e.rowId <> :excludeRowId) " +
            "AND e.isDeleted = :isDeleted " +
            "ORDER BY e.expenseDate", Expense.class)
            .setParameter("cardRowId", cardRowId)
            .setParameter("expenseType", ExpenseType.EXPENSE)
            .setParameter("end", upTo)
            .setParameter("excludeRowId", excludeRowId)
            .setParameter("isDeleted", YNType.N)
            .getResultList();

        List<CardPaymentServiceDto.InstallmentDue> dues = new ArrayList<>();
        for (Expense e : installments) {
            if (CardCycleMath.isRecordOnly(start, e.getCardSettledThrough())) {
                // 이 회차분은 앱 밖에서 이미 결제된 기록용이다 — 청구에 안 얹는다(R5).
                continue;
            }
            int seq = e.installmentSequenceAt(start);
            long due = e.installmentAmountAt(seq);
            if (due == 0L) {
                // 회차 범위(1..N) 밖 — 이 기간엔 청구되지 않는 할부다.
                continue;
            }
            Integer payoffSeq = e.installmentPayoffSequence();
            dues.add(new CardPaymentServiceDto.InstallmentDue(
                e.getRowId(), e.getMerchant(), e.getDescription(),
                e.getAmount(), e.getInstallmentMonths(), seq, due,
                payoffSeq != null && payoffSeq == seq, false));
        }
        return dues;
    }

    // === 결제일 / 청구기간 계산 (RecurringTransaction MONTHLY 말일 보정 로직 재활용) ===

    /**
     * payment_day 기준 다음 결제예정일. RecurringTransactionServiceImpl.adjustToFrequency(MONTHLY) 와 동일하게
     * 해당 월 일수보다 큰 결제일은 말일로 보정한다. from(=오늘) 의 보정 결제일이 이미 지났으면 다음 달로 이월.
     */
    static LocalDate nextPaymentDate(Integer paymentDay, LocalDate from) {
        if (paymentDay == null) {
            return null;
        }
        int maxDay = from.lengthOfMonth();
        LocalDate adjusted = from.withDayOfMonth(Math.min(paymentDay, maxDay));
        if (adjusted.isBefore(from)) {
            adjusted = adjusted.plusMonths(1);
            maxDay = adjusted.lengthOfMonth();
            adjusted = adjusted.withDayOfMonth(Math.min(paymentDay, maxDay));
        }
        return adjusted;
    }

    /** today 가 이 카드의 결제일인지 — 말일 보정 포함(예: payment_day=31, 2월이면 28/29 이 결제일). */
    static boolean isPaymentDay(Integer paymentDay, LocalDate today) {
        if (paymentDay == null) {
            return false;
        }
        int maxDay = today.lengthOfMonth();
        int effectiveDay = Math.min(paymentDay, maxDay);
        return today.getDayOfMonth() == effectiveDay;
    }

    /** 청구 기간 시작 — 전월 1일. */
    static LocalDate periodStartFor(LocalDate paymentDate) {
        return paymentDate.withDayOfMonth(1).minusMonths(1);
    }

    /** 청구 기간 종료 — 전월 말일. */
    static LocalDate periodEndFor(LocalDate paymentDate) {
        return paymentDate.withDayOfMonth(1).minusDays(1);
    }

    // === 검증 ===

    private void validateCreditCard(Asset asset) {
        if (asset.getAssetType() != AssetType.CREDIT_CARD) {
            log.warn("카드 청구 대상 아님(신용카드 아님): assetId={}, type={}", asset.getRowId(), asset.getAssetType());
            throw new InvalidValueException(DeskErrorCode.CARD_BILLING_NOT_CREDIT_CARD);
        }
    }

    private void validateOwnership(Asset asset, Long userRowId) {
        if (!asset.getUser().getRowId().equals(userRowId)) {
            log.warn("카드 자산 소유권 검증 실패 - assetId={}, ownerRowId={}, requestUserRowId={}",
                asset.getRowId(), asset.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.ASSET_ACCESS_DENIED);
        }
    }

    private Asset findAssetOrThrow(Long assetId) {
        return assetRepository.findById(assetId)
            .orElseThrow(() -> {
                log.warn("카드 자산 조회 실패 - 존재하지 않는 자산: assetId={}", assetId);
                return new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND);
            });
    }
}
