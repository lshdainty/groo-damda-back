package io.groo.damda.dutchpay.controller;

import io.groo.core.controller.ApiResponse;
import io.groo.damda.dutchpay.controller.dto.DutchPayApiDto;
import io.groo.damda.dutchpay.service.DutchPayService;
import io.groo.damda.dutchpay.service.dto.DutchPayServiceDto;
import io.groo.core.security.LoginUser;
import io.groo.damda.security.principal.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DutchPayApiController {
    private final DutchPayService dutchPayService;

    @PostMapping("/dutch-pay")
    public ApiResponse<DutchPayApiDto.Response> createDutchPay(
            @LoginUser UserPrincipal loginUser,
            @Valid @RequestBody DutchPayApiDto.CreateRequest request) {
        List<DutchPayServiceDto.ParticipantCommand> participants = request.participants() != null
            ? request.participants().stream()
                .map(p -> new DutchPayServiceDto.ParticipantCommand(
                    p.rowId(), p.userRowId(), p.participantName(), p.amount(), p.isPayer()))
                .toList()
            : List.of();

        DutchPayServiceDto.DutchPayInfo info = dutchPayService.createDutchPay(new DutchPayServiceDto.CreateCommand(
            loginUser.getRowId(),
            request.sourceExpenseRowId(),
            request.title(),
            request.description(),
            request.totalAmount(),
            request.currency(),
            request.splitMethod(),
            request.dutchPayDate(),
            participants
        ));
        return ApiResponse.success(DutchPayApiDto.Response.from(info));
    }

    @GetMapping("/dutch-pays")
    public ApiResponse<DutchPayApiDto.ListResponse> getDutchPays(
            @LoginUser UserPrincipal loginUser) {
        List<DutchPayServiceDto.DutchPayInfo> infos = dutchPayService.getDutchPays(loginUser.getRowId());
        return ApiResponse.success(DutchPayApiDto.ListResponse.from(infos));
    }

    @GetMapping("/dutch-pay/{id}")
    public ApiResponse<DutchPayApiDto.Response> getDutchPay(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        DutchPayServiceDto.DutchPayInfo info = dutchPayService.getDutchPay(id, loginUser.getRowId());
        return ApiResponse.success(DutchPayApiDto.Response.from(info));
    }

    @PutMapping("/dutch-pay/{id}")
    public ApiResponse<DutchPayApiDto.Response> updateDutchPay(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @Valid @RequestBody DutchPayApiDto.UpdateRequest request) {
        // participants 를 <b>안 보낸</b> 요청은 "참가자는 안 건드린다" 는 뜻이라 null 을 그대로 넘긴다.
        // 여기서 List.of() 로 바꾸면 서비스가 "빈 목록으로 맞춰라" 로 읽어 **활성 참가자를 전원
        // 삭제**한다 — 제목이나 금액 한 줄만 담아 PUT 한 요청이 누가 얼마를 냈는지를 통째로 지웠다.
        // 비우고 싶으면 빈 배열을 명시해 보내면 된다. 그 둘은 서로 다른 요청이다.
        List<DutchPayServiceDto.ParticipantCommand> participants = request.participants() != null
            ? request.participants().stream()
                .map(p -> new DutchPayServiceDto.ParticipantCommand(
                    p.rowId(), p.userRowId(), p.participantName(), p.amount(), p.isPayer()))
                .toList()
            : null;

        DutchPayServiceDto.DutchPayInfo info = dutchPayService.updateDutchPay(id, loginUser.getRowId(), new DutchPayServiceDto.UpdateCommand(
            request.title(),
            request.description(),
            request.totalAmount(),
            request.currency(),
            request.splitMethod(),
            request.dutchPayDate(),
            participants
        ));
        return ApiResponse.success(DutchPayApiDto.Response.from(info));
    }

    @DeleteMapping("/dutch-pay/{id}")
    public ApiResponse<Void> deleteDutchPay(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        dutchPayService.deleteDutchPay(id, loginUser.getRowId());
        return ApiResponse.success();
    }

    @PatchMapping("/dutch-pay/{id}/participant/{participantId}/paid")
    public ApiResponse<DutchPayApiDto.Response> markParticipantPaid(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id,
            @PathVariable Long participantId) {
        DutchPayServiceDto.DutchPayInfo info = dutchPayService.markParticipantPaid(id, loginUser.getRowId(), participantId);
        return ApiResponse.success(DutchPayApiDto.Response.from(info));
    }

    @PatchMapping("/dutch-pay/{id}/settle")
    public ApiResponse<DutchPayApiDto.Response> settleAll(
            @LoginUser UserPrincipal loginUser,
            @PathVariable Long id) {
        DutchPayServiceDto.DutchPayInfo info = dutchPayService.settleAll(id, loginUser.getRowId());
        return ApiResponse.success(DutchPayApiDto.Response.from(info));
    }
}
