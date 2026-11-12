package io.groo.damda.dataimport.sms.controller.dto;

import io.groo.damda.common.validation.InstallmentLimits;
import jakarta.validation.constraints.Max;

import io.groo.damda.asset.type.AssetType;
import io.groo.damda.dataimport.sms.service.SmsConfidence;
import io.groo.damda.dataimport.sms.service.dto.SmsImportServiceDto;
import io.groo.damda.expense.type.ExpenseType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 결제 문자 가져오기 API 요청/응답 DTO.
 */
public class SmsImportApiDto {

    /** 파싱 요청 — 문자 원문 한 통. */
    public record ParseRequest(String text) {}

    /** 자산 후보(어느 카드로 기록할지). */
    public record AssetCandidate(
        Long rowId,
        String assetName,
        String institution,
        AssetType assetType
    ) {
        static AssetCandidate from(SmsImportServiceDto.AssetCandidate c) {
            return new AssetCandidate(c.rowId(), c.assetName(), c.institution(), c.assetType());
        }
    }

    /**
     * 파싱 응답.
     *
     * <p>{@code expenseDate} 는 오프셋 없는 로컬 문자열이다(예: {@code 2026-08-13T13:22}).
     * 클라이언트도 이 값을 그대로 되돌려 보내야 한다 — UTC 로 바꾸면 자정 근처 날짜가 밀린다.
     *
     * @param cancel  취소 문자 — true 면 저장 버튼을 막고 안내만 한다
     */
    public record ParseResponse(
        boolean matched,
        SmsConfidence confidence,
        boolean cancel,
        Long amount,
        String merchant,
        String expenseDate,
        Integer installmentMonths,
        String cardHint,
        String issuerName,
        String cardLast4,
        Long assetRowId,
        boolean assetRemembered,
        List<AssetCandidate> assetCandidates,
        Long categoryRowId,
        String categoryName,
        BigDecimal originalAmount,
        String originalCurrency
    ) {
        public static ParseResponse from(SmsImportServiceDto.ParseResult r) {
            return new ParseResponse(
                r.matched(), r.confidence(), r.cancel(), r.amount(), r.merchant(),
                r.occurredAt() == null ? null : r.occurredAt().toString(),
                r.installmentMonths(), r.cardHint(), r.issuerName(), r.cardLast4(),
                r.assetRowId(), r.assetRemembered(),
                r.assetCandidates().stream().map(AssetCandidate::from).toList(),
                r.categoryRowId(), r.categoryName(), r.originalAmount(), r.originalCurrency());
        }
    }

    /**
     * 저장 요청 — 사용자가 확인·보정한 값 + 원문.
     *
     * <p>원문을 다시 보내는 이유는 서버가 취소 문자를 걸러내고 카드 매핑 키를
     * 스스로 도출하기 위해서다. 값 자체는 아래 필드가 기준이다.
     */
    public record CommitRequest(
        String text,
        Long assetRowId,
        Long categoryRowId,
        /**
         * 거래 종류(INCOME/EXPENSE). <b>비면 지출로 본다</b> — 이 키를 안 보내는
         * 옛 앱이 계속 돌아야 한다. 환불·입금 문자를 수입으로 남기려면 INCOME 을 싣는다.
         */
        ExpenseType expenseType,
        Long amount,
        String merchant,
        String description,
        // "yyyy-MM-dd" 또는 "yyyy-MM-ddTHH:mm[:ss]" — 오프셋 없는 로컬 시각
        String expenseDate,
        /** 결제수단 코드(CASH/CARD/TRANSFER/OTHER). 비면 서버가 카드로 채운다. */
        String paymentMethod,
        @Max(value = InstallmentLimits.MAX_MONTHS, message = "할부는 60개월까지 입력할 수 있어요")
        Integer installmentMonths,
        BigDecimal originalAmount,
        String originalCurrency,
        BigDecimal exchangeRate,
        /** 이 카드를 기억해 다음 문자부터 자동 연결할지. */
        boolean rememberCard
    ) {}

    /** 저장 응답. */
    public record CommitResponse(
        Long expenseRowId,
        boolean cardRemembered
    ) {}

    /** 기억해 둔 카드 매핑 한 건. */
    public record CardMappingResponse(
        Long rowId,
        String cardHint,
        Long assetRowId,
        String assetName
    ) {
        public static CardMappingResponse from(SmsImportServiceDto.CardMappingInfo m) {
            return new CardMappingResponse(m.rowId(), m.cardHint(), m.assetRowId(), m.assetName());
        }
    }

    /** 카드 매핑 목록 응답. */
    public record CardMappingListResponse(List<CardMappingResponse> mappings) {
        public static CardMappingListResponse from(List<SmsImportServiceDto.CardMappingInfo> list) {
            return new CardMappingListResponse(list.stream().map(CardMappingResponse::from).toList());
        }
    }
}
