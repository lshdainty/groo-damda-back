package io.groo.damda.memo.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.ForbiddenException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.memo.domain.MemoTag;
import io.groo.damda.memo.repository.MemoRepository;
import io.groo.damda.memo.repository.MemoTagRepository;
import io.groo.damda.memo.service.dto.MemoTagServiceDto;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 메모 태그 마스터 서비스 — {@code TodoTagServiceImpl} 을 그대로 미러링한다.
 *
 * <p>다른 점은 <b>매핑 테이블이 없다</b>는 것 하나다(사용자 결정 2026-09-08). 메모는 태그가
 * 하나라 {@code memo.memo_tag_row_id} FK 로 잇고, 그래서 사용 수도 매핑이 아니라 FK 로 센다.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class MemoTagServiceImpl implements MemoTagService {
    private final MemoTagRepository memoTagRepository;
    private final MemoRepository memoRepository;
    private final UserRepository userRepository;

    /**
     * 확보 시도 하나마다 <b>새 트랜잭션</b>을 여는 템플릿 — {@link #findOrCreateByName} 전용.
     *
     * <p>{@code @RequiredArgsConstructor} 를 버리고 생성자를 손으로 쓴 이유가 이것 하나다
     * (할 일 태그·지출 카테고리가 같은 자리에서 같은 이유로 먼저 세워 둔 모양이다).
     */
    private final TransactionTemplate newTransaction;

    public MemoTagServiceImpl(MemoTagRepository memoTagRepository,
                              MemoRepository memoRepository,
                              UserRepository userRepository,
                              PlatformTransactionManager transactionManager) {
        this.memoTagRepository = memoTagRepository;
        this.memoRepository = memoRepository;
        this.userRepository = userRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public MemoTagServiceDto.TagInfo createTag(MemoTagServiceDto.CreateCommand command) {
        log.debug("메모 태그 등록 시작: userRowId={}, tagName={}", command.userRowId(), command.tagName());

        // 저장 전에 이름을 한 번 다듬는다 — 검사와 저장이 같은 값을 보게 만드는 자리다.
        String tagName = NameNormalizer.require(command.tagName(), FieldLimits.NAME_MAX);

        User user = userRepository.findById(command.userRowId())
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));

        // 활성(미삭제) 태그 중 동일 이름 중복 금지 (soft-delete 된 같은 이름은 재사용 허용)
        if (memoTagRepository.existsActiveByUserAndName(command.userRowId(), tagName, null)) {
            throw new InvalidValueException(DeskErrorCode.MEMO_TAG_DUPLICATE_NAME);
        }

        MemoTag tag = MemoTag.createTag(user, tagName, command.color());
        memoTagRepository.save(tag);
        flushOrRejectDuplicate();

        log.info("메모 태그 등록 완료: tagId={}", tag.getRowId());

        return MemoTagServiceDto.TagInfo.from(tag);
    }

    @Override
    public List<MemoTagServiceDto.TagInfo> getTags(Long userRowId) {
        log.debug("메모 태그 목록 조회: userRowId={}", userRowId);

        // 태그별 사용 메모 수 — FK GROUP BY 1회 집계(N+1 금지).
        // 이름으로 맞대 세면 태그를 개명하는 순간 0 이 된다(할 일이 겪은 QA #79).
        Map<Long, Long> usage = memoTagRepository.countMemosByTag(userRowId);
        return memoTagRepository.findAllByUser(userRowId).stream()
            .map(tag -> MemoTagServiceDto.TagInfo.from(
                tag, usage.getOrDefault(tag.getRowId(), 0L)))
            .toList();
    }

    @Override
    @Transactional
    public MemoTagServiceDto.TagInfo updateTag(Long tagId, Long userRowId, MemoTagServiceDto.UpdateCommand command) {
        log.debug("메모 태그 수정 시작: tagId={}", tagId);

        MemoTag tag = findTagOrThrow(tagId);
        validateTagOwnership(tag, userRowId);

        // ★ 옛 이름을 tag.updateTag(...) 앞에서 잡는다. 뒤에서 읽으면 이미 새 이름이라
        //   아래 renameTag 의 WHERE 가 새 이름이 되어 0 행을 고치고 조용히 통과한다.
        //   할 일 태그가 같은 자리에서 같은 함정을 밟았다(#314).
        String previousName = tag.getTagName();

        String tagName = NameNormalizer.require(command.tagName(), FieldLimits.NAME_MAX);
        // 이름 변경 시 자기 자신을 제외한 활성 태그와 중복 금지
        if (memoTagRepository.existsActiveByUserAndName(userRowId, tagName, tagId)) {
            throw new InvalidValueException(DeskErrorCode.MEMO_TAG_DUPLICATE_NAME);
        }
        tag.updateTag(tagName, command.color());
        flushOrRejectDuplicate();

        // 개명이면 memo.tag 도 따라 옮긴다 — 안 옮기면 그 메모를 다음에 저장할 때
        // 서버가 옛 이름의 태그를 다시 만든다(QA #88 과 같은 되살아남).
        if (!tagName.equals(previousName)) {
            long moved = memoRepository.renameTag(userRowId, tagId, previousName, tagName);
            log.info("메모 태그 개명에 따른 메모 태그 문자열 동기화: tagId={}, {} -> {}, movedRows={}",
                tagId, previousName, tagName, moved);
        }

        log.info("메모 태그 수정 완료: tagId={}", tagId);

        return MemoTagServiceDto.TagInfo.from(tag);
    }

    /**
     * 태그 삭제 — <b>태그 행만 지우면 다음 저장에서 되살아난다</b>(QA #88).
     *
     * <p>{@code memo.tag} 는 태그 이름의 복사본이고, 웹·앱은 태그 아이디가 아니라 그 문자열을
     * 보낸다. 그래서 태그만 soft-delete 하면 메모에는 지운 이름이 그대로 남고, 그 메모를
     * 다음에 저장하는 순간 다리({@code MemoServiceImpl.applyTag} → {@link #findOrCreateByName})가
     * <b>같은 이름의 태그를 색 없이 새로 만든다</b>. 저장할 때마다 하나씩 늘었다.
     *
     * <p>그래서 <b>문자열과 FK 를 함께 끊는다</b>. 한쪽만 끊으면 나머지 한쪽이 되살린다 —
     * 이름만 지우면 FK 가 삭제된 태그를 가리킨 채 남고, FK 만 끊으면 이름이 태그를 다시 만든다.
     */
    @Override
    @Transactional
    public void deleteTag(Long tagId, Long userRowId) {
        log.debug("메모 태그 삭제 시작: tagId={}", tagId);

        MemoTag tag = findTagOrThrow(tagId);
        validateTagOwnership(tag, userRowId);

        // 이 이름이 아래 clearTag 의 WHERE 한쪽이다 — 개명(updateTag)과 달리 삭제는 이름을
        // 건드리지 않으므로 순서에 함정은 없지만, 읽는 자리를 한 곳으로 모아 둔다.
        String tagName = tag.getTagName();
        tag.deleteTag();

        long cleared = memoRepository.clearTag(userRowId, tagId, tagName);

        log.info("메모 태그 삭제 완료: tagId={}, 태그를 비운 메모={}", tagId, cleared);
    }

    /**
     * {@inheritDoc}
     *
     * <h4>왜 새 트랜잭션인가</h4>
     * 부르는 쪽은 메모 저장 트랜잭션이다. 여기서 UNIQUE 위반이 나면 그 세션은 더 못 쓰고
     * (이어서 조회·flush 하면 {@code AssertionFailure ... null identifier}), 위반을 그대로
     * 올리면 <b>태그 하나 때문에 메모 저장 전체가 죽는다</b>. 그래서 시도 하나를 트랜잭션
     * 하나로 감싸고, 위반이 나면 새 트랜잭션으로 <b>한 번만</b> 다시 돈다(무한 루프 금지).
     * MariaDB 기본 격리수준(REPEATABLE READ)에서는 같은 트랜잭션의 재조회가 처음 뜬 스냅샷을
     * 그대로 보므로, 상대가 그 뒤에 커밋한 행은 새 트랜잭션이 아니면 보이지도 않는다(#310 실측).
     */
    @Override
    // 재시도가 성립하려면 이 메서드가 트랜잭션을 들고 있으면 안 된다. 클래스 기본값
    // (readOnly = true) 이 걸리는 것도 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MemoTagServiceDto.TagRef findOrCreateByName(Long userRowId, String rawTagName) {
        String tagName = NameNormalizer.require(rawTagName, FieldLimits.NAME_MAX);
        try {
            return newTransaction.execute(status -> resolveOrCreateTag(userRowId, tagName));
        } catch (DataIntegrityViolationException e) {
            // 재시도가 뜻을 갖는 건 UNIQUE 위반뿐이다 — 상대가 넣은 태그를 다시 찾아 쓰면 되기 때문이다.
            // NOT NULL·FK 는 몇 번을 다시 돌려도 같은 자리에서 같게 터진다(QA #81).
            if (!IntegrityViolations.isUnique(e)) throw e;
            log.info("메모 태그 확보 경쟁 감지 — 새 트랜잭션으로 재조회 후 재사용: userRowId={}, tagName={}",
                userRowId, tagName);
            return newTransaction.execute(status -> resolveOrCreateTag(userRowId, tagName));
        }
    }

    /**
     * 확보 시도 한 번 — {@link #newTransaction} 안에서만 부른다(조회를 밖에 두면 재시도가 옛 스냅샷을 물려받는다).
     *
     * <p>이름·색을 <b>여기서</b> 읽어 {@link MemoTagServiceDto.TagRef} 에 담는다. 이 트랜잭션 안이
     * 그 행이 보이는 유일한 자리다 — 부르는 쪽으로 나가면 아이디로도 다시 못 읽는다(QA #102).
     */
    private MemoTagServiceDto.TagRef resolveOrCreateTag(Long userRowId, String tagName) {
        return memoTagRepository.findActiveByUserAndName(userRowId, tagName)
            .map(MemoTagServiceDto.TagRef::from)
            .orElseGet(() -> {
                User user = userRepository.findById(userRowId)
                    .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.USER_NOT_FOUND));
                // 색은 비워 둔다 — 사용자가 고른 적이 없다. 화면은 색 없는 태그를 이미 감당한다.
                MemoTag tag = MemoTag.createTag(user, tagName, null);
                memoTagRepository.save(tag);
                memoTagRepository.flush();
                log.info("memo.tag 로부터 태그 생성: userRowId={}, tagName={}, tagId={}",
                    userRowId, tagName, tag.getRowId());
                return MemoTagServiceDto.TagRef.from(tag);
            });
    }

    /**
     * 조회 검사를 빠져나간 동시 저장 경쟁을 409 로 받는다 — 할 일 태그와 같은 이유·같은 모양이다.
     */
    private void flushOrRejectDuplicate() {
        try {
            memoTagRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new InvalidValueException(DeskErrorCode.MEMO_TAG_DUPLICATE_NAME, e);
        }
    }

    private void validateTagOwnership(MemoTag tag, Long userRowId) {
        if (!tag.getUser().getRowId().equals(userRowId)) {
            log.warn("메모 태그 소유권 검증 실패 - tagId={}, ownerRowId={}, requestUserRowId={}",
                tag.getRowId(), tag.getUser().getRowId(), userRowId);
            throw new ForbiddenException(DeskErrorCode.MEMO_ACCESS_DENIED);
        }
    }

    private MemoTag findTagOrThrow(Long tagId) {
        return memoTagRepository.findById(tagId)
            .orElseThrow(() -> {
                log.warn("메모 태그 조회 실패 - 존재하지 않는 태그: tagId={}", tagId);
                return new EntityNotFoundException(DeskErrorCode.MEMO_TAG_NOT_FOUND);
            });
    }
}
