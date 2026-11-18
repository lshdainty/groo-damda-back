package io.groo.damda.apitoken.service;

import io.groo.core.exception.EntityNotFoundException;
import io.groo.core.exception.InvalidValueException;
import io.groo.core.type.YNType;
import io.groo.damda.apitoken.domain.UserApiToken;
import io.groo.damda.apitoken.repository.UserApiTokenRepository;
import io.groo.damda.apitoken.service.dto.ApiTokenServiceDto.Caller;
import io.groo.damda.apitoken.service.dto.ApiTokenServiceDto.IssuedToken;
import io.groo.damda.apitoken.service.dto.ApiTokenServiceDto.TokenInfo;
import io.groo.damda.apitoken.type.ApiTokenFormat;
import io.groo.damda.common.exception.DeskErrorCode;
import io.groo.damda.user.domain.User;
import io.groo.damda.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 프로그램용 API 토큰 — 원문이 DB 에 안 남는지, 남의 토큰을 건드릴 수 없는지,
 * 폐기·해지한 토큰이 주인을 찾아 주지 않는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class ApiTokenServiceImplTest {

    private static final Long USER = 7L;
    private static final Long OTHER_USER = 8L;

    @Mock private UserApiTokenRepository apiTokenRepository;
    @Mock private UserRepository userRepository;

    private ApiTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiTokenServiceImpl(apiTokenRepository, userRepository);
    }

    private User user(Long rowId) {
        User u = User.createUser(100L + rowId, "user" + rowId, "사용자" + rowId, "user" + rowId + "@hangroo.io");
        ReflectionTestUtils.setField(u, "rowId", rowId);
        return u;
    }

    private UserApiToken stored(Long rowId, Long userRowId, String rawToken) {
        UserApiToken token = UserApiToken.issue(userRowId, "보고서",
            ApiTokenFormat.hash(rawToken), ApiTokenFormat.displayPrefix(rawToken));
        ReflectionTestUtils.setField(token, "rowId", rowId);
        return token;
    }

    @Nested
    @DisplayName("발급")
    class Issue {

        @BeforeEach
        void saveReturnsArgument() {
            // lenient — 이름·개수 검사에서 막히는 테스트는 save 까지 가지 않는다
            org.mockito.Mockito.lenient().when(apiTokenRepository.save(any(UserApiToken.class)))
                .thenAnswer(inv -> {
                    UserApiToken saved = inv.getArgument(0);
                    ReflectionTestUtils.setField(saved, "rowId", 55L);
                    return saved;
                });
        }

        @Test
        @DisplayName("원문은 응답에만 있고 DB 에는 해시와 앞부분만 남는다")
        void storesOnlyHash() {
            IssuedToken issued = service.issue(USER, "  보고서 차트  ");

            ArgumentCaptor<UserApiToken> saved = ArgumentCaptor.forClass(UserApiToken.class);
            verify(apiTokenRepository).save(saved.capture());
            UserApiToken entity = saved.getValue();

            assertThat(issued.token()).matches("pdk_[A-Za-z0-9_-]{43}");
            assertThat(issued.rowId()).isEqualTo(55L);
            assertThat(issued.name()).isEqualTo("보고서 차트");
            assertThat(issued.tokenPrefix()).isEqualTo(issued.token().substring(0, 12));

            assertThat(entity.getUserRowId()).isEqualTo(USER);
            assertThat(entity.getTokenHash()).isEqualTo(ApiTokenFormat.hash(issued.token()));
            assertThat(entity.getTokenHash()).doesNotContain(issued.token());
            assertThat(entity.getTokenPrefix()).isEqualTo(issued.tokenPrefix());
            assertThat(entity.isActive()).isTrue();
        }

        @Test
        @DisplayName("발급할 때마다 다른 토큰이 나온다")
        void everyTokenIsDifferent() {
            assertThat(service.issue(USER, "a").token()).isNotEqualTo(service.issue(USER, "b").token());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("이름이 비면 거절한다")
        void blankNameRejected(String name) {
            assertThatThrownBy(() -> service.issue(USER, name))
                .isInstanceOfSatisfying(InvalidValueException.class, e ->
                    assertThat(e.getErrorCode()).isEqualTo(DeskErrorCode.API_TOKEN_NAME_INVALID));
            verify(apiTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("이름은 50자까지 — 51자는 거절한다")
        void nameLengthBoundary() {
            assertThat(service.issue(USER, "가".repeat(50)).name()).hasSize(50);

            assertThatThrownBy(() -> service.issue(USER, "가".repeat(51)))
                .isInstanceOfSatisfying(InvalidValueException.class, e ->
                    assertThat(e.getErrorCode()).isEqualTo(DeskErrorCode.API_TOKEN_NAME_INVALID));
        }

        @Test
        @DisplayName("살아 있는 토큰이 5개면 더 못 만든다 — 폐기한 것은 세지 않는다")
        void limit() {
            given(apiTokenRepository.countByUserRowIdAndIsDeleted(USER, YNType.N))
                .willReturn((long) ApiTokenServiceImpl.MAX_ACTIVE_TOKENS);

            assertThatThrownBy(() -> service.issue(USER, "여섯째"))
                .isInstanceOfSatisfying(InvalidValueException.class, e ->
                    assertThat(e.getErrorCode()).isEqualTo(DeskErrorCode.API_TOKEN_LIMIT_EXCEEDED));
            verify(apiTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("4개까지는 더 만들 수 있다")
        void underLimit() {
            given(apiTokenRepository.countByUserRowIdAndIsDeleted(USER, YNType.N))
                .willReturn((long) ApiTokenServiceImpl.MAX_ACTIVE_TOKENS - 1);

            assertThat(service.issue(USER, "다섯째").token()).startsWith(ApiTokenFormat.PREFIX);
        }
    }

    @Nested
    @DisplayName("목록·폐기")
    class ListAndRevoke {

        @Test
        @DisplayName("목록에는 살아 있는 내 토큰만 나온다 — 원문도 해시도 없다")
        void list() {
            UserApiToken token = stored(1L, USER, "pdk_aaaaaaaaaaaaaaaa");
            given(apiTokenRepository.findAllByUserRowIdAndIsDeletedOrderByRowIdDesc(USER, YNType.N))
                .willReturn(List.of(token));

            List<TokenInfo> tokens = service.getTokens(USER);

            assertThat(tokens).singleElement().satisfies(info -> {
                assertThat(info.rowId()).isEqualTo(1L);
                assertThat(info.name()).isEqualTo("보고서");
                assertThat(info.tokenPrefix()).isEqualTo("pdk_aaaaaaaa");
                assertThat(info.lastUsedAt()).isNull();
            });
        }

        @Test
        @DisplayName("폐기하면 죽는다")
        void revoke() {
            UserApiToken token = stored(1L, USER, "pdk_aaaaaaaaaaaaaaaa");
            given(apiTokenRepository.findByRowIdAndUserRowIdAndIsDeleted(1L, USER, YNType.N))
                .willReturn(Optional.of(token));

            service.revoke(USER, 1L);

            assertThat(token.isActive()).isFalse();
        }

        @Test
        @DisplayName("남의 토큰 번호로는 폐기할 수 없다 — 없는 것으로 답한다")
        void cannotRevokeOthers() {
            // 주인을 같이 보고 찾으므로 남의 번호는 빈 값이다
            given(apiTokenRepository.findByRowIdAndUserRowIdAndIsDeleted(1L, OTHER_USER, YNType.N))
                .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.revoke(OTHER_USER, 1L))
                .isInstanceOfSatisfying(EntityNotFoundException.class, e ->
                    assertThat(e.getErrorCode()).isEqualTo(DeskErrorCode.API_TOKEN_NOT_FOUND));
        }

        @Test
        @DisplayName("전부 폐기 — 살아 있던 개수를 돌려준다")
        void revokeAll() {
            UserApiToken a = stored(1L, USER, "pdk_aaaaaaaaaaaaaaaa");
            UserApiToken b = stored(2L, USER, "pdk_bbbbbbbbbbbbbbbb");
            given(apiTokenRepository.findAllByUserRowIdAndIsDeletedOrderByRowIdDesc(USER, YNType.N))
                .willReturn(List.of(a, b));

            assertThat(service.revokeAll(USER)).isEqualTo(2);
            assertThat(a.isActive()).isFalse();
            assertThat(b.isActive()).isFalse();
        }

        @Test
        @DisplayName("전부 폐기 — 토큰이 없어도 조용히 끝난다")
        void revokeAllWithNothing() {
            given(apiTokenRepository.findAllByUserRowIdAndIsDeletedOrderByRowIdDesc(USER, YNType.N))
                .willReturn(List.of());

            assertThat(service.revokeAll(USER)).isZero();
        }
    }

    @Nested
    @DisplayName("주인 확인")
    class Authenticate {

        private static final String RAW = "pdk_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";

        @Test
        @DisplayName("살아 있는 토큰이면 주인을 돌려주고 마지막 사용 시각을 남긴다")
        void valid() {
            UserApiToken token = stored(3L, USER, RAW);
            given(apiTokenRepository.findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N))
                .willReturn(Optional.of(token));
            given(userRepository.findById(USER)).willReturn(Optional.of(user(USER)));

            Optional<Caller> caller = service.authenticate(RAW);

            assertThat(caller).hasValueSatisfying(c -> {
                assertThat(c.tokenRowId()).isEqualTo(3L);
                assertThat(c.userRowId()).isEqualTo(USER);
                assertThat(c.userId()).isEqualTo("user7");
                assertThat(c.userName()).isEqualTo("사용자7");
                assertThat(c.userEmail()).isEqualTo("user7@hangroo.io");
            });
            assertThat(token.getLastUsedAt())
                .isBetween(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), LocalDateTime.now(ZoneOffset.UTC));
        }

        @Test
        @DisplayName("원문이 아니라 해시로 찾는다 — DB 에는 원문이 없다")
        void looksUpByHash() {
            given(apiTokenRepository.findByTokenHashAndIsDeleted(anyString(), any())).willReturn(Optional.empty());

            service.authenticate(RAW);

            verify(apiTokenRepository).findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N);
        }

        @Test
        @DisplayName("방금 쓴 토큰은 시각을 다시 쓰지 않는다")
        void doesNotRewriteLastUsedWithinInterval() {
            UserApiToken token = stored(3L, USER, RAW);
            LocalDateTime justNow = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10);
            ReflectionTestUtils.setField(token, "lastUsedAt", justNow);
            given(apiTokenRepository.findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N))
                .willReturn(Optional.of(token));
            given(userRepository.findById(USER)).willReturn(Optional.of(user(USER)));

            service.authenticate(RAW);

            assertThat(token.getLastUsedAt()).isEqualTo(justNow);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "eyJhbGciOiJIUzI1NiJ9.e30.sig", "PDK_upper"})
        @DisplayName("토큰 모양이 아니면 DB 를 보지도 않는다")
        void notATokenShape(String raw) {
            assertThat(service.authenticate(raw)).isEmpty();
            verifyNoInteractions(apiTokenRepository, userRepository);
        }

        @Test
        @DisplayName("없는(또는 폐기된) 토큰이면 빈 값 — 폐기분은 조회 조건에서 빠진다")
        void unknownOrRevoked() {
            given(apiTokenRepository.findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N))
                .willReturn(Optional.empty());

            assertThat(service.authenticate(RAW)).isEmpty();
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("주인이 해지했으면 토큰이 살아 있어도 거절한다")
        void ownerWithdrawn() {
            UserApiToken token = stored(3L, USER, RAW);
            User withdrawn = user(USER);
            withdrawn.withdraw(null);
            given(apiTokenRepository.findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N))
                .willReturn(Optional.of(token));
            given(userRepository.findById(USER)).willReturn(Optional.of(withdrawn));

            assertThat(service.authenticate(RAW)).isEmpty();
            assertThat(token.getLastUsedAt()).isNull();
        }

        @Test
        @DisplayName("주인 행이 없으면 거절한다")
        void ownerMissing() {
            UserApiToken token = stored(3L, USER, RAW);
            given(apiTokenRepository.findByTokenHashAndIsDeleted(ApiTokenFormat.hash(RAW), YNType.N))
                .willReturn(Optional.of(token));
            given(userRepository.findById(USER)).willReturn(Optional.empty());

            assertThat(service.authenticate(RAW)).isEmpty();
        }
    }
}
