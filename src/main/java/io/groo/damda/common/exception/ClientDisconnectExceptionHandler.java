package io.groo.damda.common.exception;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 클라이언트가 <b>먼저 연결을 끊은</b> 비동기 응답을 조용히 끝내는 핸들러.
 *
 * <p>알림 SSE({@code /api/v1/notifications/stream})는 앱이 백그라운드로 가거나 웹 탭을 닫을 때마다
 * 끊긴다. 그때 Spring 은 {@link AsyncRequestNotUsableException} 을 던지는데, groo-core 의
 * GlobalExceptionHandler 가 이걸 마지막 {@code Exception} 핸들러로 받아
 * <ol>
 *   <li>{@code ERROR "Unexpected Exception"} 을 스택 전체와 함께 찍고</li>
 *   <li>JSON 오류 응답을 쓰려다 {@code text/event-stream} 응답이라 실패해
 *       {@code HttpMessageNotWritableException} WARN 을 한 줄 더 찍었다.</li>
 * </ol>
 * 끊긴 연결 하나에 로그 두 건 — dev 하루에 각 111~213건이 쌓여 Grafana 의 오류 건수를
 * 부풀리고 진짜 오류를 묻었다(2026-11-09 로그 분석 대시보드로 발견).
 *
 * <p>응답 쪽은 할 일이 없다. 받을 사람이 이미 떠났고, Spring 자신의
 * {@code DefaultHandlerExceptionResolver} 도 이 예외는 아무것도 쓰지 않고 넘긴다. 그래서
 * 여기서는 DEBUG 한 줄만 남기고 끝낸다.
 *
 * <p><b>{@link HttpServletResponse} 인자는 쓰지 않지만 지우면 안 된다.</b> 반환이 {@code void} 인
 * 예외 핸들러는 응답 인자를 받아야 "응답을 직접 처리했다" 로 표시되고, 그렇지 않으면 Spring 이
 * 뷰 이름을 찾으러 간다. 응답 객체를 건드리면 끊긴 소켓에 쓰다가 같은 예외가 다시 난다 —
 * 받기만 하고 손대지 않는다.
 *
 * <p>{@code @Order} 는 {@link RequestRoutingExceptionHandler} 와 같은 이유다. advice 는 순서대로
 * 훑다가 처음 매칭에서 멈추므로 core 의 {@code Exception} 핸들러보다 앞서야 불린다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ClientDisconnectExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e, HttpServletResponse response) {
        log.debug("클라이언트가 비동기 응답 연결을 끊음: {}", e.getMessage());
    }
}
