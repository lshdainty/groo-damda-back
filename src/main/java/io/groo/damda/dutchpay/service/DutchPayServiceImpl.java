package io.groo.damda.dutchpay.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.dutchpay.domain.DutchPay;
import io.groo.damda.dutchpay.domain.DutchPayParticipant;
import io.groo.damda.dutchpay.repository.DutchPayRepository;
import io.groo.damda.dutchpay.service.dto.DutchPayServiceDto;
import io.groo.damda.expense.domain.Expense;
import io.groo.damda.expense.repository.ExpenseRepository;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DutchPayServiceImpl implements DutchPayService {

    /**
     * 이 정산의 활성 결제자 UNIQUE 를 가리키는 키 이름 조각.
     *
     * <p>{@code dutch_pay_participant} 에는 UNIQUE 가 <b>둘</b> 붙는다 —
     * {@code UK_dutch_pay_participant_active_name}(활성 참가자 이름, 마이그레이션
     * {@code V2026.09.04_02__desk-back-311}) 과 활성 결제자 것이다. 어느 쪽이 걸렸는지
     * 안 가리면 결제자가 부딪힌 요청이 <b>있지도 않은 이름 중복</b>을 이유로 거절당한다 —
     * QA #81 이 공통 핸들러에서 잡은 것과 같은 종류의 오역이다.
     *
     * <p>이름 전체가 아니라 조각으로 보는 이유는 {@link IntegrityViolations#constraintName} 에 적었다.
     * 두 키 이름 중 이 조각을 가진 것은 결제자 쪽뿐이다.
     */
    private static final String ACTIVE_PAYER_CONSTRAINT_MARK = "active_payer";

    private final DutchPayRepository dutchPayRepository;
    private final UserRepository userRepository;
    private final ExpenseRepository expenseRepository;

    @Override
    @Transactional
    public DutchPayServiceDto.DutchPayInfo createDutchPay(DutchPayServiceDto.CreateCommand command) {
        log.debug("더치페이 생성 시작: userRowId={}, title={}", command.userRowId(), command.title());

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        Expense sourceExpense = resolveSourceExpense(command.sourceExpenseRowId(), command.userRowId());

        DutchPay dutchPay = DutchPay.createDutchPay(
            user,
            sourceExpense,
            command.title(),
            command.description(),
            command.totalAmount(),
            command.currency() != null ? command.currency() : "KRW",
            command.splitMethod(),
            command.dutchPayDate()
        );

        addParticipants(dutchPay, command.participants());

        dutchPayRepository.save(dutchPay);
        flushOrRejectDuplicate();
        log.info("더치페이 생성 완료: dutchPayId={}", dutchPay.getRowId());

        return DutchPayServiceDto.DutchPayInfo.from(dutchPay);
    }

    @Override
    public List<DutchPayServiceDto.DutchPayInfo> getDutchPays(Long userRowId) {
        log.debug("더치페이 목록 조회: userRowId={}", userRowId);

        List<DutchPay> dutchPays = dutchPayRepository.findAllByUser(userRowId);

        return dutchPays.stream()
            .map(DutchPayServiceDto.DutchPayInfo::from)
            .toList();
    }

    @Override
    public DutchPayServiceDto.DutchPayInfo getDutchPay(Long dutchPayId, Long userRowId) {
        log.debug("더치페이 상세 조회: dutchPayId={}", dutchPayId);

        DutchPay dutchPay = findDutchPayOrThrow(dutchPayId);
        validateDutchPayOwnership(dutchPay, userRowId);

        return DutchPayServiceDto.DutchPayInfo.from(dutchPay);
    }

    @Override
    @Transactional
    public DutchPayServiceDto.DutchPayInfo updateDutchPay(Long dutchPayId, Long userRowId, DutchPayServiceDto.UpdateCommand command) {
        log.debug("더치페이 수정 시작: dutchPayId={}", dutchPayId);

        DutchPay dutchPay = findDutchPayOrThrow(dutchPayId);
        validateDutchPayOwnership(dutchPay, userRowId);

        dutchPay.updateDutchPay(
            command.title(),
            command.description(),
            command.totalAmount(),
            command.currency() != null ? command.currency() : "KRW",
            command.splitMethod(),
            command.dutchPayDate()
        );

        syncParticipants(dutchPay, command.participants());

        dutchPayRepository.save(dutchPay);
        log.info("더치페이 수정 완료: dutchPayId={}", dutchPayId);

        return DutchPayServiceDto.DutchPayInfo.from(dutchPay);
    }

    @Override
    @Transactional
    public void deleteDutchPay(Long dutchPayId, Long userRowId) {
        log.debug("더치페이 삭제 시작: dutchPayId={}", dutchPayId);

        DutchPay dutchPay = findDutchPayOrThrow(dutchPayId);
        validateDutchPayOwnership(dutchPay, userRowId);

        dutchPay.deleteDutchPay();
        dutchPayRepository.save(dutchPay);

        log.info("더치페이 삭제 완료: dutchPayId={}", dutchPayId);
    }

    @Override
    @Transactional
    public DutchPayServiceDto.DutchPayInfo markParticipantPaid(Long dutchPayId, Long userRowId, Long participantId) {
        log.debug("참가자 정산 처리: dutchPayId={}, participantId={}", dutchPayId, participantId);

        DutchPay dutchPay = findDutchPayOrThrow(dutchPayId);
        validateDutchPayOwnership(dutchPay, userRowId);

        DutchPayParticipant participant = dutchPay.getActiveParticipants().stream()
            .filter(p -> p.getRowId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.DUTCH_PAY_PARTICIPANT_NOT_FOUND));

        participant.markPaid();
        dutchPay.checkSettled();
        dutchPayRepository.save(dutchPay);

        log.info("참가자 정산 완료: participantId={}", participantId);

        return DutchPayServiceDto.DutchPayInfo.from(dutchPay);
    }

    @Override
    @Transactional
    public DutchPayServiceDto.DutchPayInfo settleAll(Long dutchPayId, Long userRowId) {
        log.debug("더치페이 전체 정산: dutchPayId={}", dutchPayId);

        DutchPay dutchPay = findDutchPayOrThrow(dutchPayId);
        validateDutchPayOwnership(dutchPay, userRowId);

        dutchPay.settleAll();
        dutchPayRepository.save(dutchPay);

        log.info("더치페이 전체 정산 완료: dutchPayId={}", dutchPayId);

        return DutchPayServiceDto.DutchPayInfo.from(dutchPay);
    }

    private Expense resolveSourceExpense(Long sourceExpenseRowId, Long userRowId) {
        if (sourceExpenseRowId == null) return null;
        Expense expense = expenseRepository.findById(sourceExpenseRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.EXPENSE_NOT_FOUND));
        if (!expense.getUser().getRowId().equals(userRowId)) {
            throw new ForbiddenException(DeskErrorCode.EXPENSE_ACCESS_DENIED);
        }
        // 자동으로 만들어진 거래(이월 · 매도 손익 · 이체 이자)는 나눠 낼 대상이 아니다.
        // 웹·앱은 버튼을 끈다 — 여기는 옛 앱·직접 호출을 막는 자리다.
        if (expense.isAutoGenerated()) {
            throw new InvalidValueException(DeskErrorCode.EXPENSE_AUTO_GENERATED_NO_DERIVE);
        }
        return expense;
    }

    /**
     * 참가자 목록을 rowId 로 맞춰 간다 — 있으면 제자리 수정, 없으면 신규, 안 온 건 삭제.
     *
     * <p>통째로 지우고 새로 만들면 <b>정산 완료 표시(is_paid/paid_at)가 전부 풀린다.</b>
     * 3명이 이미 입금해 체크해 뒀는데 금액 한 줄 고쳤다고 그게 날아가면 안 된다.
     */
    private void syncParticipants(DutchPay dutchPay, List<DutchPayServiceDto.ParticipantCommand> participants) {
        if (participants == null) {
            return;
        }
        List<String> names = validateNoDuplicateParticipants(participants);
        List<DutchPayParticipant> existing = List.copyOf(dutchPay.getActiveParticipants());
        Map<Long, DutchPayParticipant> byId = existing.stream()
            .filter(pt -> pt.getRowId() != null)
            .collect(Collectors.toMap(DutchPayParticipant::getRowId, pt -> pt, (a, b) -> a));

        // 쓰기 전에 요청 전체를 훑어 둔다 — 아래에서 이름을 임시값으로 비켜 두므로,
        // 검증이 중간에 터지면 되돌릴 것만 늘어난다(롤백은 되지만 읽기가 어려워진다).
        List<DutchPayParticipant> matched = new ArrayList<>(participants.size());
        List<User> users = new ArrayList<>(participants.size());
        for (DutchPayServiceDto.ParticipantCommand pc : participants) {
            // 0원은 결제자만 된다 — 결제자가 정해진 뒤에 본다(validatePayerOnlyZero).
            if (pc.amount() == null || pc.amount() < 0) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_INVALID_PARTICIPANT_AMOUNT);
            }
            User participantUser = null;
            if (pc.userRowId() != null) {
                participantUser = userRepository.findById(pc.userRowId())
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));
            }
            users.add(participantUser);
            matched.add(pc.rowId() != null ? byId.get(pc.rowId()) : null);
        }

        Set<DutchPayParticipant> kept =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        matched.stream().filter(java.util.Objects::nonNull).forEach(kept::add);

        // 결제자를 못 정하면 여기서 던진다 — 아래 삭제 루프보다 앞이라 아무것도 지워지지 않는다.
        int payerIndex = resolveUpdatePayerIndex(participants, matched, dutchPay.getPayer());
        validatePayerOnlyZero(participants, payerIndex);
        DutchPayParticipant nextPayer = matched.get(payerIndex);

        // ── ① 목록에서 빠진 참가자를 <b>먼저</b> 지운다. id 로 매칭되지 않은 것도 여기 걸린다.
        for (DutchPayParticipant pt : existing) {
            if (!kept.contains(pt)) {
                pt.deleteParticipant();
            }
        }
        // ── ② 이름이 바뀌는 기존 행을 임시값으로 비켜 두고, 자리를 넘겨줄 결제자를 내려놓는다.
        for (int i = 0; i < participants.size(); i++) {
            DutchPayParticipant found = matched.get(i);
            if (found != null && !found.getParticipantName().equalsIgnoreCase(names.get(i))) {
                found.parkNameForRename();
            }
        }
        // 강등이 ④ 에 남아 있으면 승격과 같은 플러시에 들어가 활성 결제자 UNIQUE 에 걸린다 —
        // UPDATE 는 한 문장씩 나가므로 어느 쪽을 먼저 내도 잠깐 결제자가 둘이 된다.
        // 여기서 내려놓으면 DB 가 보는 상태는 [1명] → [0명] → [1명] 뿐이다.
        // 이번에 지워진 행은 건드리지 않는다 — is_deleted='Y' 가 나가는 순간 활성 집합에서 빠지므로
        // 유일성에 걸리지 않고, 지워진 행에 "누가 냈는지" 는 남겨 두는 편이 기록으로 낫다.
        for (DutchPayParticipant pt : existing) {
            if (kept.contains(pt) && pt.isPayer() && pt != nextPayer) {
                pt.parkPayerForHandover();
            }
        }
        // ── ③ 여기서 한 번 내보낸다. 이 flush 가 없으면 아래 신규 INSERT 가 위 UPDATE 보다
        //     먼저 나가(하이버네이트의 플러시 순서) "빠진 사람 자리에 같은 이름을 새로 넣는"
        //     저장과 "두 사람 이름 맞바꾸기" 가 활성 이름 UNIQUE 에 걸린다.
        dutchPayRepository.flush();

        // ── ④ 최종 이름·금액·결제자 적용 + 신규 추가.
        for (int i = 0; i < participants.size(); i++) {
            DutchPayServiceDto.ParticipantCommand pc = participants.get(i);
            DutchPayParticipant found = matched.get(i);
            if (found != null) {
                found.updateParticipant(users.get(i), names.get(i), pc.amount(), i == payerIndex);
            } else {
                dutchPay.addParticipant(DutchPayParticipant.create(
                    dutchPay, users.get(i), names.get(i), pc.amount(), i == payerIndex));
            }
        }
        flushOrRejectDuplicate();
    }

    private void addParticipants(DutchPay dutchPay, List<DutchPayServiceDto.ParticipantCommand> participants) {
        // 참가자 0명은 결제자 0명이다 — 개수는 컨트롤러 @NotEmpty 가 먼저 끊지만, 서비스로
        // 바로 들어오는 호출까지 같은 규칙을 지나게 여기서도 본다.
        if (participants == null || participants.isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_PAYER_REQUIRED);
        }
        List<String> names = validateNoDuplicateParticipants(participants);
        int payerIndex = resolveCreatePayerIndex(participants);
        validatePayerOnlyZero(participants, payerIndex);
        for (int i = 0; i < participants.size(); i++) {
            DutchPayServiceDto.ParticipantCommand pc = participants.get(i);
            User participantUser = null;
            if (pc.userRowId() != null) {
                participantUser = userRepository.findById(pc.userRowId())
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));
            }
            DutchPayParticipant participant = DutchPayParticipant.create(
                dutchPay, participantUser, names.get(i), pc.amount(), i == payerIndex
            );
            dutchPay.addParticipant(participant);
        }
    }

    /**
     * 참가자 금액 — null·음수는 안 된다. <b>0원은 결제자만</b>, 그것도 다른 참가자가 있을 때만 된다.
     *
     * <p>거래에서 더치페이를 만들 때 "나도 포함" 을 끄면 "내가 전액 결제, 다른 사람 몫만 받아요"
     * 다 — 내가 결제자이고 내 몫은 0원이다. 종전엔 0원을 막아서 웹은 나를 빼고 보내(첫 친구가
     * 결제자로 저장됐다), 앱은 결제자 없이 보내 400 이었다(QA 30 3). 결제자가 아닌 사람의 0원은
     * 여전히 막는다 — 갚을 돈이 없는 사람은 정산에 들 이유가 없다.
     */
    private static void validatePayerOnlyZero(List<DutchPayServiceDto.ParticipantCommand> participants,
                                              int payerIndex) {
        for (int i = 0; i < participants.size(); i++) {
            Long amount = participants.get(i).amount();
            if (amount == null || amount < 0) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_INVALID_PARTICIPANT_AMOUNT);
            }
            if (amount == 0L && (i != payerIndex || participants.size() < 2)) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_INVALID_PARTICIPANT_AMOUNT);
            }
        }
    }

    /**
     * <b>생성</b>에서 결제자가 목록의 몇 번째인지 정한다. 한 정산에 결제자는 <b>반드시 한 명</b>이다.
     *
     * <p>둘 이상이면 거부한다. 그건 클라이언트 버그이고, 넘어가면 화면마다 다른 사람을
     * 결제자로 그리던 예전 증상으로 되돌아간다. <b>0명도 거부한다</b>(QA 2026-09-07 #80) —
     * 다만 그 전에 {@link #isPayerAware 이 필드를 아는 요청인지}를 본다.
     *
     * <p><b>왜 아는 요청만 거부하나.</b> 결제자를 0명으로 두면 {@code getDebtors()} 가 전원을
     * 갚을 사람으로 돌려줘 전체 정산이 <b>돈 낸 사람까지</b> 납부 처리하고, 화면은 화면대로 첫
     * 사람을 결제자처럼 그린다 — 서버와 화면이 갈린다. 그래서 새 클라이언트에는 "한 명 골라
     * 주세요" 로 답한다. 반대로 이 필드를 <b>모르는</b> 구버전 앱은 골라 보낼 방법이 없다.
     * 거기까지 거부하면 앱을 안 올린 사용자가 정산을 아예 못 만든다 — 스토어를 안 쓰는 앱이라
     * 자동 업데이트가 없고, 하한({@code min_build.json})도 0 이라 옛 빌드가 그대로 붙는다.
     * 그래서 <b>폴백은 구버전 호환용으로만 남긴다</b>: 목록 어디에도 키가 없을 때만 첫 사람이다.
     * 기존 데이터를 마이그레이션이 채우는 규칙(첫 참가자)과 같은 규칙이다.
     *
     * <p><b>수정은 이 폴백을 쓰면 안 된다</b> — 지킬 값이 이미 있기 때문이다.
     * {@link #resolveUpdatePayerIndex} 를 봐라.
     */
    private int resolveCreatePayerIndex(List<DutchPayServiceDto.ParticipantCommand> participants) {
        int marked = markedPayerIndex(participants);
        if (marked >= 0) return marked;
        requireInferablePayer(participants);
        return 0;
    }

    /**
     * <b>수정</b>에서 결제자가 목록의 몇 번째인지 정한다 — 표시가 없으면 <b>기존 결제자를 지킨다</b>.
     *
     * <p>여기서 생성 폴백("표시가 없으면 첫 사람")을 그대로 쓰면 금액 한 줄만 고치려고
     * {@code isPayer} 없이 PUT 한 요청이 <b>저장된 결제자를 목록 첫 사람으로 조용히 옮긴다.</b>
     * 참가자 순서는 클라이언트가 정하는 것이라 정렬만 바뀌어도 결제자가 따라 움직였고,
     * 거기 걸린 "받을 돈" 집계까지 같이 틀어졌다. 그 순서 추측을 없애려고 만든 것이
     * {@code is_payer} 컬럼인데 수정 경로가 다시 순서를 보고 있었던 것이다.
     *
     * <p>그래서 수정의 규칙은 <b>표시 없음 = 안 건드림</b> 이다. 생성 폴백은 구버전 앱을
     * 위한 것이라 그 자리에 그대로 둔다 — 만들 때는 지킬 값이 아직 없어 무언가는 골라야 한다.
     *
     * <p>단 <b>"안 건드림" 은 표시를 안 실은 요청에만 준다</b>(QA 2026-09-07 #80). 이 필드를
     * 아는 요청이 전원 {@code false} 로 왔다면 그건 "아무도 안 냈다" 는 주장이라, 저장된
     * 결제자를 슬쩍 지켜 200 을 주면 클라이언트가 보낸 값과 서버 값이 갈린다 — 화면과 데이터가
     * 어긋나던 그 증상으로 되돌아간다. 그래서 {@link #requireInferablePayer} 가
     * <b>지킬 값을 꺼내기 전에</b> 400 으로 끊는다.
     *
     * <p>표시가 없고 지킬 대상도 없으면 — 원래 결제자가 없었거나 이번 요청에서 그 사람이
     * 목록에서 빠졌으면 — 생성과 같은 규칙(첫 사람)으로 되돌아간다.
     *
     * <p>목록이 비었으면 결제자를 정할 방법이 아예 없다. 참가자 전원 삭제는 컨트롤러
     * {@code @Size(min = 1)} 가 먼저 막지만, 서비스로 바로 들어와도 같은 답이다.
     *
     * @param matched 요청 i 번째에 대응하는 기존 행(없으면 신규라 {@code null})
     * @param currentPayer 저장돼 있던 결제자. 없으면 {@code null}
     * @return 결제자 인덱스. 항상 유효한 값이다 — 정할 수 없으면 돌려주지 않고 던진다
     */
    private int resolveUpdatePayerIndex(List<DutchPayServiceDto.ParticipantCommand> participants,
                                        List<DutchPayParticipant> matched,
                                        DutchPayParticipant currentPayer) {
        int marked = markedPayerIndex(participants);
        if (marked >= 0) return marked;
        // 표시가 없는 요청만 여기 온다 — 지킬 값을 꺼내기 전에 "추측해도 되는 요청인지" 부터 본다.
        // 순서가 반대면 <b>전원 false</b> 로 온 요청이 저장된 결제자를 그대로 유지하며 200 이 되어,
        // 명시적으로 "아무도 안 냈다" 고 말한 클라이언트와 서버 값이 조용히 어긋난다.
        requireInferablePayer(participants);
        if (currentPayer != null) {
            for (int i = 0; i < matched.size(); i++) {
                if (matched.get(i) == currentPayer) return i;
            }
        }
        return 0;
    }

    /**
     * 결제자 표시가 없는 요청이다 — 서버가 <b>추측해도 되는</b> 요청인지 보고, 아니면 400 으로 끊는다.
     *
     * <p>추측이 허용되는 조건은 둘이다: 참가자가 <b>한 명 이상</b>일 것(0명이면 고를 사람 자체가
     * 없다)과, 이 요청이 {@code isPayer} 를 <b>모를 것</b>. 아는 요청이 아무도 표시하지 않았다면
     * 그건 "결제자가 없는 정산" 을 만들겠다는 뜻이라, 추측으로 메우지 않고 되돌려 보낸다.
     */
    private void requireInferablePayer(List<DutchPayServiceDto.ParticipantCommand> participants) {
        if (participants.isEmpty() || isPayerAware(participants)) {
            log.warn("더치페이 결제자 없음 - participantCount={}", participants.size());
            throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_PAYER_REQUIRED);
        }
    }

    /**
     * 보낸 쪽이 {@code isPayer} 를 아는가 — 한 명이라도 이 키를 실었으면 안다.
     *
     * <p>구버전 앱과 "결제자를 안 고른 새 클라이언트" 를 가르는 유일한 신호다. 값이 아니라
     * <b>키가 왔는지</b>를 본다: 아는 클라이언트는 고르지 않은 사람에게도 {@code false} 를
     * 싣기 때문에, 값만 보면 둘이 똑같이 "true 가 하나도 없음" 으로 보인다.
     *
     * <p>이 신호가 흐려지는 경우는 하나뿐이다 — 이 필드를 <b>일부 참가자에만</b> 싣는
     * 클라이언트. 지금은 없다(웹·앱 모두 한 커밋에서 전원에 붙였다, 2026-09-07 확인).
     * 그런 요청이 오면 "안다" 로 읽어 400 을 준다 — 안전한 쪽이다. 첫 사람을 추측해
     * 조용히 저장하는 것보다, 누가 냈는지 다시 물어보는 편이 낫다.
     */
    private boolean isPayerAware(List<DutchPayServiceDto.ParticipantCommand> participants) {
        return participants.stream().anyMatch(pc -> pc.isPayer() != null);
    }

    /** 요청에 결제자로 표시된 사람의 인덱스. 아무도 없으면 -1, 둘 이상이면 거부(클라이언트 버그). */
    private int markedPayerIndex(List<DutchPayServiceDto.ParticipantCommand> participants) {
        List<Integer> marked = java.util.stream.IntStream.range(0, participants.size())
            .filter(i -> Boolean.TRUE.equals(participants.get(i).isPayer()))
            .boxed()
            .toList();
        if (marked.size() > 1) {
            log.warn("더치페이 결제자 중복 - payerCount={}", marked.size());
            throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_INVALID_PAYER);
        }
        return marked.isEmpty() ? -1 : marked.get(0);
    }

    /**
     * 한 정산 안에서 같은 사람을 두 번 넣지 못하게 한다 — 정규화된 이름을 요청 순서대로 돌려준다.
     *
     * <p>수정은 요청에 없는 행을 지우는 구조라 커밋 후 활성 집합 == 요청 목록이 된다.
     * 그래서 요청 안의 중복만 막으면 결과가 맞는다. 스코프는 사용자가 아니라 <b>정산 건</b>이다.
     *
     * <h4>고친 구멍 둘</h4>
     * ① <b>검사 갈래가 갈려 있었다.</b> 등록 사용자는 userRowId 집합에, 손으로 친 이름만 있는
     * 참가자는 이름 집합에 넣었다 — {@code {userRowId:5,"철수"}} 와 {@code {userRowId:null,"철수"}}
     * 가 둘 다 통과해 한 정산에 같은 이름 활성 행이 둘 생겼다. 이제 <b>이름은 userRowId 유무와
     * 무관하게 전원</b>을 본다(등록 사용자 중복은 그대로 따로 막는다).
     *
     * <p>② <b>이름 비교가 자바 문자열이었다.</b> {@code HashSet<String>.add} 라 대소문자 구분·
     * trim 없음 — '철수' 와 '철수 '(끝공백)가 통과했는데 DB 는 콜레이션({@code utf8mb4_unicode_ci},
     * PAD SPACE)상 같은 값으로 본다. 정규화한 뒤 {@code toLowerCase(Locale.ROOT)} 로 비교해
     * 판정을 DB 와 같게 맞춘다. <b>저장 값은 원문 대소문자 그대로</b>다.
     */
    private List<String> validateNoDuplicateParticipants(List<DutchPayServiceDto.ParticipantCommand> participants) {
        java.util.Set<Long> seenUserIds = new java.util.HashSet<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        List<String> names = new ArrayList<>(participants.size());
        for (DutchPayServiceDto.ParticipantCommand pc : participants) {
            if (pc.userRowId() != null && !seenUserIds.add(pc.userRowId())) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_DUPLICATE_PARTICIPANT);
            }
            String name = NameNormalizer.require(pc.participantName(), FieldLimits.WIDE_NAME_MAX);
            if (!seenNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_DUPLICATE_PARTICIPANT);
            }
            names.add(name);
        }
        return names;
    }

    /** 위 검사를 빠져나간 경쟁·중복을 409 로 받는다 — 정산 건 안의 UNIQUE 가 마지막 판정자다. */
    private void flushOrRejectDuplicate() {
        try {
            dutchPayRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            // 제약 이름은 로그에만 남긴다 — 응답에 실으면 내부 이름이 새어 나간다(QA #75).
            log.warn("더치페이 유일성 위반 - {}", IntegrityViolations.describe(e));
            String constraint = IntegrityViolations.constraintName(e);
            if (constraint != null
                    && constraint.toLowerCase(Locale.ROOT).contains(ACTIVE_PAYER_CONSTRAINT_MARK)) {
                throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_PAYER_CONFLICT, e);
            }
            throw new InvalidValueException(DeskErrorCode.DUTCH_PAY_DUPLICATE_PARTICIPANT, e);
        }
    }

    private void validateDutchPayOwnership(DutchPay dutchPay, Long userRowId) {
        if (!dutchPay.getUser().getRowId().equals(userRowId)) {
            log.warn("더치페이 소유권 검증 실패 - dutchPayId={}, ownerRowId={}, requestUserRowId={}",
                dutchPay.getRowId(), dutchPay.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.DUTCHPAY_ACCESS_DENIED);
        }
    }

    private DutchPay findDutchPayOrThrow(Long dutchPayId) {
        return dutchPayRepository.findById(dutchPayId)
            .orElseThrow(() -> {
                log.warn("더치페이 조회 실패 - 존재하지 않는 더치페이: dutchPayId={}", dutchPayId);
                return new EntityNotFoundException(DeskErrorCode.DUTCH_PAY_NOT_FOUND);
            });
    }
}
