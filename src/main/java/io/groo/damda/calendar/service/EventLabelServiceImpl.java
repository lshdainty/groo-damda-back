package io.groo.damda.calendar.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.calendar.domain.EventLabel;
import io.groo.damda.calendar.repository.EventLabelRepository;
import io.groo.damda.calendar.service.dto.EventLabelServiceDto;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventLabelServiceImpl implements EventLabelService {
    private final EventLabelRepository eventLabelRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public EventLabelServiceDto.LabelInfo createLabel(EventLabelServiceDto.CreateCommand command) {
        log.debug("일정 라벨 생성 시작: userRowId={}", command.userRowId());

        // 저장 전에 이름을 한 번 다듬는다 — 검사와 저장이 같은 값을 보게 만드는 자리다.
        String labelName = NameNormalizer.require(command.labelName(), FieldLimits.NAME_MAX);

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        // 활성(미삭제) 라벨 중 동일 이름 중복 금지 (soft-delete 된 같은 이름은 재사용 허용)
        if (eventLabelRepository.existsActiveByUserAndName(command.userRowId(), labelName, null)) {
            throw new InvalidValueException(DeskErrorCode.EVENT_LABEL_DUPLICATE_NAME);
        }

        List<EventLabel> existing = eventLabelRepository.findAllByUser(command.userRowId());
        int nextOrder = existing.size();

        EventLabel label = EventLabel.createLabel(user, labelName, command.color(), nextOrder);
        eventLabelRepository.save(label);
        flushOrRejectDuplicate();

        log.info("일정 라벨 생성 완료: labelId={}", label.getRowId());
        return EventLabelServiceDto.LabelInfo.from(label);
    }

    @Override
    public List<EventLabelServiceDto.LabelInfo> getLabels(Long userRowId) {
        log.debug("일정 라벨 목록 조회: userRowId={}", userRowId);
        // 라벨별 사용 일정 수 — GROUP BY 1회 집계(N+1 금지).
        Map<Long, Long> usage = eventLabelRepository.countEventsByLabel(userRowId);
        return eventLabelRepository.findAllByUser(userRowId).stream()
            .map(label -> EventLabelServiceDto.LabelInfo.from(
                label, usage.getOrDefault(label.getRowId(), 0L)))
            .toList();
    }

    @Override
    @Transactional
    public EventLabelServiceDto.LabelInfo updateLabel(Long labelId, Long userRowId, EventLabelServiceDto.UpdateCommand command) {
        log.debug("일정 라벨 수정 시작: labelId={}", labelId);

        EventLabel label = eventLabelRepository.findById(labelId)
            .orElseThrow(() -> {
                log.warn("일정 라벨 조회 실패: labelId={}", labelId);
                return new EntityNotFoundException(DeskErrorCode.EVENT_LABEL_NOT_FOUND);
            });
        validateLabelOwnership(label, userRowId);

        String labelName = NameNormalizer.require(command.labelName(), FieldLimits.NAME_MAX);
        // 이름 변경 시 자기 자신을 제외한 활성 라벨과 중복 금지
        if (eventLabelRepository.existsActiveByUserAndName(userRowId, labelName, labelId)) {
            throw new InvalidValueException(DeskErrorCode.EVENT_LABEL_DUPLICATE_NAME);
        }

        label.updateLabel(labelName, command.color());
        flushOrRejectDuplicate();
        log.info("일정 라벨 수정 완료: labelId={}", labelId);
        return EventLabelServiceDto.LabelInfo.from(label);
    }

    @Override
    @Transactional
    public void deleteLabel(Long labelId, Long userRowId) {
        log.debug("일정 라벨 삭제 시작: labelId={}", labelId);

        EventLabel label = eventLabelRepository.findById(labelId)
            .orElseThrow(() -> {
                log.warn("일정 라벨 조회 실패: labelId={}", labelId);
                return new EntityNotFoundException(DeskErrorCode.EVENT_LABEL_NOT_FOUND);
            });
        validateLabelOwnership(label, userRowId);

        label.deleteLabel();
        log.info("일정 라벨 삭제 완료: labelId={}", labelId);
    }

    /**
     * 위 조회 검사를 <b>동시 저장 경쟁</b>이 빠져나간 경우까지 409 로 받는다.
     *
     * <p>조회와 저장 사이에는 락이 없다 — 같은 이름의 두 요청이 동시에 들어오면 둘 다
     * "없다" 를 보고 둘 다 INSERT 한다. 활성 이름 UNIQUE 가 DB 에 붙으면 진 쪽이
     * {@link DataIntegrityViolationException} 으로 터지는데, 그대로 두면 500 이다.
     *
     * <p><b>flush 를 명시하는 이유</b>: 안 하면 위반이 커밋 시점 — 서비스 메서드가 반환한
     * 뒤, 트랜잭션 인터셉터 안 — 에 터져 이 try/catch 가 닿지 않는다. 수정 경로는 더티
     * 체킹이라 특히 그렇다(UPDATE 자체가 커밋 때 나간다).
     *
     * <p>여기서 <b>재조회하지 않는</b> 것은 답이 409 이기 때문이다. 제약 위반 뒤의 하이버네이트
     * 세션은 더 쓸 수 없어 같은 트랜잭션에서 조회·수정을 이어갈 수 없다. 상대 행을 다시 찾아
     * 이어가야 하는 자리는 가져오기의 카테고리 확보 한 곳뿐이고, 그건 새 트랜잭션으로 돈다
     * ({@code ExpenseCategoryService.findOrCreateCategory}).
     */
    private void flushOrRejectDuplicate() {
        try {
            eventLabelRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.EVENT_LABEL_DUPLICATE_NAME, e);
        }
    }

    private void validateLabelOwnership(EventLabel label, Long userRowId) {
        if (!label.getUser().getRowId().equals(userRowId)) {
            log.warn("일정 라벨 소유권 검증 실패 - labelId={}, ownerRowId={}, requestUserRowId={}",
                label.getRowId(), label.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.EVENT_LABEL_ACCESS_DENIED);
        }
    }
}
