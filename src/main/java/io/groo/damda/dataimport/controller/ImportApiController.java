package io.groo.damda.dataimport.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.dataimport.controller.dto.ImportApiDto;
import io.groo.damda.dataimport.service.ImportService;
import io.groo.damda.dataimport.service.StandardRow;
import io.groo.damda.dataimport.type.ImportSource;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 데이터 가져오기 — 파일 분석(미리보기) + 실제 저장.
 *
 * <p>둘 다 multipart 업로드. analyze 는 파일+소스로 자동매핑·미리보기를 돌려주고,
 * execute 는 파일 + (사용자 보정 매핑·옵션 JSON part)로 거래를 생성한다(stateless 재업로드).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ImportApiController {

    private final ImportService importService;

    /** 파일 분석 — 자동매핑 제안 + 미리보기 + 유효/중복 건수. */
    @PostMapping(value = "/import/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportApiDto.AnalyzeResponse> analyze(
            @LoginUser UserPrincipal loginUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam("source") ImportSource source) {
        ImportService.AnalyzeResult result = importService.analyze(file, source, loginUser.getRowId());
        return ApiResponse.success(toAnalyzeResponse(result));
    }

    /** 실제 저장 — 최종 매핑·옵션대로 거래 생성. */
    @PostMapping(value = "/import/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportApiDto.ExecuteResponse> execute(
            @LoginUser UserPrincipal loginUser,
            @RequestPart("file") MultipartFile file,
            @RequestPart("request") ImportApiDto.ExecuteRequest request) {
        ImportService.ExecuteResult result = importService.execute(
            file, request.source(), request.mapping(),
            request.dupSkip(), request.autoCat(), loginUser.getRowId());
        List<ImportApiDto.FailureRow> failures = result.failures().stream()
            .map(f -> new ImportApiDto.FailureRow(f.lineNo(), f.reason()))
            .toList();
        // 실패 한 건마다 목록에도 한 줄이 들어가므로, 개수가 어긋나면 목록이 상한에서 잘린 것이다.
        boolean truncated = result.failed() > failures.size();
        // 중복으로 건너뛴 행 — 미리보기와 같은 모양으로 내려 화면이 같은 표를 재사용한다.
        List<ImportApiDto.PreviewRow> duplicates = result.duplicates().stream()
            .map(ImportApiController::toPreview)
            .toList();
        boolean dupTruncated = result.duplicateSkipped() > duplicates.size();
        return ApiResponse.success(new ImportApiDto.ExecuteResponse(
            result.imported(), result.skipped(), result.failed(), failures,
            truncated, result.createdCategories(), result.createdCategoryCount(),
            result.duplicateSkipped(), duplicates, dupTruncated));
    }

    // ── 매핑 헬퍼 ────────────────────────────────────────────

    private ImportApiDto.AnalyzeResponse toAnalyzeResponse(ImportService.AnalyzeResult r) {
        List<ImportApiDto.ColumnInfo> columns = new ArrayList<>();
        for (int i = 0; i < r.columns().size(); i++) {
            columns.add(new ImportApiDto.ColumnInfo(i, r.columns().get(i)));
        }
        List<ImportApiDto.PreviewRow> preview = r.preview().stream()
            .map(ImportApiController::toPreview)
            .toList();
        return new ImportApiDto.AnalyzeResponse(
            r.fileName(), r.totalRows(), r.validRows(), r.duplicateCount(),
            columns, r.suggestedMapping(), preview, r.blockedParents(),
            r.newCategories(), r.newCategoryCount());
    }

    private static ImportApiDto.PreviewRow toPreview(StandardRow s) {
        return new ImportApiDto.PreviewRow(
            s.lineNo(),
            s.date() == null ? null : s.date().toString(),
            s.type() == null ? null : s.type().name(),
            s.amount(),
            categoryPath(s.category(), s.subcategory()),
            s.asset(),
            s.memo(),
            s.duplicate(),
            s.error(),
            s.merchant());
    }

    /** 미리보기 카테고리 — 대분류/소분류가 부모/자식으로 들어가므로 경로로 보여준다. */
    private static String categoryPath(String category, String subcategory) {
        if (category != null && subcategory != null) return category + " > " + subcategory;
        if (category != null) return category;
        return subcategory;
    }
}
