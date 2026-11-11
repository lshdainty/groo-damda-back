package io.groo.damda.card.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.card.controller.dto.CardBenefitCategoryMappingApiDto;
import io.groo.damda.card.controller.dto.CardCatalogApiDto;
import io.groo.damda.card.service.CardBenefitCategoryMappingService;
import io.groo.damda.card.service.dto.CardBenefitCategoryMappingServiceDto;
import io.groo.damda.card.service.dto.CardCatalogServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CardBenefitCategoryMappingApiController {
    private final CardBenefitCategoryMappingService cardBenefitCategoryMappingService;

    @GetMapping("/card-benefit-mappings")
    public ApiResponse<CardBenefitCategoryMappingApiDto.ListResponse> getMappings(
        @LoginUser UserPrincipal loginUser
    ) {
        List<CardBenefitCategoryMappingServiceDto.MappingInfo> infos =
            cardBenefitCategoryMappingService.getEffectiveMappings(loginUser.getRowId());
        return ApiResponse.success(CardBenefitCategoryMappingApiDto.ListResponse.from(infos));
    }

    @PostMapping("/card-benefit-mappings")
    public ApiResponse<CardBenefitCategoryMappingApiDto.MappingResponse> createOrUpdateMapping(
        @LoginUser UserPrincipal loginUser,
        @RequestBody CardBenefitCategoryMappingApiDto.CreateRequest request
    ) {
        CardBenefitCategoryMappingServiceDto.MappingInfo info = cardBenefitCategoryMappingService.upsertMapping(
            new CardBenefitCategoryMappingServiceDto.CreateCommand(
                loginUser.getRowId(),
                request.benefitCategory(),
                request.expenseCategoryRowId()
            )
        );
        return ApiResponse.success(CardBenefitCategoryMappingApiDto.MappingResponse.from(info));
    }

    @DeleteMapping("/card-benefit-mappings/{id}")
    public ApiResponse<Void> deleteMapping(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long id
    ) {
        cardBenefitCategoryMappingService.deleteMapping(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    /**
     * 특정 카드의 특정 경비 카테고리에 적용될 수 있는 혜택 조회.
     * ExpenseForm 에서 asset + category 선택 시 표시할 혜택 배너 데이터 소스.
     */
    @GetMapping("/card-catalogs/{cardRowId}/available-benefits")
    public ApiResponse<List<CardCatalogApiDto.BenefitResponse>> getAvailableBenefits(
        @LoginUser UserPrincipal loginUser,
        @PathVariable Long cardRowId,
        @RequestParam Long expenseCategoryRowId
    ) {
        List<CardCatalogServiceDto.BenefitInfo> benefits = cardBenefitCategoryMappingService
            .getAvailableBenefits(loginUser.getRowId(), cardRowId, expenseCategoryRowId);
        List<CardCatalogApiDto.BenefitResponse> response = benefits.stream()
            .map(CardCatalogApiDto.BenefitResponse::from)
            .toList();
        return ApiResponse.success(response);
    }
}
