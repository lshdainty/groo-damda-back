package io.groo.damda.card.repository;

import io.groo.damda.card.domain.CardCatalogBenefit;

import java.util.List;

public interface CardCatalogBenefitRepository {
    List<CardCatalogBenefit> findAllByCardCatalog(Long cardCatalogRowId);
}
