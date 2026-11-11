package io.groo.damda.export.controller;

import io.groo.core.audit.AccessAction;
import io.groo.core.audit.AuditAccess;
import io.groo.core.controller.ApiResponse;
import io.groo.core.util.FileUploadValidator;
import io.groo.damda.export.controller.dto.ExportApiDto;
import io.groo.damda.export.service.ExportService;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 데이터 내보내기 — 파일 생성/스트리밍 다운로드 + 건수 + 미리보기.
 *
 * <p>대용량 대응: {@link StreamingResponseBody} 로 응답을 청크 스트리밍해 파일 전체를
 * 메모리에 버퍼링하지 않는다. 본문 작성은 서비스의 @Transactional(readOnly) 안에서 수행.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExportApiController {

    private final ExportService exportService;

    /**
     * 파일 생성 + 다운로드 (CSV/Excel/JSON, 다종은 ZIP).
     *
     * <p>본인 데이터지만 대량 반출이라 흔적을 남긴다 — 계정이 탈취됐을 때 소비 내역
     * 전체가 빠져나간 사실을 사후에 확인할 수 있어야 한다. 응답이 스트리밍이라
     * 기록 시점은 "다운로드 요청" 이지 "전송 완료" 가 아니다.</p>
     */
    @AuditAccess(action = AccessAction.EXPORT, targetType = "LEDGER", detail = "데이터 내보내기")
    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ExportApiDto.ExportRequest request) {
        ExportService.ExportDescriptor desc = exportService.describe(request, loginUser.getRowId());
        String safeFilename = FileUploadValidator.sanitizeForContentDisposition(desc.filename());

        StreamingResponseBody body = out -> exportService.writeExport(out, request, loginUser.getRowId());

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(desc.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename + "\"")
            .body(body);
    }

    /** 선택 종 + 기간의 건수 (체크박스 옆 배지용). */
    @PostMapping("/export/counts")
    public ApiResponse<ExportApiDto.CountResponse> counts(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ExportApiDto.CountRequest request) {
        return ApiResponse.success(exportService.counts(request, loginUser.getRowId()));
    }

    /** 선택 종 + 기간의 미리보기 (종별 상위 N행). */
    @PostMapping("/export/preview")
    public ApiResponse<ExportApiDto.PreviewResponse> preview(
            @LoginUser UserPrincipal loginUser,
            @RequestBody ExportApiDto.PreviewRequest request) {
        return ApiResponse.success(exportService.preview(request, loginUser.getRowId()));
    }
}
