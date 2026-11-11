package io.groo.damda.common.config.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 필터를 <b>실제 요청에 태워</b> 왕복을 확인한다.
 *
 * <p>이 테스트가 있는 이유: "마스킹된 문자열이 로그에 있다" 만 보는 단위 테스트는
 * 이 필터의 진짜 사고를 못 잡는다. 필터가 서블릿 입력 스트림을 소비하면 컨트롤러가
 * <b>빈 본문</b>을 받아 모든 POST/PUT 이 조용히 깨지고, {@code copyBodyToResponse()} 를
 * 건너뛰면 클라이언트가 <b>빈 응답</b>을 받는다. 둘 다 로그만 보면 멀쩡해 보인다.
 * desk 는 인증을 다루므로 여기서 깨지면 로그인이 통째로 안 된다.
 *
 * <p>그래서 여기서는 항상 두 가지를 같이 본다 —
 * <b>컨트롤러가 받은 본문</b>과 <b>클라이언트가 받은 본문</b>이 원문 그대로인지,
 * 그리고 같은 요청의 <b>로그 줄</b>에는 민감값이 없는지.
 */
class RequestResponseLoggingFilterRoundTripTest {

    /** 실제 토큰 모양(JWT). 컨트롤러·클라이언트에는 원문 그대로 오가야 하고 로그에만 안 나와야 한다. */
    private static final String JWT =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0"
                    + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
        originalLevel = filterLogger.getLevel();
        // 잘린 본문의 DEBUG 전문 로깅까지 잡아야 한다 — 거기가 예전에 토큰이 통째로 남던 자리다.
        filterLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(originalLevel);
        appender.stop();
    }

    private String loggedText() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private MockMvc mockMvc(RequestResponseLoggingFilter filter) {
        return MockMvcBuilders.standaloneSetup(new EchoController())
                .addFilter(filter, "/*")
                .build();
    }

    private MockMvc mockMvc() {
        return mockMvc(new RequestResponseLoggingFilter());
    }

    // ────────────────────────────────────────────────────────────────────
    // 사고 1 — 요청 본문이 컨트롤러에 도달하지 못한다
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST 본문이 컨트롤러에 원문 그대로 도착한다 — 마스킹은 로그에만 걸린다")
    void postBodyReachesControllerIntact() throws Exception {
        String body = "{\"clientId\":\"tsck_live_ArfgoFbLDafecJFMjelTAeB\","
                + "\"clientSecret\":\"tssk_live_ebkuEpOmb5T6XtZ538o1xZtrDs10Zu3acztY8n5yF3a\"}";

        String received = mockMvc().perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 컨트롤러가 받은 본문 == 보낸 본문. 필터가 스트림을 소비했다면 여기가 빈 문자열이 된다.
        assertThat(received).isEqualTo(body);

        // 같은 요청의 로그에는 시크릿이 없어야 한다
        String logged = loggedText();
        assertThat(logged).doesNotContain("tssk_live_").doesNotContain("tsck_live_");
        assertThat(logged).contains("\"clientSecret\":\"***\"");
        // desk 고유 추가 키 — core 기본 목록에는 없다(SSO 에서는 공개 식별자라서)
        assertThat(logged).contains("\"clientId\":\"***\"");
    }

    @Test
    @DisplayName("폼(x-www-form-urlencoded) 파라미터가 컨트롤러에 도착한다 — 파라미터 파싱이 본문을 삼키는 자리")
    void formParametersReachControllerIntact() throws Exception {
        String received = mockMvc().perform(post("/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("userId", "alice")
                        .param("password", "p@ssw0rd!"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(received).isEqualTo("alice/p@ssw0rd!");
    }

    @Test
    @DisplayName("멀티파트 업로드가 깨지지 않는다")
    void multipartRequestIsNotBroken() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", MediaType.TEXT_PLAIN_VALUE, "hello-bytes".getBytes(StandardCharsets.UTF_8));

        String received = mockMvc().perform(multipart("/upload").file(file))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(received).isEqualTo("a.txt:11");
    }

    @Test
    @DisplayName("본문 없는 요청·JSON 아닌 본문에서도 깨지지 않는다")
    void emptyAndNonJsonBodiesSurvive() throws Exception {
        MockMvc mvc = mockMvc();

        String empty = mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty).isEmpty();

        String plain = mvc.perform(post("/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("그냥 텍스트 <not json>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(plain).isEqualTo("그냥 텍스트 <not json>");
    }

    @Test
    @DisplayName("캐시 상한(10KB)을 넘는 거대 본문도 컨트롤러에 온전히 도착한다")
    void oversizedBodyReachesControllerIntact() throws Exception {
        // CONTENT_CACHE_LIMIT = 10KB. 캐시는 잘려도 컨트롤러가 읽는 스트림은 온전해야 한다.
        String body = "{\"password\":\"leak-me\",\"pad\":\"" + "x".repeat(64 * 1024) + "\"}";

        String received = mockMvc().perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(received).isEqualTo(body);
        assertThat(received).hasSize(body.length());
        assertThat(loggedText()).doesNotContain("leak-me");
    }

    // ────────────────────────────────────────────────────────────────────
    // 사고 2 — 응답 본문이 클라이언트에 나가지 못한다
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답 본문이 클라이언트에 원문 그대로 나간다 — copyBodyToResponse 누락이면 빈 응답이 된다")
    void responseBodyReachesClientIntact() throws Exception {
        String response = mockMvc().perform(get("/token"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 실제 토큰은 클라이언트로 그대로 나가야 한다(마스킹은 로그 문자열 사본에만 건다)
        assertThat(response).isEqualTo("{\"code\":\"COMMON_200\",\"data\":{\"accessToken\":\"" + JWT + "\"}}");

        String logged = loggedText();
        assertThat(logged).doesNotContain(JWT);
        assertThat(logged).contains("\"accessToken\":\"***\"");
        // ApiResponse 봉투의 code 는 가려지면 안 된다 — 로그가 통째로 못 쓰게 된다
        assertThat(logged).contains("\"code\":\"COMMON_200\"");
    }

    @Test
    @DisplayName("로깅이 Error 로 죽어도 응답 본문은 온전히 나가고 요청은 성공한다")
    void loggingFailureDoesNotBreakTheResponse() throws Exception {
        RequestResponseLoggingFilter exploding = new RequestResponseLoggingFilter() {
            @Override
            protected void logRequestResponse(ContentCachingRequestWrapper request,
                                              ContentCachingResponseWrapper response,
                                              String traceId,
                                              long executionTime) {
                // Exception 이 아니라 Error 다. 예전 구조에서는 이게 finally 를 뚫고 나가
                // copyBodyToResponse() 를 건너뛰어 클라이언트가 빈 본문을 받았다.
                throw new StackOverflowError("boom");
            }
        };

        String body = "{\"password\":\"p@ssw0rd!\"}";
        String received = mockMvc(exploding).perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(received).isEqualTo(body);
    }

    @Test
    @DisplayName("제외 경로는 로깅을 건너뛰되 응답은 그대로 나간다")
    void excludedPathStillServesResponse() throws Exception {
        String received = mockMvc().perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(received).isEqualTo("UP");
        assertThat(appender.list).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────
    // 채운 누락 경로
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("로그에 남지 않아야 하는 것")
    class MaskedInLog {

        @Test
        @DisplayName("쿼리스트링의 민감값이 가려지고 비민감 파라미터는 남는다")
        void queryStringIsMasked() throws Exception {
            String verifier = "a".repeat(43); // PKCE code_verifier 길이

            // .param() 은 MockHttpServletRequest 의 queryString 을 채우지 않는다.
            // 필터가 읽는 건 getQueryString() 이므로 원시 URI 로 보내야 이 경로가 실제로 태워진다.
            mockMvc().perform(get("/echo-query?page=1&access_token=" + JWT + "&code_verifier=" + verifier))
                    .andExpect(status().isOk());

            String logged = loggedText();
            assertThat(logged).doesNotContain(JWT).doesNotContain(verifier);
            assertThat(logged).contains("access_token=***");
            assertThat(logged).contains("code_verifier=***");
            // 진단에 필요한 파라미터는 살아 있어야 한다
            assertThat(logged).contains("page=1");
        }

        @Test
        @DisplayName("OAuth 인가코드·PKCE verifier 는 가려지고 봉투 code 는 살아남는다")
        void authorizationCodeIsMaskedButEnvelopeCodeSurvives() throws Exception {
            // /api/v1/auth/token 의 실제 본문 모양 — TokenExchangeDto.CodeRequest
            String authCode = "SplxlOBeZQQYbYS6WxSbIAabcdefghijklmnopqrstuv"; // 44자 base64url
            String body = "{\"code\":\"" + authCode + "\",\"codeVerifier\":\"" + "b".repeat(43)
                    + "\",\"redirectUri\":\"https://desk.porest.dev/callback\"}";

            String received = mockMvc().perform(post("/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(received).isEqualTo(body);

            String logged = loggedText();
            assertThat(logged).doesNotContain(authCode).doesNotContain("b".repeat(43));
            assertThat(logged).contains("\"code\":\"***\"").contains("\"codeVerifier\":\"***\"");
            // 리다이렉트 URI 는 진단에 필요하다
            assertThat(logged).contains("https://desk.porest.dev/callback");
        }

        @Test
        @DisplayName("본문이 잘려 DEBUG 전문이 다시 찍혀도 토큰은 마스킹된 채로 남는다")
        void debugFullBodyIsAlsoMasked() throws Exception {
            // MAX_BODY_LENGTH(500)를 넘겨 DEBUG "Full Request Body" 경로를 태운다.
            // 예전 SSO 사본은 이 자리에서 원본을 다시 읽어 완전한 JWT 를 남겼다.
            String body = "{\"pad\":\"" + "y".repeat(600) + "\",\"refresh_token\":\"" + JWT + "\"}";

            mockMvc().perform(post("/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            List<ILoggingEvent> debugEvents = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .toList();

            assertThat(debugEvents).isNotEmpty();
            assertThat(loggedText()).doesNotContain(JWT);
            assertThat(loggedText()).contains("Full Request Body");
        }

        @Test
        @DisplayName("목록에 없는 이름으로 실려도 JWT 모양이면 가려진다 — 리네임으로 뚫리던 자리")
        void jwtIsMaskedRegardlessOfKeyName() throws Exception {
            // core 마스커의 이름 무관 규칙. 목록에 "ssoTicketBlob" 같은 이름은 없다.
            String body = "{\"ssoTicketBlob\":\"" + JWT + "\"}";

            mockMvc().perform(post("/echo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            assertThat(loggedText()).doesNotContain(JWT).contains("***");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 사고 4 — 스트리밍(비동기) 응답이 빈 캐시로 커밋되어 0바이트가 나간다
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스트리밍 경로(/api/v1/export)는 필터가 정확 일치로 비껴간다 — 본문이 온전히 나간다")
    void streamingPathBypassesFilterAndBodyArrives() throws Exception {
        MvcResult started = mockMvc().perform(get("/api/v1/export"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult done = mockMvc().perform(asyncDispatch(started)).andReturn();

        assertThat(done.getResponse().getContentAsString()).isEqualTo("ZIPBYTES");
        // 필터를 비껴갔으므로 이 요청의 요청·응답 로그 줄도 없어야 한다
        assertThat(loggedText()).doesNotContain("/api/v1/export");
    }

    @Test
    @DisplayName("하위 JSON 경로(/api/v1/export/counts 꼴)는 계속 로깅된다 — 정확 일치라 startsWith 로 쓸려 나가지 않는다")
    void exportSubPathsStayLogged() throws Exception {
        RequestResponseLoggingFilter filter = new RequestResponseLoggingFilter();
        MockHttpServletRequest counts = new MockHttpServletRequest("POST", "/api/v1/export/counts");
        counts.setRequestURI("/api/v1/export/counts");
        MockHttpServletRequest export = new MockHttpServletRequest("POST", "/api/v1/export");
        export.setRequestURI("/api/v1/export");

        assertThat(filter.shouldNotFilter(export)).isTrue();
        assertThat(filter.shouldNotFilter(counts)).isFalse();
    }

    @Test
    @DisplayName("제외를 빠뜨린 비동기 응답도 빈 캐시를 커밋하지 않고 경고를 남긴다")
    void asyncStartedSkipsEmptyCopyAndWarns() throws Exception {
        RequestResponseLoggingFilter filter = new RequestResponseLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/future-stream");
        request.setRequestURI("/api/v1/future-stream");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            ((HttpServletRequest) req).startAsync();
        });

        // 빈 캐시 복사가 실행됐다면 Content-Length: 0 이 박힌다 — 스킵됐어야 한다
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(loggedText()).contains("excludedExactPaths()");
    }


    // ────────────────────────────────────────────────────────────────────

    @RestController
    static class EchoController {

        /** 받은 본문을 그대로 돌려준다 — 컨트롤러가 무엇을 받았는지 클라이언트에서 확인하려고. */
        @PostMapping(value = "/echo", produces = "text/plain;charset=UTF-8")
        String echo(@RequestBody(required = false) String body) {
            return body == null ? "" : body;
        }

        @PostMapping(value = "/form", produces = "text/plain;charset=UTF-8")
        String form(@RequestParam String userId, @RequestParam String password) {
            return userId + "/" + password;
        }

        @PostMapping(value = "/upload", produces = "text/plain;charset=UTF-8")
        String upload(@RequestParam("file") MultipartFile file) {
            return file.getOriginalFilename() + ":" + file.getSize();
        }

        @GetMapping("/echo-query")
        String echoQuery() {
            return "ok";
        }

        @GetMapping(value = "/token", produces = "application/json;charset=UTF-8")
        String token() {
            return "{\"code\":\"COMMON_200\",\"data\":{\"accessToken\":\"" + JWT + "\"}}";
        }

        /** StreamingResponseBody — 데이터 내보내기와 같은 비동기 스트리밍 응답 모형. */
        @GetMapping("/api/v1/export")
        org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportStream() {
            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body =
                    out -> out.write("ZIPBYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .body(body);
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }
}
