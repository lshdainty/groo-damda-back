package io.groo.damda.security.session.repository;

import io.groo.core.type.YNType;
import io.groo.damda.security.session.domain.UserSsoSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserSsoSessionRepository extends JpaRepository<UserSsoSession, Long> {

    /**
     * 재발급용 조회 — 행 잠금.
     *
     * <p>같은 세션에 동시 요청이 들어오면 각자 SSO 재발급을 시도하고, rotation 때문에 하나만
     * 성공한다. 나머지는 이미 폐기된 토큰으로 거부당해 멀쩡한 사용자가 로그아웃된다.
     * 그 줄은 {@code SsoSessionService} 가 프로세스 안 잠금으로 세우고, 이 잠금은 판정·반영의
     * <b>짧은 트랜잭션 동안만</b> 쥔다 — SSO 호출(읽기 타임아웃 10초) 동안 쥐면 SSO 가 느릴 때
     * 커넥션과 잠금이 함께 묶인다(보안 점검 DAM-5 잔여).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSsoSession s where s.sessionId = :sessionId and s.isDeleted = :isDeleted")
    Optional<UserSsoSession> findForRefresh(@Param("sessionId") String sessionId,
                                            @Param("isDeleted") YNType isDeleted);

    /** 로그아웃용 — 잠글 필요 없다. */
    Optional<UserSsoSession> findBySessionIdAndIsDeleted(String sessionId, YNType isDeleted);

    /** 사용자의 살아 있는 세션 전체 (기기 목록·전체 로그아웃용). */
    List<UserSsoSession> findAllByUserRowIdAndIsDeleted(Long userRowId, YNType isDeleted);
}
