package io.groo.damda.dataimport.sms.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.common.time.WallClockDateTimeParser;
import io.groo.damda.dataimport.sms.controller.dto.SmsImportApiDto;
import io.groo.damda.dataimport.sms.service.SmsImportService;
import io.groo.damda.dataimport.sms.service.dto.SmsImportServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 문자 가져오기 — 파싱(미리보기) + 저장 + 카드 매핑 관리.
 *
 * <p>파일 가져오기의 analyze/execute 와 같은 2단 계약이다. parse 는 아무것도 저장하지
 * 않으므로 클라이언트가 마음껏 불러도 되고, commit 만 지출을 만든다.
 *
 * <p>웹·iOS·안드로이드가 같은 엔드포인트를 쓴다 — 카드사 포맷이 늘어도 서버 배포로 끝난다.
 */
@RestController
@RequestMapping("/api/v1/import/sms")
@RequiredArgsConstructor
public class SmsImportApiController {

    private final SmsImportService smsImportService;

    /** 문자 해석 — 저장 없음. 자산·카테고리 추론 결과까지 함께 준다. */
    @PostMapping("/parse")
    public ApiResponse<SmsImportApiDto.ParseResponse> parse(
            @LoginUser UserPrincipal loginUser,
            @RequestBody SmsImportApiDto.ParseRequest request) {
        SmsImportServiceDto.ParseResult result =
            smsImportService.parse(request.text(), loginUser.getRowId());
        return ApiResponse.success(SmsImportApiDto.ParseResponse.from(result));
    }

    /** 확정 값으로 지출 생성. */
    @PostMapping("/commit")
    public ApiResponse<SmsImportApiDto.CommitResponse> commit(
            @LoginUser UserPrincipal loginUser,
            @RequestBody SmsImportApiDto.CommitRequest request) {
        SmsImportServiceDto.CommitResult result = smsImportService.commit(
            new SmsImportServiceDto.CommitCommand(
                loginUser.getRowId(),
                request.text(),
                request.assetRowId(),
                request.categoryRowId(),
                request.expenseType(),
                request.amount(),
                request.merchant(),
                request.description(),
                WallClockDateTimeParser.parse(request.expenseDate()),
                request.paymentMethod(),
                request.installmentMonths(),
                request.originalAmount(),
                request.originalCurrency(),
                request.exchangeRate(),
                request.rememberCard()));
        return ApiResponse.success(
            new SmsImportApiDto.CommitResponse(result.expenseRowId(), result.cardRemembered()));
    }

    /** 기억해 둔 카드 매핑 목록 — 설정 화면에서 확인·해제한다. */
    @GetMapping("/cards")
    public ApiResponse<SmsImportApiDto.CardMappingListResponse> getCardMappings(
            @LoginUser UserPrincipal loginUser) {
        return ApiResponse.success(SmsImportApiDto.CardMappingListResponse.from(
            smsImportService.getCardMappings(loginUser.getRowId())));
    }

    /** 카드 매핑 해제. */
    @DeleteMapping("/cards/{mappingId}")
    public ApiResponse<Void> deleteCardMapping(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long mappingId) {
        smsImportService.deleteCardMapping(mappingId, loginUser.getRowId());
        return ApiResponse.success();
    }
}
