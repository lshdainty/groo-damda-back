<p align="center">
  <img src="https://img.shields.io/badge/%EA%B7%B8%EB%A3%A8_%EB%8B%B4%EB%8B%A4-6DB33F?style=for-the-badge&logo=spring&logoColor=white" alt="그루 담다" />
</p>

<h1 align="center">그루 담다 Backend</h1>

<p align="center">
  <strong>개인 생산성/라이프 로그 관리를 위한 Desk 백엔드 서비스</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.0.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/JWT-000000?logo=jsonwebtokens&logoColor=white" alt="JWT" />
</p>

---

## 소개

**그루 담다 Backend**는 [그루](https://github.com/lshdainty/groo) 서비스의 Desk 백엔드입니다.

할 일, 캘린더, 메모, 가계부(지출·예산·반복거래), 자산, 카드 혜택, 구독, 더치페이, 저축 목표, 데이터 가져오기/내보내기, 토스증권 연동, 할 일 별자리 게이미피케이션 등 개인 생산성/가계 관리 기능을 REST API(`/api/v1`)로 제공합니다.

> `main` 브랜치는 보호되어 있으며, PR + CI(테스트) 통과 시에만 머지됩니다.

---

## 기술 스택

| Category | Technology |
|----------|------------|
| **Language** | ![Java](https://img.shields.io/badge/Java_25-007396?style=flat-square&logo=openjdk&logoColor=white) |
| **Framework** | ![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.0.4-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white) |
| **ORM** | ![JPA](https://img.shields.io/badge/JPA-59666C?style=flat-square&logo=hibernate&logoColor=white) ![QueryDSL](https://img.shields.io/badge/QueryDSL_7.1-0769AD?style=flat-square) |
| **Database** | ![MariaDB](https://img.shields.io/badge/MariaDB_3.5.1-003545?style=flat-square&logo=mariadb&logoColor=white) |
| **Cache** | ![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white) |
| **Authentication** | ![JWT](https://img.shields.io/badge/JJWT_0.12.6-000000?style=flat-square&logo=jsonwebtokens&logoColor=white) ![Nimbus](https://img.shields.io/badge/Nimbus_JOSE_10.4-4B6BFB?style=flat-square) |
| **Excel (Import/Export)** | ![Apache POI](https://img.shields.io/badge/Apache_POI_5.3.0-D22128?style=flat-square&logo=apache&logoColor=white) |
| **Monitoring** | ![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=flat-square&logo=prometheus&logoColor=white) ![Loki](https://img.shields.io/badge/Loki-F46800?style=flat-square&logo=grafana&logoColor=white) |
| **공통 라이브러리** | ![groo-core](https://img.shields.io/badge/groo--core_3.0.0-6DB33F?style=flat-square) |
| **API Documentation** | ![Swagger](https://img.shields.io/badge/SpringDoc_OpenAPI_3.0.0-85EA2D?style=flat-square&logo=swagger&logoColor=black) |
| **Testing** | ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white) ![JaCoCo](https://img.shields.io/badge/JaCoCo_0.8.13-C5D9C8?style=flat-square) |
| **Build** | ![Gradle](https://img.shields.io/badge/Gradle_9.2.1-02303A?style=flat-square&logo=gradle&logoColor=white) |

---

## 도메인 모듈

```
src/main/java/io/groo/damda/
├── asset/                 # 자산/이체/잔액 히스토리 (토스 평가액 스냅샷 스케줄러)
├── calendar/              # 일정/라벨/공휴일/댓글/집계
├── card/                  # 카드 카탈로그/혜택/실적/청구
├── constellation/         # 할 일 별자리 게이미피케이션 (별빛/일일 진행/도감)
├── dashboard/             # 대시보드 요약/레이아웃
├── dataimport/            # 거래 데이터 가져오기 (CSV/Excel 업로드 분석·실행)
├── dutchpay/              # 더치페이 정산
├── expense/               # 지출/카테고리/예산/분할/템플릿/반복거래
├── export/                # 데이터 내보내기 (Excel/CSV/JSON)
├── file/                  # 첨부파일
├── memo/                  # 메모/폴더
├── notification/          # 알림 (트리거/하트비트 스케줄러)
├── savingGoal/            # 저축 목표
├── subscription/          # 구독 요금제/기능 게이트
├── todo/                  # 할 일/프로젝트/태그
├── toss/                  # 토스증권 Open API 연동 (시세/호가, 사용자별 크리덴셜 암호화 저장)
├── user/                  # 사용자/OAuth 연동
├── security/              # 인증/JWT 필터/SSO 토큰 교환
└── common/                # 공통 설정/암호화/예외/메시지
```

---

## 테스트와 품질

- **테스트 슬라이스**: `@WebMvcTest`(+`@WithLoginUser` 커스텀 인증), `@DataJpaTest`(H2 + QueryDSL), Mockito 단위 테스트 — 총 127개 테스트 파일
- **JaCoCo 게이트**: `./gradlew test` 시 커버리지 리포트 생성 후 검증 — 측정 스코프(controller/domain/dto/config/security 등 제외) **BUNDLE LINE 커버리지 0.50 미만이면 빌드 실패**
- **브랜치 보호**: `main` 직접 push 금지, PR 생성 시 GitHub Actions CI(`.github/workflows/ci-main.yml`)가 테스트 실행·결과 리포트

---

## 시작하기

### 요구사항

- **Java**: 25 (Gradle toolchain 자동 관리)
- **Gradle**: Wrapper 포함 (9.2.1, `./gradlew` 사용)
- **MariaDB**, **Redis**: 접속 정보는 환경 변수로 주입
- **GitHub Packages 접근**: `GITHUB_ACTOR`, `GITHUB_TOKEN` 환경변수 필요 (groo-core 의존성)

### 환경 변수

앱은 `spring.config.import`로 프로필별 env 파일을 자동 로드합니다. (`.env.example` 참고)

- `.env.local` (기본 프로필 `local`)
- `.env.{profile}` (`SPRING_PROFILES_ACTIVE`로 선택, 예: `.env.dev`, `.env.prod`)

핵심 키:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- `JWT_SECRET` (desk 자체 토큰 HS256 서명 키)
- `SSO_API_URL`, `SSO_CLIENT_SECRET` (SSO 토큰 검증은 JWKS 공개키 사용)
- `APP_ENCRYPTION_KEY` (DB 보관 비밀값 공용 암호화 키 — 토스 크리덴셜 + SSO refresh token. 없으면 무음 재인증이 조용히 비활성)
- `CORS_ORIGINS`

### 빌드 및 실행

```bash
# 빌드 (테스트 포함)
./gradlew clean build

# 빌드 (테스트 제외)
./gradlew clean build -x test

# 테스트 + JaCoCo 커버리지 리포트/게이트 검증
./gradlew test

# 로컬 실행 (기본 프로필 local → .env.local 로드)
./gradlew bootRun

# 다른 프로필로 실행
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

기본 포트: `8002`

---

## API 문서

- Swagger UI: `http://localhost:8002/swagger-ui/index.html`
- OpenAPI: `http://localhost:8002/v3/api-docs`
- Actuator: `http://localhost:8002/actuator` (prometheus, health, info, metrics)

모든 API는 `/api/v1` 프리픽스를 사용하며, 응답/에러 메시지는 `src/main/resources/message/messages_ko.properties`·`messages_en.properties`로 한국어/영어를 지원합니다.

---

## 대표 API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/exchange-code` | SSO 인가 코드 → desk 토큰 교환 |
| GET | `/api/v1/dashboard/summary` | 대시보드 요약 |
| POST | `/api/v1/todo` | 할 일 생성 |
| POST | `/api/v1/calendar/event` | 일정 생성 |
| POST | `/api/v1/expense` | 지출 생성 |
| POST | `/api/v1/memo` | 메모 생성 |
| POST | `/api/v1/import/analyze` | 가져오기 파일 분석 (CSV/Excel) |
| POST | `/api/v1/export` | 데이터 내보내기 (Excel/CSV/JSON) |
| GET | `/api/v1/toss/prices` | 토스증권 시세 조회 |
| GET | `/api/v1/constellations/today` | 오늘의 별자리 진행 조회 |

---

## 관련 저장소

| Repository | Description |
|------------|-------------|
| [그루](https://github.com/lshdainty/groo) | 통합 레포지토리 (서비스 소개) |
| [groo-damda-front](https://github.com/lshdainty/groo-damda-front) | Desk 프론트엔드 |
| [groo-damda-app](https://github.com/lshdainty/groo-damda-app) | Desk 모바일 앱 (Flutter) |
| [groo-core](https://github.com/lshdainty/groo-core) | 공통 라이브러리 |
| [groo-sso-back](https://github.com/lshdainty/groo-sso-back) | SSO 백엔드 |
| [groo-sso-front](https://github.com/lshdainty/groo-sso-front) | SSO 프론트엔드 |

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/lshdainty">lshdainty</a>
</p>
