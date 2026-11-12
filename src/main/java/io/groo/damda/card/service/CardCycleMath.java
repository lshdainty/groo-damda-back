package io.groo.damda.card.service;

import io.groo.damda.common.validation.InstallmentLimits;

import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.type.ExpenseType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 카드 회차 규칙의 <b>순수 계산</b> — "결제가 끝난 회차는 기록만"(2026-09-21 확정 D1~D16).
 *
 * <p>회차 k 는 한 달(1일~말일)이고 결제일은 {@link PaymentSchedule} 이 정한다. 결제일이 된 회차는
 * 닫혔다(당일 포함, D2). 닫힌 회차에 걸린 변경은 통장·카드 빚·다음 청구를 움직이지 않는다 —
 * 카드 쪽에서 상계(CARD_SETTLED ±)만 움직인다(D1). 돈이 움직이는 건 열린 회차에서 미리 낸 돈이
 * 남을 때의 환급 하나뿐이다(D3).
 *
 * <p>결제 끝난 카드 거래는 돈 칸을 고칠 수 없으므로(D12) 닫힌 회차 몫은 <b>생기거나 사라질 뿐</b>이다.
 * 그래서 옮겨 간 금액을 짝짓는 일이 없다 — 생성(닫힌 회차 몫 → 표식 + 상계 +)과 제거(기록용 →
 * 상계 −, 결제분 → 결제가 덮은 만큼 붙잡기, 결제 전 → 선결제 환급)가 서로 만나지 않는다.
 *
 * <p>DB 를 모른다. 금액은 호출자가 넘기고, 이 클래스는 "무엇이 기록용이 되고 무엇을 돌려줄 후보인지"만
 * 정한다. 결제가 실제로 덮은 금액(크레딧)은 돈의 기록을 봐야 해서 호출자가 잰다.
 */
public final class CardCycleMath {

    private CardCycleMath() {}

