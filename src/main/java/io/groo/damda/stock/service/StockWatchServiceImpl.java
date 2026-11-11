package io.groo.damda.stock.service;

import io.groo.core.exception.DuplicateException;
import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.core.exception.IntegrityViolations;
import io.groo.damda.common.util.NameNormalizer;
import io.groo.damda.common.validation.FieldLimits;
import io.groo.damda.stock.domain.StockMaster;
import io.groo.damda.stock.domain.StockWatchGroup;
import io.groo.damda.stock.domain.StockWatchItem;
import io.groo.damda.stock.repository.StockWatchGroupRepository;
import io.groo.damda.stock.repository.StockWatchItemRepository;
import io.groo.damda.stock.service.dto.StockWatchServiceDto;
import io.groo.damda.stock.type.StockMarket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StockWatchServiceImpl implements StockWatchService {

    /** 사용자당 그룹 상한 — 화면 탭 UI 가 감당 가능한 수준의 방어선 */
    private static final int MAX_GROUPS_PER_USER = 20;
    /** 그룹당 종목 상한 — 시세 폴링 비용 방어선 */
    private static final int MAX_ITEMS_PER_GROUP = 100;
    /** 시장 미지정 심볼 해석 시 우선하는 시장 — 토스 시세 대상(KR/US) */

    private final StockWatchGroupRepository groupRepository;
    private final StockWatchItemRepository itemRepository;
    private final StockMasterResolver stockMasterResolver;

    @Override
    public List<StockWatchServiceDto.GroupInfo> getGroups(Long userRowId) {
        log.debug("관심목록 조회: userRowId={}", userRowId);
        List<StockWatchGroup> groups = groupRepository.findAllActiveByUser(userRowId);

        Map<Long, List<StockWatchServiceDto.ItemInfo>> itemsByGroup = new LinkedHashMap<>();
        for (StockWatchItemRepository.ItemWithStock row : itemRepository.findAllActiveByUserWithStock(userRowId)) {
            itemsByGroup.computeIfAbsent(row.item().getGroupRowId(), k -> new ArrayList<>())
                .add(StockWatchServiceDto.ItemInfo.of(row.item(), row.stock()));
        }

        return groups.stream()
            .map(g -> StockWatchServiceDto.GroupInfo.of(g, itemsByGroup.getOrDefault(g.getRowId(), List.of())))
            .toList();
    }

    @Override
    @Transactional
    public StockWatchServiceDto.GroupInfo createGroup(Long userRowId, String groupName) {
        log.debug("관심목록 그룹 생성: userRowId={}, groupName={}", userRowId, groupName);
        String name = normalizeGroupName(groupName);

        if (groupRepository.countActiveByUser(userRowId) >= MAX_GROUPS_PER_USER) {
            throw new InvalidValueException(DeskErrorCode.STOCK_WATCH_GROUP_LIMIT_EXCEEDED);
        }
        if (groupRepository.existsActiveByUserAndName(userRowId, name, null)) {
            throw new DuplicateException(DeskErrorCode.STOCK_WATCH_GROUP_NAME_DUPLICATE);
        }

        int nextOrder = groupRepository.findAllActiveByUser(userRowId).stream()
            .mapToInt(StockWatchGroup::getSortOrder)
            .max()
            .orElse(-1) + 1;
        StockWatchGroup group = groupRepository.save(StockWatchGroup.create(userRowId, name, nextOrder));
        flushOrRejectDuplicate();
        log.info("관심목록 그룹 생성 완료: userRowId={}, groupRowId={}", userRowId, group.getRowId());
        return StockWatchServiceDto.GroupInfo.of(group, List.of());
    }

    @Override
    @Transactional
    public StockWatchServiceDto.GroupInfo renameGroup(Long userRowId, Long groupRowId, String groupName) {
        log.debug("관심목록 그룹 이름 변경: userRowId={}, groupRowId={}", userRowId, groupRowId);
        String name = normalizeGroupName(groupName);
        StockWatchGroup group = findOwnedGroup(userRowId, groupRowId);

        // 자기 자신 제외는 DB 로 내린다. 종전엔 자바 equals(대소문자 구분)로 먼저 걸러
        // DB 검사(콜레이션 _ci, 대소문자 무시)를 건너뛰었는데, 그래서 'tech' → 'TECH' 개명이
        // 자기 자신에 걸려 409 로 막혔다 — 사용자가 자기 그룹 이름의 대소문자를 못 바꿨다.
        if (groupRepository.existsActiveByUserAndName(userRowId, name, groupRowId)) {
            throw new DuplicateException(DeskErrorCode.STOCK_WATCH_GROUP_NAME_DUPLICATE);
        }
        group.rename(name);
        flushOrRejectDuplicate();

        List<StockWatchServiceDto.ItemInfo> items = itemRepository.findAllActiveByUserWithStock(userRowId).stream()
            .filter(row -> groupRowId.equals(row.item().getGroupRowId()))
            .map(row -> StockWatchServiceDto.ItemInfo.of(row.item(), row.stock()))
            .toList();
        return StockWatchServiceDto.GroupInfo.of(group, items);
    }

    @Override
    @Transactional
    public void deleteGroup(Long userRowId, Long groupRowId) {
        log.debug("관심목록 그룹 삭제: userRowId={}, groupRowId={}", userRowId, groupRowId);
        StockWatchGroup group = findOwnedGroup(userRowId, groupRowId);

        // 그룹을 지우면 소속 종목도 함께 정리한다. 남겨두면 조인 조회에서 고아 행이 걸러질 뿐 데이터가 쌓인다.
        for (StockWatchItem item : itemRepository.findAllActiveByGroup(groupRowId)) {
            item.delete();
        }
        group.delete();
        log.info("관심목록 그룹 삭제 완료: userRowId={}, groupRowId={}", userRowId, groupRowId);
    }

    @Override
    @Transactional
    public StockWatchServiceDto.ItemInfo addItem(Long userRowId, Long groupRowId, String symbol, StockMarket marketCode) {
        log.debug("관심 종목 추가: userRowId={}, groupRowId={}, symbol={}, market={}", userRowId, groupRowId, symbol, marketCode);
        findOwnedGroup(userRowId, groupRowId);
        StockMaster stock = resolveStock(symbol, marketCode);

        StockWatchItem existing = itemRepository
            .findByGroupAndStockIncludingDeleted(groupRowId, stock.getRowId())
            .orElse(null);

        // 이미 담긴 종목은 그대로 돌려준다 — 별 토글 연타·화면 간 경합에 멱등.
        if (existing != null && !existing.isDeleted()) {
            return StockWatchServiceDto.ItemInfo.of(existing, stock);
        }

        if (itemRepository.countActiveByGroup(groupRowId) >= MAX_ITEMS_PER_GROUP) {
            throw new InvalidValueException(DeskErrorCode.STOCK_WATCH_ITEM_LIMIT_EXCEEDED);
        }

        int nextOrder = itemRepository.findAllActiveByGroup(groupRowId).stream()
            .mapToInt(StockWatchItem::getSortOrder)
            .max()
            .orElse(-1) + 1;

        StockWatchItem item;
        if (existing != null) {
            // (group, stock) 유니크 제약 때문에 새 행 대신 삭제 행을 되살린다.
            existing.restore(nextOrder);
            item = existing;
        } else {
            item = itemRepository.save(StockWatchItem.create(groupRowId, stock.getRowId(), nextOrder));
        }
        log.info("관심 종목 추가 완료: userRowId={}, groupRowId={}, symbol={}", userRowId, groupRowId, stock.getSymbol());
        return StockWatchServiceDto.ItemInfo.of(item, stock);
    }

    @Override
    @Transactional
    public void removeItem(Long userRowId, Long itemRowId) {
        log.debug("관심 종목 제거: userRowId={}, itemRowId={}", userRowId, itemRowId);
        StockWatchItem item = itemRepository.findActiveById(itemRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.STOCK_WATCH_ITEM_NOT_FOUND));

        // 소유권은 소속 그룹으로 검증한다.
        groupRepository.findActiveByIdAndUser(item.getGroupRowId(), userRowId)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.STOCK_WATCH_ITEM_NOT_FOUND));

        item.delete();
        log.info("관심 종목 제거 완료: userRowId={}, itemRowId={}", userRowId, itemRowId);
    }

    private StockWatchGroup findOwnedGroup(Long userRowId, Long groupRowId) {
        return groupRepository.findActiveByIdAndUser(groupRowId, userRowId)
            .orElseThrow(() -> {
                log.warn("관심목록 그룹 조회 실패 - 없거나 소유자가 아님: userRowId={}, groupRowId={}", userRowId, groupRowId);
                return new EntityNotFoundException(DeskErrorCode.STOCK_WATCH_GROUP_NOT_FOUND);
            });
    }

    /** 심볼 → 마스터 해석. 시장 미지정이면 정확 일치 중 KR/US 를 우선한다 (시장 간 6자리 코드 충돌 대비). */
    /**
     * 규칙은 {@link StockMasterResolver} 한 곳에 있다 — 관심목록과 자산 평가가 각자 다른
     * 규칙으로 풀면 같은 심볼이 서로 다른 종목을 가리킨다.
     */
    private StockMaster resolveStock(String symbol, StockMarket marketCode) {
        return stockMasterResolver.resolve(marketCode, symbol)
            .orElseThrow(() -> new EntityNotFoundException(DeskErrorCode.STOCK_NOT_FOUND));
    }

    /**
     * 이름 정규화는 이제 {@link NameNormalizer} 한 곳에 있다 — 여기 있던 구현을 그리로 올렸고
     * 나머지 일곱 도메인이 같은 규칙을 쓴다(다섯 중 정규화를 가진 건 여기뿐이었다).
     * 동작은 그대로다: trim · 빈 이름과 50자 초과는 {@code COMMON_400}.
     */
    private String normalizeGroupName(String groupName) {
        return NameNormalizer.require(groupName, FieldLimits.NAME_MAX);
    }

    /** 조회 검사를 빠져나간 동시 저장 경쟁을 409 로 받는다 — flush 가 없으면 위반이 커밋 시점에 터져 못 잡는다. */
    private void flushOrRejectDuplicate() {
        try {
            groupRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반만 이 도메인의 답으로 번역한다. NOT NULL·FK 를 여기서 "이름 중복" 이라고
            // 답하면 값을 빼먹은 요청이 엉뚱한 이유를 듣는다(QA #81) — 그런 위반은 그대로 올려
            // core GlobalExceptionHandler 가 종류대로 답하게 둔다.
            if (!IntegrityViolations.isUnique(e)) throw e;
            throw new DuplicateException(DeskErrorCode.STOCK_WATCH_GROUP_NAME_DUPLICATE, e);
        }
    }
}
