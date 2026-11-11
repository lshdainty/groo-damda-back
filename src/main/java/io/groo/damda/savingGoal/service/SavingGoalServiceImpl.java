package io.groo.damda.savingGoal.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.asset.domain.Asset;
import io.groo.damda.asset.repository.AssetRepository;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.savingGoal.domain.SavingGoal;
import io.groo.damda.savingGoal.repository.SavingGoalRepository;
import io.groo.damda.savingGoal.service.dto.SavingGoalServiceDto;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SavingGoalServiceImpl implements SavingGoalService {
    private final SavingGoalRepository savingGoalRepository;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;

    @Override
    @Transactional
    public SavingGoalServiceDto.GoalInfo createSavingGoal(SavingGoalServiceDto.CreateCommand command) {
        log.debug("저축 목표 생성 시작: userRowId={}, title={}", command.userRowId(), command.title());

        String title = NameNormalizer.require(command.title(), FieldLimits.WIDE_NAME_MAX);

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        // 활성(미삭제) 목표 중 같은 이름 금지 (지운 이름은 다시 쓸 수 있다).
        // 종전엔 서버 검사가 아예 없어 화면 목록 캐시가 비면 그대로 통과했다.
        if (savingGoalRepository.existsActiveByUserAndTitle(command.userRowId(), title, null)) {
            throw new InvalidValueException(DeskErrorCode.SAVING_GOAL_DUPLICATE_NAME);
        }

        Asset linkedAsset = resolveLinkedAsset(command.linkedAssetRowId(), command.userRowId());

        SavingGoal goal = SavingGoal.createSavingGoal(
            user,
            title,
            command.description(),
            command.targetAmount(),
            command.currency(),
            command.deadlineDate(),
            command.icon(),
            command.color(),
            linkedAsset,
            command.sortOrder()
        );

        savingGoalRepository.save(goal);
        flushOrRejectDuplicate();
        log.info("저축 목표 생성 완료: savingGoalId={}, userRowId={}", goal.getRowId(), command.userRowId());

        return SavingGoalServiceDto.GoalInfo.from(goal);
    }

    @Override
    public List<SavingGoalServiceDto.GoalInfo> getSavingGoals(Long userRowId) {
        log.debug("저축 목표 목록 조회: userRowId={}", userRowId);

        return savingGoalRepository.findByUser(userRowId).stream()
            .map(SavingGoalServiceDto.GoalInfo::from)
            .toList();
    }

    @Override
    public SavingGoalServiceDto.GoalInfo getSavingGoal(Long savingGoalId, Long userRowId) {
        log.debug("저축 목표 단건 조회: savingGoalId={}", savingGoalId);

        SavingGoal goal = findOrThrow(savingGoalId);
        validateOwnership(goal, userRowId);
        return SavingGoalServiceDto.GoalInfo.from(goal);
    }

    @Override
    @Transactional
    public SavingGoalServiceDto.GoalInfo updateSavingGoal(Long savingGoalId, Long userRowId,
                                                           SavingGoalServiceDto.UpdateCommand command) {
        log.debug("저축 목표 수정 시작: savingGoalId={}", savingGoalId);

        SavingGoal goal = findOrThrow(savingGoalId);
        validateOwnership(goal, userRowId);

        // 실린 칸만 바꾼다 — 안 온 칸은 지금 값을 그대로 넘긴다(QA #96).
        String title = NameNormalizer.require(command.title().orKeep(goal.getTitle()), FieldLimits.WIDE_NAME_MAX);
        // 자기 자신은 뺀다 — 안 빼면 이름을 그대로 두고 금액만 고치는 저장이 영영 막힌다.
        if (savingGoalRepository.existsActiveByUserAndTitle(userRowId, title, savingGoalId)) {
            throw new InvalidValueException(DeskErrorCode.SAVING_GOAL_DUPLICATE_NAME);
        }

        // 연결 자산은 실렸을 때만 조회한다 — 안 실린 아이디를 조회하면 그 자체로 404 가 난다.
        Asset linkedAsset = command.linkedAssetRowId()
            .map(rowId -> resolveLinkedAsset(rowId, userRowId))
            .orKeep(goal.getLinkedAsset());

        goal.updateSavingGoal(
            title,
            command.description().orKeep(goal.getDescription()),
            command.targetAmount().orKeep(goal.getTargetAmount()),
            command.deadlineDate().orKeep(goal.getDeadlineDate()),
            command.icon().orKeep(goal.getIcon()),
            command.color().orKeep(goal.getColor()),
            linkedAsset
        );

        flushOrRejectDuplicate();

        log.info("저축 목표 수정 완료: savingGoalId={}", savingGoalId);
        return SavingGoalServiceDto.GoalInfo.from(goal);
    }

    @Override
    @Transactional
    public SavingGoalServiceDto.GoalInfo contribute(Long savingGoalId, Long userRowId,
                                                     SavingGoalServiceDto.ContributeCommand command) {
        log.debug("저축 목표 적립 시작: savingGoalId={}, amount={}", savingGoalId, command.amount());

        SavingGoal goal = findOrThrow(savingGoalId);
        validateOwnership(goal, userRowId);

        goal.contribute(command.amount());

        log.info("저축 목표 적립 완료: savingGoalId={}, current={}, isAchieved={}",
            savingGoalId, goal.getCurrentAmount(), goal.getIsAchieved());

        return SavingGoalServiceDto.GoalInfo.from(goal);
    }

    @Override
    @Transactional
    public void deleteSavingGoal(Long savingGoalId, Long userRowId) {
        log.debug("저축 목표 삭제 시작: savingGoalId={}", savingGoalId);

        SavingGoal goal = findOrThrow(savingGoalId);
        validateOwnership(goal, userRowId);
        goal.deleteSavingGoal();

        log.info("저축 목표 삭제 완료: savingGoalId={}", savingGoalId);
    }

    @Override
    @Transactional
    public void reorderSavingGoals(Long userRowId, List<SavingGoalServiceDto.ReorderItem> items) {
        log.debug("저축 목표 정렬 변경: userRowId={}, count={}", userRowId, items.size());

        for (SavingGoalServiceDto.ReorderItem item : items) {
            SavingGoal goal = findOrThrow(item.id());
            validateOwnership(goal, userRowId);
            goal.updateSortOrder(item.sortOrder());
        }

        log.info("저축 목표 정렬 변경 완료: userRowId={}", userRowId);
    }

    private Asset resolveLinkedAsset(Long linkedAssetRowId, Long userRowId) {
        if (linkedAssetRowId == null) {
            return null;
        }
        Asset asset = assetRepository.findById(linkedAssetRowId)
            .orElseThrow(() -> {
                log.warn("연결 자산 조회 실패 - 존재하지 않는 자산: rowId={}", linkedAssetRowId);
                return new EntityNotFoundException(DeskErrorCode.ASSET_NOT_FOUND);
            });
        if (!asset.getUser().getRowId().equals(userRowId)) {
            log.warn("연결 자산 소유권 검증 실패 - assetId={}, ownerRowId={}, requestUserRowId={}",
                asset.getRowId(), asset.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.ASSET_ACCESS_DENIED);
        }
        return asset;
    }

    private SavingGoal findOrThrow(Long savingGoalId) {
        return savingGoalRepository.findById(savingGoalId)
            .orElseThrow(() -> {
                log.warn("저축 목표 조회 실패 - 존재하지 않는 목표: savingGoalId={}", savingGoalId);
                return new EntityNotFoundException(DeskErrorCode.SAVING_GOAL_NOT_FOUND);
            });
    }

    /**
     * 조회 검사를 빠져나간 동시 저장 경쟁을 409 로 받는다.
     *
     * <p>flush 를 명시하지 않으면 위반이 커밋 시점에 터져 try/catch 가 닿지 않는다 —
     * 수정은 더티 체킹이라 UPDATE 자체가 커밋 때 나가므로 특히 그렇다.
     */
    private void flushOrRejectDuplicate() {
        try {
            savingGoalRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.SAVING_GOAL_DUPLICATE_NAME, e);
        }
    }

    private void validateOwnership(SavingGoal goal, Long userRowId) {
        if (!goal.getUser().getRowId().equals(userRowId)) {
            log.warn("저축 목표 소유권 검증 실패 - savingGoalId={}, ownerRowId={}, requestUserRowId={}",
                goal.getRowId(), goal.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.SAVING_GOAL_ACCESS_DENIED);
        }
    }
}