    public static LocalDate cycleStartOf(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    public static LocalDate cycleEndOf(LocalDate cycleStart) {
        return cycleStart.withDayOfMonth(cycleStart.lengthOfMonth());
    }

    /** 표식이 이 회차를 덮는가 — 덮으면 그 회차분은 기록용이다. */
    public static boolean isRecordOnly(LocalDate cycleStart, LocalDate mark) {
        return mark != null && !mark.isBefore(cycleEndOf(cycleStart));
    }

    /**
     * 거래가 회차마다 청구에 얹는 금액 — 지출은 +, 카드 수입(캐시백·취소)은 −.
     *
     * <p>일시불·수입은 거래 날짜의 회차 하나, 할부는 회차분(R5). 할부 회차분은
     * {@link Expense#installmentAmountAt(int)} 로 센다 — 청구 계산과 같은 산식이어야 기록용 금액과
     * 청구에서 빠지는 금액이 갈리지 않는다. 수입도 센다: 닫힌 회차 날짜로 카드 수입을 적거나
     * 지우면 청구가 아니라 상계가 움직여야 한다(없으면 카드가 양수가 되어 자정 스윕이 통장으로 보낸다).
     */
    public static Map<LocalDate, Long> duesByCycle(Expense e) {
        Map<LocalDate, Long> dues = new TreeMap<>();
        if (e == null || e.getExpenseDate() == null || e.getAmount() == null) {
            return dues;
        }
        LocalDate first = cycleStartOf(e.getExpenseDate().toLocalDate());
        if (e.getExpenseType() == ExpenseType.INCOME) {
            dues.put(first, -e.getAmount());
            return dues;
        }
        if (e.getExpenseType() != ExpenseType.EXPENSE) {
            return dues;
        }
        if (!e.isInstallment()) {
            dues.put(first, e.getAmount());
            return dues;
        }
        // 상한까지만 돈다 — 저장 경로는 상한을 넘는 값을 막지만(DAM-9), 그 전에 저장된 행이 Integer.MAX_VALUE 면
        // seq <= months 가 넘쳐 반복이 끝나지 않았다. 상한 밖 회차는 어차피 실재하지 않는 값이다.
        int months = Math.min(e.getInstallmentMonths(), InstallmentLimits.MAX_MONTHS);
        for (int seq = 1; seq <= months; seq++) {
            long due = e.installmentAmountAt(seq);
            if (due != 0L) {
                dues.merge(first.plusMonths(seq - 1L), due, Long::sum);
            }
        }
        return dues;
    }

    /**
     * 한쪽(수정 전·후) 모습 — 신용카드 거래일 때만 회차 규칙이 선다.
     *
     * @param schedule 회차별 결제일. 결제일 없는 카드면 {@link PaymentSchedule#hasDay()} 가 false —
     *                 회차가 닫히지 않는다(D8)
     * @param dues     회차 시작일 → 그 회차에 얹는 금액(지출 +, 수입 −)
     * @param mark     기록용 표식(없으면 null)
     */
    public record Side(Long cardRowId, PaymentSchedule schedule, Map<LocalDate, Long> dues, LocalDate mark) {

        public static Side none() {
            return new Side(null, null, Map.of(), null);
        }

        public boolean isCard() {
            return cardRowId != null && schedule != null;
        }

        public boolean isClosed(LocalDate cycleStart, LocalDate today) {
            return schedule != null && schedule.isClosed(cycleStart, today);
        }
    }

    /**
     * 카드 회차에서 청구가 줄어든 몫 — 결제가 덮고 있었다면 돌려주거나(열린 회차 선결제) 붙잡는다(닫힌 회차).
     *
     * @param cardRowId 그 카드
     * @param closed    닫힌 회차 몫인가 — 닫힌 회차는 돌려주지 않고 결제가 덮은 만큼 붙잡는다(D1). 열린
     *                  회차는 선결제가 남으면 결제계좌로 돌려준다(D3). 덮이지 않은 몫은 원래 빚이라 빚이 줄 뿐이다
     */
    public record PaidRemoval(Long cardRowId, LocalDate cycleStart, long amount, boolean closed) {}

    /**
     * 판정 결과.
     *
     * @param newMark           바뀐 뒤 표식
     * @param settleDeltaBefore 옛 카드의 상계 변화(같은 카드면 새 카드 몫까지 여기 합친다)
     * @param settleDeltaAfter  새 카드의 상계 변화(카드가 바뀔 때만)
     * @param paidRemovals      옛 카드에서 빠진 몫 — 결제가 덮은 만큼 돌려주거나 붙잡을 후보
     * @param newRecordAmount   이번 저장으로 새로 기록용이 된 금액(닫힌 회차에 떨어진 지출)
     * @param afterBillable     옛 카드 회차별로 바뀐 뒤에도 <b>앱 청구 대상</b>으로 남는 이 거래의 몫 —
     *                          미리보기가 "바뀐 뒤 청구액" 을 셀 때 쓴다(같은 카드일 때만)
     */
    public record Plan(LocalDate newMark,
                       long settleDeltaBefore,
                       long settleDeltaAfter,
                       List<PaidRemoval> paidRemovals,
                       long newRecordAmount,
                       Map<LocalDate, Long> afterBillable) {

        /** 아무것도 안 하는 판정 — 카드가 아닌 거래. */
        public static Plan none() {
            return new Plan(null, 0L, 0L, List.of(), 0L, Map.of());
        }
    }

    /**
     * 바뀌기 전 → 뒤로 무엇이 움직이는지 정한다. 생성은 before=none, 삭제·환불은 after=none.
     *
     * <ul>
     *   <li>닫힌 회차에 몫이 생기면(소급 입력·열린 회차에서 옮겨 옴) → 기록용: 상계 +, 표식(R2·D2)</li>
     *   <li>닫힌 회차 몫이 사라지면 → 기록용이면 상계 −(받쳐 둔 것을 걷음), 결제분이면 결제가 덮은
     *       만큼 붙잡기 후보(D1 — 통장으로 돌려주지 않는다)</li>
     *   <li>열린 회차 청구가 줄면 → 선결제가 남았을 때만 돌려줄 후보(D3)</li>
     *   <li>열린 회차 청구가 늘면 → 결제일에 정상 청구(아무것도 안 함)</li>
     * </ul>
     *
     * <p>수입(음수 몫)도 같은 식이다 — 닫힌 회차의 수입이 생기거나 사라지면 상계가 그만큼 반대로 움직여
     * 카드 잔액이 그대로 남는다.
     */
    public static Plan plan(Side before, Side after, LocalDate today) {
        boolean sameCard = before.isCard() && after.isCard()
            && before.cardRowId().equals(after.cardRowId());

        long settleBefore = 0L;
        long settleAfter = 0L;
        long newRecord = 0L;
        TreeSet<LocalDate> recordCycles = new TreeSet<>();
        List<PaidRemoval> removals = new ArrayList<>();

        // 옛 카드 쪽 — 같은 카드면 새 모습과의 차이, 다른 카드(또는 없음)면 통째로 빠진다.
        if (before.isCard()) {
            TreeSet<LocalDate> cycles = new TreeSet<>(before.dues().keySet());
            if (sameCard) {
                cycles.addAll(after.dues().keySet());
            }
            for (LocalDate c : cycles) {
                long b = before.dues().getOrDefault(c, 0L);
                long a = sameCard ? after.dues().getOrDefault(c, 0L) : 0L;
                boolean wasRecord = isRecordOnly(c, before.mark());
                if (a == b) {
                    if (a != 0L && wasRecord && sameCard) {
                        recordCycles.add(c);   // 카테고리만 고친 기록용 — 표식을 지킨다
                    }
                    continue;
                }
                long d = a - b;
                if (before.isClosed(c, today)) {
                    if (d < 0L && b > 0L && !wasRecord) {
                        // 앱이 결제한 지출이 빠졌다 — 돌려주지 않고 결제가 덮은 만큼만 붙잡는다(D1).
                        removals.add(new PaidRemoval(before.cardRowId(), c, -d, true));
                    } else {
                        // 기록용이 빠지거나(받쳐 둔 상계를 걷음) 새 몫이 생기거나 수입이 바뀌었다 — 상계만.
                        settleBefore += d;
                        if (d > 0L && a > 0L) {
                            newRecord += d;
                        }
                    }
                    if (a != 0L && (wasRecord || d > 0L || a < 0L)) {
                        recordCycles.add(c);
                    }
                } else if (wasRecord) {
                    // 열린 회차에 붙은 옛 표식(재청구 방지, D14 로 폐지) — 청구에 없던 몫이라 상계로만 정리한다.
                    settleBefore += d;
                    if (a != 0L) {
                        recordCycles.add(c);
                    }
                } else if (d < 0L) {
                    // 열린 회차 청구가 줄었다 — 미리 낸 돈이 남으면 돌려준다(D3).
                    removals.add(new PaidRemoval(before.cardRowId(), c, -d, false));
                }
            }
        }

        // 새 카드 쪽(다른 카드일 때만) — 닫힌 회차 몫은 기록용으로 생긴다.
        if (after.isCard() && !sameCard) {
            for (Map.Entry<LocalDate, Long> e : after.dues().entrySet()) {
                LocalDate c = e.getKey();
                long a = e.getValue();
                if (a == 0L) {
                    continue;
                }
                if (after.isClosed(c, today)) {
                    settleAfter += a;
                    if (a > 0L) {
                        newRecord += a;
                    }
                    recordCycles.add(c);
                } else if (a < 0L) {
                    // 열린 회차에 새 카드 수입 — 청구가 줄어 미리 낸 돈이 남으면 돌려준다(D3).
                    removals.add(new PaidRemoval(after.cardRowId(), c, -a, false));
                }
            }
        }

        LocalDate newMark = after.isCard() && !recordCycles.isEmpty() ? cycleEndOf(recordCycles.last()) : null;

        Map<LocalDate, Long> afterBillable = new TreeMap<>();
        if (sameCard) {
            for (Map.Entry<LocalDate, Long> e : after.dues().entrySet()) {
                if (!isRecordOnly(e.getKey(), newMark)) {
                    afterBillable.put(e.getKey(), e.getValue());
                }
            }
        }
        return new Plan(newMark, settleBefore, settleAfter, removals, newRecord, afterBillable);
    }
}
