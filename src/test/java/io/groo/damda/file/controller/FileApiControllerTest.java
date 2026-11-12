package io.groo.damda.file.controller;

import io.groo.core.util.MessageResolver;
import io.groo.damda.common.config.web.WebConfig;
import io.groo.damda.file.service.FileAttachmentService;
import io.groo.damda.file.service.FileStorageService;
import io.groo.damda.file.service.dto.FileServiceDto;
import io.groo.damda.file.type.ReferenceType;
import io.groo.damda.security.filter.JwtAuthenticationFilter;
import io.groo.damda.security.resolver.LoginUserArgumentResolver;
import io.groo.damda.support.security.WithLoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * File API 슬라이스 테스트 — 멀티파트 업로드/다운로드 포함.
 *
 * <p>보안 필터는 끄고({@code addFilters=false}) {@link WithLoginUser} 로 SecurityContext 를 세팅 →
 * {@code @LoginUser} ArgumentResolver({@link WebConfig} 가 등록)가 UserPrincipal 을 주입한다.
 */
@WebMvcTest(controllers = FileApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, LoginUserArgumentResolver.class})
@ActiveProfiles("test")
@WithLoginUser(rowId = 1L)
class FileApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FileAttachmentService fileAttachmentService;
    @MockitoBean private FileStorageService fileStorageService;
    // groo-core GlobalExceptionHandler(@ControllerAdvice) 의존 — 슬라이스 로드용 mock.
    @MockitoBean private MessageResolver messageResolver;

    private FileServiceDto.FileInfo sampleInfo() {
        return new FileServiceDto.FileInfo(
                100L, 1L, "report.txt", "stored.txt", "/data/stored.txt",
                "text/plain", 5L, ReferenceType.MEMO_ATTACHMENT.name(), 10L, "2026-07-01T09:00:00");
    }

    @Test
    @DisplayName("POST /files/upload — 멀티파트 파일·referenceType·referenceRowId 로 업로드 위임")
    void uploadFile() throws Exception {
        given(fileAttachmentService.uploadFile(any(), eq(1L), eq(ReferenceType.MEMO_ATTACHMENT), eq(10L)))
                .willReturn(sampleInfo());

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("referenceType", "MEMO_ATTACHMENT")
                        .param("referenceRowId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowId").value(100))
                .andExpect(jsonPath("$.data.originalName").value("report.txt"))
                .andExpect(jsonPath("$.data.referenceType").value("MEMO_ATTACHMENT"));

        var captor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(fileAttachmentService).uploadFile(
                captor.capture(), eq(1L), eq(ReferenceType.MEMO_ATTACHMENT), eq(10L));
        assertThat(captor.getValue().getOriginalFilename()).isEqualTo("report.txt");
    }

    @Test
    @DisplayName("POST /files/upload — referenceRowId 미지정이면 null 로 위임")
    void uploadFileWithoutReferenceRowId() throws Exception {
        given(fileAttachmentService.uploadFile(any(), eq(1L), eq(ReferenceType.EXPENSE_RECEIPT), isNull()))
                .willReturn(sampleInfo());

        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("referenceType", "EXPENSE_RECEIPT"))
                .andExpect(status().isOk());

        verify(fileAttachmentService).uploadFile(
                any(), eq(1L), eq(ReferenceType.EXPENSE_RECEIPT), isNull());
    }

    @Test
    @DisplayName("GET /files/{id} — 파일 로드 후 inline Content-Disposition 으로 다운로드")
    void downloadFile() throws Exception {
        Path tempFile = Files.createTempFile("porest-file-test", ".txt");
        Files.write(tempFile, "hello".getBytes());
        tempFile.toFile().deleteOnExit();

        given(fileAttachmentService.getFile(7L, 1L)).willReturn(sampleInfo());
        given(fileStorageService.load(anyString())).willReturn(tempFile);

        mockMvc.perform(get("/api/v1/files/{id}", 7L))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/plain")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("report.txt")))
                .andExpect(content().bytes("hello".getBytes()));

        verify(fileAttachmentService).getFile(7L, 1L);
    }

    @Test
    @DisplayName("GET /files/{id} — SVG 는 내려받기로 보낸다(브라우저에서 바로 열면 스크립트가 이 출처에서 돈다, 보안 점검 DAM-8)")
    void svgIsDownloadedNotOpened() throws Exception {
        Path tempFile = Files.createTempFile("porest-file-test", ".svg");
        Files.write(tempFile, "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes());
        tempFile.toFile().deleteOnExit();

        given(fileAttachmentService.getFile(8L, 1L)).willReturn(new FileServiceDto.FileInfo(
                101L, 1L, "logo.svg", "stored.svg", "/data/stored.svg",
                "image/svg+xml", 80L, ReferenceType.MEMO_ATTACHMENT.name(), 10L, "2026-07-01T09:00:00"));
        given(fileStorageService.load(anyString())).willReturn(tempFile);

        mockMvc.perform(get("/api/v1/files/{id}", 8L))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("바로 여는 형식은 래스터 이미지·PDF·텍스트뿐이다 — 매개변수·대소문자는 무시")
    void inlineAllowList() {
        assertThat(FileApiController.opensInline("image/png")).isTrue();
        assertThat(FileApiController.opensInline("IMAGE/JPEG")).isTrue();
        assertThat(FileApiController.opensInline("application/pdf")).isTrue();
        assertThat(FileApiController.opensInline("text/plain; charset=UTF-8")).isTrue();
        assertThat(FileApiController.opensInline("image/svg+xml")).isFalse();
        assertThat(FileApiController.opensInline("text/csv")).isFalse();
        assertThat(FileApiController.opensInline("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).isFalse();
        assertThat(FileApiController.opensInline(null)).isFalse();
    }

    @Test
    @DisplayName("GET /files — referenceType·referenceRowId 쿼리로 참조별 목록 조회")
    void getFilesByReference() throws Exception {
        given(fileAttachmentService.getFilesByReference(ReferenceType.MEMO_ATTACHMENT, 10L, 1L))
                .willReturn(List.of(sampleInfo()));

        mockMvc.perform(get("/api/v1/files")
                        .param("referenceType", "MEMO_ATTACHMENT")
                        .param("referenceRowId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].rowId").value(100));

        verify(fileAttachmentService).getFilesByReference(ReferenceType.MEMO_ATTACHMENT, 10L, 1L);
    }

    @Test
    @DisplayName("GET /files — 잘못된 referenceType enum 값이면 400")
    void getFilesByReferenceInvalidEnum() throws Exception {
        mockMvc.perform(get("/api/v1/files")
                        .param("referenceType", "INVALID")
                        .param("referenceRowId", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /files/{id} — id·로그인 사용자로 삭제 위임")
    void deleteFile() throws Exception {
        mockMvc.perform(delete("/api/v1/files/{id}", 7L))
                .andExpect(status().isOk());

        verify(fileAttachmentService).deleteFile(7L, 1L);
    }
}
