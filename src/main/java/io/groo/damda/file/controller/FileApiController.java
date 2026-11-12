package io.groo.damda.file.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.core.util.FileUploadValidator;
import io.groo.damda.file.controller.dto.FileApiDto;
import io.groo.damda.file.service.FileAttachmentService;
import io.groo.damda.file.service.FileStorageService;
import io.groo.damda.file.service.dto.FileServiceDto;
import io.groo.damda.file.type.ReferenceType;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FileApiController {
    private final FileAttachmentService fileAttachmentService;
    private final FileStorageService fileStorageService;

    @PostMapping("/files/upload")
    public ApiResponse<FileApiDto.Response> uploadFile(
            @LoginUser UserPrincipal loginUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam("referenceType") ReferenceType referenceType,
            @RequestParam(value = "referenceRowId", required = false) Long referenceRowId) {
        FileServiceDto.FileInfo info = fileAttachmentService.uploadFile(
            file, loginUser.getRowId(), referenceType, referenceRowId
        );
        return ApiResponse.success(FileApiDto.Response.from(info));
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> downloadFile(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) throws MalformedURLException {
        FileServiceDto.FileInfo info = fileAttachmentService.getFile(id, loginUser.getRowId());
        Path filePath = fileStorageService.load(info.filePath());
        Resource resource = new UrlResource(filePath.toUri());

        // Content-Disposition 헤더 인젝션 방지
        String safeFilename = FileUploadValidator.sanitizeForContentDisposition(info.originalName());
        String disposition = opensInline(info.contentType()) ? "inline" : "attachment";

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(info.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + safeFilename + "\"")
            // 브라우저가 내용을 보고 형식을 다시 추측하지 않게 한다 — 텍스트로 올린 파일이 HTML 로 열리는 길을 막는다.
            .header("X-Content-Type-Options", "nosniff")
            .body(resource);
    }

    /**
     * 브라우저에서 바로 열어도 되는 형식 — 스크립트를 실행할 수 없는 것만.
     *
     * <p>SVG 는 이미지지만 XML 문서라 {@code <script>} 를 품을 수 있고, 주소창에서 바로 열면 이 API 의
     * 출처에서 실행된다(보안 점검 DAM-8). 그래서 목록에 없는 형식은 전부 내려받기로 보낸다.
     * {@code <img>} 로 띄우는 미리보기는 Content-Disposition 과 무관하게 그대로 보인다.
     */
    private static final Set<String> INLINE_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
        "application/pdf", "text/plain"
    );

    static boolean opensInline(String contentType) {
        if (contentType == null) return false;
        String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return INLINE_TYPES.contains(base);
    }

    @GetMapping("/files")
    public ApiResponse<FileApiDto.ListResponse> getFilesByReference(
            @LoginUser UserPrincipal loginUser,
            @RequestParam ReferenceType referenceType,
            @RequestParam Long referenceRowId) {
        List<FileServiceDto.FileInfo> infos = fileAttachmentService.getFilesByReference(referenceType, referenceRowId, loginUser.getRowId());
        return ApiResponse.success(FileApiDto.ListResponse.from(infos));
    }

    @DeleteMapping("/files/{id}")
    public ApiResponse<Void> deleteFile(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        fileAttachmentService.deleteFile(id, loginUser.getRowId());
        return ApiResponse.success();
    }

}
