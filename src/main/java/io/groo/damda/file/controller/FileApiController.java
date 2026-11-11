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

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(info.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFilename + "\"")
            .body(resource);
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
