package io.groo.damda.common.exception;

import io.groo.core.controller.GlobalExceptionHandler;
import io.groo.core.util.MessageResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 끊긴 SSE 연결이 core 의 "Unexpected Exception"(ERROR + 500 본문)으로 새지 않는지 검증한다.
 *
 * <p>core GlobalExceptionHandler 를 <b>같이 등록한 채로</b> 돌린다. 이 핸들러만 놓고 부르면
 * {@code @Order} 를 빼먹어도 통과한다 — 실제로는 core 의 {@code Exception} 핸들러가 먼저 잡아
 * 이 클래스가 한 번도 안 불리는데도.
 */
class ClientDisconnectExceptionHandlerTest {

    @RestController
    static class DisconnectingController {
        @GetMapping("/stream")
        String stream() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException(
                    "Servlet container error notification for disconnected client",
                    new IOException("Broken pipe"));
        }
    }

    @Test
    @DisplayName("끊긴 연결은 core 의 500 핸들러로 가지 않고 아무것도 쓰지 않은 채 끝난다")
    void disconnectIsSwallowedBeforeCoreCatchAll() throws Exception {
        MessageResolver coreMessages = mock(MessageResolver.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DisconnectingController())
                .setControllerAdvice(new GlobalExceptionHandler(coreMessages), new ClientDisconnectExceptionHandler())
                .build();

        mvc.perform(get("/stream"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        // core 의 catch-all 은 메시지를 꺼내며 시작한다 — 한 번도 안 불렸다는 증거.
        verifyNoInteractions(coreMessages);
    }

    @Test
    @DisplayName("core 의 Exception 핸들러보다 앞 순서다")
    void ordersAheadOfCoreCatchAll() {
        assertThat(OrderUtils.getOrder(ClientDisconnectExceptionHandler.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
