package io.groo.damda.expense.repository;

import io.groo.core.type.YNType;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.domain.QExpense;
import io.groo.damda.asset.type.AssetType;
import io.groo.damda.expense.type.ExpenseType;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
@Primary
@RequiredArgsConstructor
public class ExpenseQueryDslRepository implements ExpenseRepository {
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;
    private static final QExpense expense = QExpense.expense;

    /**
     * LocalDate → LocalDateTime 변환 헬퍼
     * startDate: 해당 일자 00:00:00 (하루의 시작)
     * endDate:   해당 일자 23:59:59.999999999 (하루의 끝)
     */
    private static LocalDateTime toStartOfDay(LocalDate d) {
        return d.atStartOfDay();
    }

    private static LocalDateTime toEndOfDay(LocalDate d) {
        return d.atTime(LocalTime.MAX);
    }

    @Override
    public Optional<Expense> findById(Long rowId) {
        return Optional.ofNullable(
            queryFactory.selectFrom(expense)
                .leftJoin(expense.category).fetchJoin()
                .leftJoin(expense.asset).fetchJoin()
                .where(expense.rowId.eq(rowId), expense.isDeleted.eq(YNType.N))
                .fetchOne()
        );
    }

    @Override
    public List<Expense> findActiveByCategory(Long categoryRowId) {
        return queryFactory.selectFrom(expense)
            .where(expense.category.rowId.eq(categoryRowId), expense.isDeleted.eq(YNType.N))
            .fetch();
    }

    @Override
    public boolean existsByCategory(Long categoryRowId) {
        return queryFactory.selectOne()
            .from(expense)
            .where(
                expense.category.rowId.eq(categoryRowId),
                expense.isDeleted.eq(YNType.N)
            )
            .fetchFirst() != null;
    }

    @Override
    public long sumAmountByCategoryRollup(Long userRowId, Long categoryRowId, ExpenseType expenseType,
                                          LocalDate startDate, LocalDate endDate) {
        List<Long> amounts = queryFactory.select(expense.amount)
            .from(expense)
            .where(
                expense.user.rowId.eq(userRowId),
                // 예산은 가계부 숫자다 — 환불된 것과 카드 이월을 뺀다(ExpensePredicates).
                ExpensePredicates.ledgerCountable(),
                expense.expenseType.eq(expenseType),
                expense.expenseDate.goe(toStartOfDay(startDate)),
                expense.expenseDate.loe(toEndOfDay(endDate)),
                // 카테고리 본인 + 하위 카테고리 지출까지 합산 (자식 → 부모 roll-up)
                expense.category.rowId.eq(categoryRowId)
                    .or(expense.category.parent.rowId.eq(categoryRowId))
            )
            .fetch();
        return amounts.stream().filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    @Override
    public List<Expense> findByUser(Long userRowId, Long categoryRowId, ExpenseType expenseType, LocalDate startDate, LocalDate endDate) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(expense.user.rowId.eq(userRowId));
        builder.and(expense.isDeleted.eq(YNType.N));

        if (categoryRowId != null) {
            builder.and(expense.category.rowId.eq(categoryRowId));
        }
        if (expenseType != null) {
            builder.and(expense.expenseType.eq(expenseType));
        }
        if (startDate != null) {
            builder.and(expense.expenseDate.goe(toStartOfDay(startDate)));
        }
        if (endDate != null) {
            builder.and(expense.expenseDate.loe(toEndOfDay(endDate)));
        }

        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(builder)
            .orderBy(expense.expenseDate.desc(), expense.rowId.desc())
            .fetch();
    }

    @Override
    public List<Expense> findDailySummary(Long userRowId, LocalDate date) {
        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(
                expense.user.rowId.eq(userRowId),
                expense.isDeleted.eq(YNType.N),
                expense.expenseDate.goe(toStartOfDay(date)),
                expense.expenseDate.loe(toEndOfDay(date))
            )
            .orderBy(expense.rowId.desc())
            .fetch();
    }

    @Override
    public List<Expense> findByDateRange(Long userRowId, LocalDate startDate, LocalDate endDate) {
        return findByDateRange(userRowId, startDate, endDate, null);
    }

    @Override
    public List<Expense> findByDateRange(Long userRowId, LocalDate startDate, LocalDate endDate,
                                         Long assetRowId) {
        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(
                expense.user.rowId.eq(userRowId),
                expense.isDeleted.eq(YNType.N),
                expense.expenseDate.goe(toStartOfDay(startDate)),
                expense.expenseDate.loe(toEndOfDay(endDate)),
                // 자산 필터 — 목록·캘린더가 걸러지는데 상단 합계만 전체 값이라 안 맞았다.
                assetRowId == null ? null : expense.asset.rowId.eq(assetRowId)
            )
            .orderBy(expense.expenseDate.desc(), expense.rowId.desc())
            .fetch();
    }

    @Override
    public List<Expense> search(Long userRowId, Long categoryRowId, Long assetRowId, ExpenseType expenseType,
                                String keyword, String merchant, Long minAmount, Long maxAmount,
                                LocalDate startDate, LocalDate endDate) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(expense.user.rowId.eq(userRowId));
        builder.and(expense.isDeleted.eq(YNType.N));

