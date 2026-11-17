package io.groo.damda.card.repository;

import io.groo.damda.card.domain.CardCatalogBenefit;
import io.groo.damda.card.domain.QCardCatalogBenefit;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Primary
@RequiredArgsConstructor
public class CardCatalogBenefitQueryDslRepository implements CardCatalogBenefitRepository {
    private final JPAQueryFactory queryFactory;
    private static final QCardCatalogBenefit benefit = QCardCatalogBenefit.cardCatalogBenefit;

    @Override
    public List<CardCatalogBenefit> findAllByCardCatalog(Long cardCatalogRowId) {
        return queryFactory.selectFrom(benefit)
            .where(benefit.cardCatalog.rowId.eq(cardCatalogRowId))
            .orderBy(benefit.kind.asc(), benefit.sortOrder.asc(), benefit.rowId.asc())
            .fetch();
    }
}
