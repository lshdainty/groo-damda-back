package io.groo.damda.common.exception;

import io.groo.core.exception.IntegrityViolations;
import io.groo.core.exception.IntegrityViolationKind;

import org.hibernate.PropertyValueException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무결성 위반의 <b>종류 판정</b> — QA #81 의 심장이다.
 *
 * <p>여기서 UNIQUE 와 NOT NULL 이 갈리지 않으면 핸들러도 열 곳의 서비스도 전부 같이 틀린다.
 * H2(테스트)와 MariaDB(운영)는 벤더 코드가 완전히 다르므로 <b>두 벌을 나란히</b> 세워 둔다 —
 * H2 만 맞춰 두면 운영에서 조용히 갈린다.
 *
 * <p>값의 출처: H2 는 이 레포 슬라이스에서 직접 재현해 찍은 값(2026-09-07),
 * MariaDB 는 {@code MySQLDialect}(=MariaDBDialect 의 부모) 바이트코드의 분기 표.
 */
class IntegrityViolationsTest {

    /** 하이버네이트가 실제로 만드는 모양 — 스프링 래퍼 → 하이버네이트 → JDBC 3단 사슬. */
    private static DataIntegrityViolationException wrapped(
            ConstraintViolationException.ConstraintKind kind, SQLException sql, String constraint) {
        ConstraintViolationException hibernate =
                new ConstraintViolationException("could not execute statement", sql, kind, constraint);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    private static SQLException h2(String sqlState) {
        // H2 는 SQLState 와 벤더 에러코드가 같은 숫자다.
        return new SQLIntegrityConstraintViolationException("h2", sqlState, Integer.parseInt(sqlState));
    }

    private static SQLException maria(int errorCode) {
        // MariaDB 는 무결성 위반 SQLState 가 전부 23000 이라 벤더 코드로만 갈린다.
        return new SQLIntegrityConstraintViolationException("maria", "23000", errorCode);
    }

    @Nested
    @DisplayName("하이버네이트가 종류를 매긴 경우 — 1차 판정")
    class HibernateKind {

        @Test
        @DisplayName("UNIQUE / NOT_NULL / FOREIGN_KEY 가 그대로 갈린다")
        void mapsEachKind() {
            assertThat(IntegrityViolations.classify(wrapped(
                    ConstraintViolationException.ConstraintKind.UNIQUE, h2("23505"), "UK_TODO_TAG")))
                    .isEqualTo(IntegrityViolationKind.UNIQUE);
            assertThat(IntegrityViolations.classify(wrapped(
                    ConstraintViolationException.ConstraintKind.NOT_NULL, h2("23502"), "EVENT_TYPE")))
                    .isEqualTo(IntegrityViolationKind.NOT_NULL);
            assertThat(IntegrityViolations.classify(wrapped(
                    ConstraintViolationException.ConstraintKind.FOREIGN_KEY, h2("23506"), "FK_X")))
                    .isEqualTo(IntegrityViolationKind.FOREIGN_KEY);
        }

        @Test
        @DisplayName("CHECK 는 OTHER 로 — 유일성이 아니므로 409 가 아니다")
        void checkIsOther() {
            assertThat(IntegrityViolations.classify(wrapped(
                    ConstraintViolationException.ConstraintKind.CHECK, h2("23513"), "CK_X")))
                    .isEqualTo(IntegrityViolationKind.OTHER);
        }

        @Test
        @DisplayName("방언이 OTHER 로 두면 벤더 코드가 이어받는다 (2차 판정)")
        void fallsBackToVendorCodeWhenDialectSaysOther() {
            assertThat(IntegrityViolations.classify(wrapped(
                    ConstraintViolationException.ConstraintKind.OTHER, maria(1062), null)))
                    .isEqualTo(IntegrityViolationKind.UNIQUE);
        }
    }

    @Nested
    @DisplayName("벤더 코드만 있는 경우 — 하이버네이트를 안 거친 경로")
    class VendorCodes {

        @Test
        @DisplayName("H2(테스트) SQLState: 23505 UNIQUE · 23502 NOT NULL · 23503/23506 FK")
        void h2SqlStates() {
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", h2("23505"))))
                    .isEqualTo(IntegrityViolationKind.UNIQUE);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", h2("23502"))))
                    .isEqualTo(IntegrityViolationKind.NOT_NULL);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", h2("23503"))))
                    .isEqualTo(IntegrityViolationKind.FOREIGN_KEY);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", h2("23506"))))
                    .isEqualTo(IntegrityViolationKind.FOREIGN_KEY);
        }

        @Test
        @DisplayName("MariaDB(운영) 에러코드: 1062 UNIQUE · 1048/1364 NOT NULL · 1451/1452 FK")
        void mariaErrorCodes() {
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", maria(1062))))
                    .isEqualTo(IntegrityViolationKind.UNIQUE);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", maria(1048))))
                    .isEqualTo(IntegrityViolationKind.NOT_NULL);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", maria(1364))))
                    .isEqualTo(IntegrityViolationKind.NOT_NULL);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", maria(1451))))
                    .isEqualTo(IntegrityViolationKind.FOREIGN_KEY);
            assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x", maria(1452))))
                    .isEqualTo(IntegrityViolationKind.FOREIGN_KEY);
        }
    }

    @Test
    @DisplayName("스프링이 이미 DuplicateKeyException 으로 좁혔으면 그대로 UNIQUE")
    void springDuplicateKey() {
        assertThat(IntegrityViolations.classify(new DuplicateKeyException("dup")))
                .isEqualTo(IntegrityViolationKind.UNIQUE);
    }

    @Test
    @DisplayName("SQL 이 나가기 전에 하이버네이트가 잡은 널도 NOT NULL 이다")
    void propertyValueExceptionIsNotNull() {
        // hibernate.check_nullability 를 켜면 SQLException 없이 이것만 온다.
        assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("x",
                new PropertyValueException("not-null property references a null value",
                        "CalendarEvent", "eventType"))))
                .isEqualTo(IntegrityViolationKind.NOT_NULL);
    }

    @Test
    @DisplayName("판정할 근거가 없으면 OTHER — UNIQUE 로 넘겨짚지 않는다")
    void unknownIsOther() {
        // 원인 없는 예외는 판정 불가다. 여기서 UNIQUE 를 돌려주면 열 곳의 서비스가 아무 위반이나
        // "이름이 중복돼요" 로 번역하던 옛 동작으로 되돌아간다.
        assertThat(IntegrityViolations.classify(new DataIntegrityViolationException("UK_event_label_user_active_name")))
                .isEqualTo(IntegrityViolationKind.OTHER);
        assertThat(IntegrityViolations.isUnique(new DataIntegrityViolationException("UK_something"))).isFalse();
    }

    @Test
    @DisplayName("자기 자신을 원인으로 갖는 예외에도 안 멈춘다")
    void survivesSelfReferencingCause() {
        SQLException self = new SQLException("boom", "23000", 1062) {
            @Override public Throwable getCause() { return this; }
        };
        assertThat(IntegrityViolations.classify(self)).isEqualTo(IntegrityViolationKind.UNIQUE);
    }

    @Test
    @DisplayName("describe 는 제약 이름·SQLState·벤더 코드를 담는다 — 로그 전용")
    void describeCarriesTheInternalNames() {
        String desc = IntegrityViolations.describe(wrapped(
                ConstraintViolationException.ConstraintKind.NOT_NULL, h2("23502"), "EVENT_TYPE"));

        assertThat(desc).contains("NOT_NULL").contains("EVENT_TYPE").contains("23502");
    }
}