        if (categoryRowId != null) {
            builder.and(expense.category.rowId.eq(categoryRowId));
        }
        if (assetRowId != null) {
            // 통장을 조회하면 거기 물린 체크카드로 쓴 거래도 함께 보여준다 —
            // 체크카드 지출은 잔액이 이 통장에서 빠지므로, 안 보이면 잔액만 줄고
            // 거래는 없는 화면이 된다. 카드 자체를 조회할 땐 eq 로 그대로 걸린다.
            builder.and(expense.asset.rowId.eq(assetRowId)
                .or(expense.asset.assetType.eq(AssetType.CHECK_CARD)
                    .and(expense.asset.paymentAsset.rowId.eq(assetRowId))));
        }
        if (expenseType != null) {
            builder.and(expense.expenseType.eq(expenseType));
        }
        if (keyword != null && !keyword.isBlank()) {
            // 키워드는 메모 또는 거래처에 걸린다 — 앱 검색 안내가 "키워드, 가맹점, 메모로" 인데
            // 메모만 봐서 거래처만 적은 거래가 안 찾혔다(QA 30 11). 거래처만 따로 거르는
            // merchant 파라미터는 그대로다.
            builder.and(expense.description.containsIgnoreCase(keyword)
                .or(expense.merchant.containsIgnoreCase(keyword)));
        }
        if (merchant != null && !merchant.isBlank()) {
            builder.and(expense.merchant.containsIgnoreCase(merchant));
        }
        if (minAmount != null) {
            builder.and(expense.amount.goe(minAmount));
        }
        if (maxAmount != null) {
            builder.and(expense.amount.loe(maxAmount));
        }
        if (startDate != null) {
            builder.and(expense.expenseDate.goe(toStartOfDay(startDate)));
        }
        if (endDate != null) {
            builder.and(expense.expenseDate.loe(toEndOfDay(endDate)));
        }

        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(builder)
            .orderBy(expense.expenseDate.desc(), expense.rowId.desc())
            .fetch();
    }

    @Override
    public List<Expense> findByCalendarEvent(Long userRowId, Long calendarEventRowId) {
        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(
                expense.user.rowId.eq(userRowId),
                expense.calendarEvent.rowId.eq(calendarEventRowId),
                expense.isDeleted.eq(YNType.N)
            )
            .orderBy(expense.expenseDate.desc())
            .fetch();
    }

    @Override
    public List<Expense> findByTodo(Long userRowId, Long todoRowId) {
        return queryFactory.selectFrom(expense)
            .leftJoin(expense.category).fetchJoin()
            .leftJoin(expense.asset).fetchJoin()
            .where(
                expense.user.rowId.eq(userRowId),
                expense.todo.rowId.eq(todoRowId),
                expense.isDeleted.eq(YNType.N)
            )
            .orderBy(expense.expenseDate.desc())
            .fetch();
    }

    @Override
    public Expense save(Expense entity) {
        entityManager.persist(entity);
        return entity;
    }

    @Override
    public void delete(Expense entity) {
        entity.deleteExpense();
    }

    @Override
    public List<Object[]> sumGroupedByDayOfWeekAndHour(Long userRowId, ExpenseType expenseType,
                                                       LocalDate startDate, LocalDate endDate) {
        return entityManager.createQuery(
                "SELECT FUNCTION('DAYOFWEEK', e.expenseDate), " +
                "       FUNCTION('HOUR', e.expenseDate), " +
                "       COALESCE(SUM(e.amount), 0) " +
                "FROM Expense e " +
                "WHERE e.user.rowId = :userRowId " +
                "  AND e.expenseType = :expenseType " +
                "  AND e.expenseDate >= :startDate " +
                "  AND e.expenseDate <= :endDate " +
                // 히트맵도 가계부 숫자다 — 환불된 것과 카드 이월을 뺀다.
                "  AND e.refundedAt IS NULL " +
                "  AND (e.autoSource IS NULL OR e.autoSource NOT IN (:carryover)) " +
                "  AND e.isDeleted = :isDeleted " +
                "GROUP BY FUNCTION('DAYOFWEEK', e.expenseDate), FUNCTION('HOUR', e.expenseDate)",
                Object[].class)
            .setParameter("userRowId", userRowId)
            .setParameter("expenseType", expenseType)
            .setParameter("startDate", toStartOfDay(startDate))
            .setParameter("endDate", toEndOfDay(endDate))
            .setParameter("carryover", Expense.CARD_CARRYOVER_SOURCES)
            .setParameter("isDeleted", YNType.N)
            .getResultList();
    }

    @Override
    public Optional<Long> findRecentCategoryRowIdByMerchant(Long userRowId, String merchant,
                                                            ExpenseType expenseType) {
        if (merchant == null || merchant.isBlank()) return Optional.empty();
        Long categoryRowId = queryFactory.select(expense.category.rowId)
            .from(expense)
            .where(
                expense.user.rowId.eq(userRowId),
                expense.expenseType.eq(expenseType),
                expense.merchant.equalsIgnoreCase(merchant.trim()),
                expense.category.isNotNull(),
                expense.isDeleted.eq(YNType.N)
            )
            .orderBy(expense.expenseDate.desc(), expense.rowId.desc())
            .fetchFirst();
        return Optional.ofNullable(categoryRowId);
    }

    @Override
    public Optional<Expense> findActiveByAssetAndAutoSource(Long assetRowId, String autoSource) {
        if (assetRowId == null || autoSource == null) return Optional.empty();
        return Optional.ofNullable(queryFactory.selectFrom(expense)
            .where(
                expense.asset.rowId.eq(assetRowId),
                expense.autoSource.eq(autoSource),
                expense.isDeleted.eq(YNType.N)
            )
            .orderBy(expense.rowId.asc())
            .fetchFirst());
    }
}
