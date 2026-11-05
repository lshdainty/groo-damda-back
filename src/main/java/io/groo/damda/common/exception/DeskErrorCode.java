package io.groo.damda.common.exception;

import io.groo.core.exception.ErrorCodeProvider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DeskErrorCode implements ErrorCodeProvider {
    // Common
    INTERNAL_SERVER_ERROR("COMMON_500", "error.common.internal", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_INPUT("COMMON_400", "error.common.invalid.input", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED("COMMON_422", "error.common.validation.failed", HttpStatus.UNPROCESSABLE_ENTITY),
    CONCURRENT_MODIFICATION("COMMON_409", "error.common.concurrent.modification", HttpStatus.CONFLICT),
    INVALID_DATE_VALUE("COMMON_400", "error.common.invalid.date.value", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST_BODY("COMMON_400", "error.common.malformed.request.body", HttpStatus.BAD_REQUEST),
    // 경로는 있는데 메서드가 없는 자리(QA 2026-09-03 #64). 매핑이 아예 없는 경로는 core 가
    // NoResourceFoundException 을 COMMON_404 로 잡으므로 여기에 짝이 되는 상수를 두지 않는다.
    METHOD_NOT_ALLOWED("COMMON_405", "error.common.method.not.allowed", HttpStatus.METHOD_NOT_ALLOWED),
    // 쿼리·경로 파라미터의 타입이 안 맞는 자리(QA 2026-09-03 #74). core 가 이 문구를 코드에
    // 박아 두고 있어(격식체) 번들로 끌어낸다 — 코드는 COMMON_400 그대로다.
    INVALID_PARAMETER_VALUE("COMMON_400", "error.common.invalid.parameter.value", HttpStatus.BAD_REQUEST),
    // 필수 파라미터 누락(QA 2026-09-04 #75). 코드도 메시지 키도 core 가 답하던 것과 같다 —
    // 바뀐 건 문구뿐이고, 여기 상수를 두는 이유는 파라미터 이름을 로그에 남기는 책임을
    // 이 레포가 갖기 위해서다(RequestValueExceptionHandler 주석 참고).
    MISSING_PARAMETER("COMMON_400", "error.common.missing.parameter", HttpStatus.BAD_REQUEST),
    // DB NOT NULL 위반이 여기까지 온 자리(QA 2026-09-07 #81). 종전엔 COMMON_409 "다른 곳에서
    // 먼저 수정됐어요" 로 나가 값을 빼먹은 요청이 경쟁에서 진 것처럼 보였다. 어느 컬럼인지는
    // 응답에 싣지 않는다(QA #75) — DataIntegrityExceptionHandler 의 로그에만 남는다.
    REQUIRED_VALUE_MISSING("COMMON_400", "error.common.required.value.missing", HttpStatus.BAD_REQUEST),
    // DB 외래키 위반(QA 2026-09-07 #81). 없는 것을 가리켰거나, 아직 참조 중인 것을 지우려 한 것이다.
    INVALID_REFERENCE("COMMON_400", "error.common.invalid.reference", HttpStatus.BAD_REQUEST),

    // Auth
    AUTH_INVALID_TOKEN("AUTH_001", "error.auth.invalid.token", HttpStatus.UNAUTHORIZED),
    AUTH_EXPIRED_TOKEN("AUTH_002", "error.auth.expired.token", HttpStatus.UNAUTHORIZED),
    AUTH_ACCESS_DENIED("AUTH_003", "error.auth.access.denied", HttpStatus.FORBIDDEN),
    AUTH_EXCHANGE_FAILED("AUTH_004", "error.auth.exchange.failed", HttpStatus.UNAUTHORIZED),

    // User
    USER_NOT_FOUND("USER_001", "error.notfound.user", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("USER_002", "error.duplicate.user", HttpStatus.CONFLICT),
    USER_PASSWORD_CHANGE_FAILED("USER_003", "error.user.password.change.failed", HttpStatus.BAD_REQUEST),
    USER_PASSWORD_VERIFY_FAILED("USER_004", "error.user.password.verify.failed", HttpStatus.BAD_REQUEST),
    // desk 이용 해지 — 설계서 A 흐름.
    WITHDRAW_BLOCKED_SUBSCRIPTION("USER_020", "error.user.withdraw.blocked.subscription", HttpStatus.CONFLICT),
    USER_WITHDRAWN("USER_021", "error.user.withdrawn", HttpStatus.FORBIDDEN),
    REAUTH_REQUIRED("AUTH_020", "error.auth.reauth.required", HttpStatus.UNAUTHORIZED),
    REAUTH_FAILED("AUTH_021", "error.auth.reauth.failed", HttpStatus.BAD_REQUEST),
    // 본인 확인을 여러 번 틀려 잠긴 것 — SSO 가 429 로 알려 주면 그대로 넘긴다.
    // 오답(AUTH_021, 400)과 같은 코드로 내보내면 화면이 "틀렸어요, 다시" 로 읽어,
    // 기다려야 풀린다는 것을 전하지 못한다(QA 22차 #8).
    REAUTH_LOCKED("AUTH_022", "error.auth.reauth.locked", HttpStatus.TOO_MANY_REQUESTS),

    // SSO
    SSO_SERVICE_ERROR("SSO_001", "error.sso.service.error", HttpStatus.BAD_GATEWAY),

    // OAuth account linking (소셜 계정 연동 프록시)
    OAUTH_LINK_FAILED("OAUTH_001", "error.oauth.link.failed", HttpStatus.BAD_REQUEST),

    // Todo
    TODO_NOT_FOUND("TODO_001", "error.notfound.todo", HttpStatus.NOT_FOUND),
    TODO_TAG_NOT_FOUND("TODO_003", "error.notfound.todo.tag", HttpStatus.NOT_FOUND),
    TODO_ACCESS_DENIED("TODO_004", "error.todo.access.denied", HttpStatus.FORBIDDEN),
    TODO_TAG_DUPLICATE_NAME("TODO_005", "error.duplicate.todo.tag", HttpStatus.CONFLICT),

    // Calendar
    CALENDAR_EVENT_NOT_FOUND("CAL_001", "error.notfound.calendar.event", HttpStatus.NOT_FOUND),
    CALENDAR_INVALID_DATE_RANGE("CAL_002", "error.calendar.invalid.date.range", HttpStatus.BAD_REQUEST),
    EVENT_LABEL_NOT_FOUND("CAL_003", "error.notfound.event.label", HttpStatus.NOT_FOUND),
    EVENT_REMINDER_NOT_FOUND("CAL_004", "error.notfound.event.reminder", HttpStatus.NOT_FOUND),
    USER_CALENDAR_NOT_FOUND("CAL_005", "error.notfound.user.calendar", HttpStatus.NOT_FOUND),
    USER_CALENDAR_DEFAULT_DELETE("CAL_006", "error.calendar.default.delete", HttpStatus.BAD_REQUEST),
    // 기본 캘린더는 숨기지도 못한다 — 일정 저장 때 캘린더를 안 고르면 서버가 기본 캘린더를 대입하는데,
    // 그게 숨겨져 있으면 방금 만든 일정이 곧바로 안 보인다. 삭제 거절(CAL_006)을 재사용하지 않는 이유는
    // 문구다 — "삭제할 수 없어요" 로는 무엇을 하다 막혔는지 알 수 없다. 번호는 캘린더 블록이 이미
    // 015 까지 차 있어 016 이다.
    USER_CALENDAR_DEFAULT_HIDE("CAL_016", "error.calendar.default.hide", HttpStatus.BAD_REQUEST),
    CALENDAR_ACCESS_DENIED("CAL_007", "error.calendar.access.denied", HttpStatus.FORBIDDEN),
    CALENDAR_EVENT_ACCESS_DENIED("CAL_008", "error.calendar.event.access.denied", HttpStatus.FORBIDDEN),
    EVENT_LABEL_ACCESS_DENIED("CAL_009", "error.event.label.access.denied", HttpStatus.FORBIDDEN),
    USER_CALENDAR_MEMBER_NOT_FOUND("CAL_010", "error.notfound.user.calendar.member", HttpStatus.NOT_FOUND),
    USER_CALENDAR_ALREADY_MEMBER("CAL_011", "error.user.calendar.already.member", HttpStatus.CONFLICT),
    USER_CALENDAR_INVITE_INVALID("CAL_012", "error.user.calendar.invite.invalid", HttpStatus.NOT_FOUND),
    USER_CALENDAR_OWNER_REMOVE("CAL_013", "error.user.calendar.owner.remove", HttpStatus.BAD_REQUEST),
    EVENT_LABEL_DUPLICATE_NAME("CAL_014", "error.duplicate.event.label", HttpStatus.CONFLICT),
    CALENDAR_EVENT_CALENDAR_REQUIRED("CAL_015", "error.calendar.event.calendar.required", HttpStatus.BAD_REQUEST),

    // Memo
    MEMO_NOT_FOUND("MEMO_001", "error.notfound.memo", HttpStatus.NOT_FOUND),
    MEMO_ACCESS_DENIED("MEMO_003", "error.memo.access.denied", HttpStatus.FORBIDDEN),
    // MEMO_002 는 쓰지 않는다 — 지난 코드가 쓰다 사라진 번호라 되쓰면 옛 클라이언트가 다른
    // 뜻으로 읽을 수 있다. 태그는 그래서 004 부터다.
    MEMO_TAG_NOT_FOUND("MEMO_004", "error.notfound.memo.tag", HttpStatus.NOT_FOUND),
    MEMO_TAG_DUPLICATE_NAME("MEMO_005", "error.duplicate.memo.tag", HttpStatus.CONFLICT),

    // Constellation
    CONSTELLATION_NOT_FOUND("CONST_001", "error.notfound.constellation", HttpStatus.NOT_FOUND),

    // Expense
    EXPENSE_NOT_FOUND("EXP_001", "error.notfound.expense", HttpStatus.NOT_FOUND),
    EXPENSE_CATEGORY_NOT_FOUND("EXP_002", "error.notfound.expense.category", HttpStatus.NOT_FOUND),
    EXPENSE_BUDGET_NOT_FOUND("EXP_003", "error.notfound.expense.budget", HttpStatus.NOT_FOUND),
    EXPENSE_TEMPLATE_NOT_FOUND("EXP_004", "error.notfound.expense.template", HttpStatus.NOT_FOUND),
    RECURRING_TRANSACTION_NOT_FOUND("EXP_005", "error.notfound.recurring.transaction", HttpStatus.NOT_FOUND),
    EXPENSE_CATEGORY_MAX_DEPTH("EXP_006", "error.expense.category.max.depth", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_HAS_CHILDREN("EXP_007", "error.expense.category.has.children", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_TYPE_MISMATCH("EXP_008", "error.expense.category.type.mismatch", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_NOT_LEAF("EXP_009", "error.expense.category.not.leaf", HttpStatus.BAD_REQUEST),
    EXPENSE_ACCESS_DENIED("EXP_010", "error.expense.access.denied", HttpStatus.FORBIDDEN),
    EXPENSE_SPLIT_NOT_FOUND("EXP_011", "error.notfound.expense.split", HttpStatus.NOT_FOUND),
    EXPENSE_SPLIT_AMOUNT_MISMATCH("EXP_012", "error.expense.split.amount.mismatch", HttpStatus.BAD_REQUEST),
    EXPENSE_BUDGET_CATEGORY_NOT_ROOT("EXP_013", "error.expense.budget.category.not.root", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_CANNOT_DEMOTE("EXP_014", "error.expense.category.cannot.demote", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_CANNOT_PROMOTE("EXP_015", "error.expense.category.cannot.promote", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_PARENT_HAS_TX("EXP_016", "error.expense.category.parent.has.tx", HttpStatus.BAD_REQUEST),
    EXPENSE_BUDGET_INVALID_AMOUNT("EXP_017", "error.expense.budget.invalid.amount", HttpStatus.BAD_REQUEST),
    EXPENSE_INVALID_DATE_RANGE("EXP_018", "error.expense.invalid.date.range", HttpStatus.BAD_REQUEST),
    EXPENSE_CATEGORY_DUPLICATE_NAME("EXP_019", "error.duplicate.expense.category", HttpStatus.CONFLICT),
    EXPENSE_TYPE_CATEGORY_MISMATCH("EXP_020", "error.expense.type.category.mismatch", HttpStatus.BAD_REQUEST),
    EXPENSE_INVALID_AMOUNT("EXP_021", "error.expense.invalid.amount", HttpStatus.BAD_REQUEST),
    EXPENSE_AUTO_GENERATED_READONLY("EXP_022", "error.expense.auto.generated.readonly", HttpStatus.BAD_REQUEST),
    EXPENSE_TEMPLATE_DUPLICATE_NAME("EXP_023", "error.duplicate.expense.template", HttpStatus.CONFLICT),

    /*
     * 환불 마크 (2026-09-18 설계 결정 1). 환불은 원거래에 찍는 표식이라 "환불 거래" 가
     * 없다 — 그래서 옛 코드(EXP_024 상한 초과 · EXP_025 원거래 축소 금지)는 걷었다.
     */
    REFUND_NOT_EXPENSE("EXP_040", "error.expense.refund.not.expense", HttpStatus.BAD_REQUEST),
    ALREADY_REFUNDED("EXP_041", "error.expense.already.refunded", HttpStatus.BAD_REQUEST),
    NOT_REFUNDED("EXP_042", "error.expense.not.refunded", HttpStatus.BAD_REQUEST),
    REFUNDED_READONLY("EXP_043", "error.expense.refunded.readonly", HttpStatus.BAD_REQUEST),
    /**
     * 옛 앱·웹이 보내는 폐기된 칸(`refundOfExpenseRowId`).
     *
     * <p>조용히 무시하면 환불이 <b>일반 수입</b>으로 저장된다 — 사용자는 환불한 줄 알고
     * 통계는 부푼다. 눈에 보이게 막으면 사용자가 업데이트하면 되므로 강제 업데이트가
     * 필요 없다(설계서 6절).
     */
    DEPRECATED_FIELD("EXP_044", "error.expense.deprecated.field", HttpStatus.BAD_REQUEST),
    ASSET_TRANSFER_AUTO_GENERATED_READONLY("AST_030", "error.asset.transfer.auto.generated.readonly", HttpStatus.BAD_REQUEST),
    ASSET_TRANSFER_CARD_PAYMENT_READONLY("AST_031", "error.asset.transfer.card.payment.readonly", HttpStatus.BAD_REQUEST),
    ASSET_TRANSFER_CARD_REFUND_READONLY("AST_032", "error.asset.transfer.card.refund.readonly", HttpStatus.BAD_REQUEST),
    // 결제가 끝난 카드 거래는 돈 칸을 고칠 수 없다(D12) — 지우고 다시 적거나 고쳐 쓰기.
    EXPENSE_MONEY_LOCKED("EXP_045", "error.expense.money.locked", HttpStatus.BAD_REQUEST),
    EXPENSE_REPLACE_NOT_LOCKED("EXP_046", "error.expense.replace.not.locked", HttpStatus.BAD_REQUEST),
    EXPENSE_REPLACE_PAID_OFF("EXP_047", "error.expense.replace.paid.off", HttpStatus.BAD_REQUEST),
    REFUND_DATE_OUT_OF_RANGE("EXP_048", "error.expense.refund.date.out.of.range", HttpStatus.BAD_REQUEST),
    EXPENSE_CARRYOVER_READONLY("EXP_049", "error.expense.carryover.readonly", HttpStatus.BAD_REQUEST),
    // 자동으로 만들어진 거래(이월 · 매도 손익 · 이체 이자)를 원본으로 반복 거래·더치페이를 만들 수 없다
    EXPENSE_AUTO_GENERATED_NO_DERIVE("EXP_050", "error.expense.auto.generated.no.derive", HttpStatus.BAD_REQUEST),
    // 신용카드 결제일·이월(D7·D8·D15)
    ASSET_CARD_PAYMENT_DAY_REQUIRED("AST_033", "error.asset.card.payment.day.required", HttpStatus.BAD_REQUEST),
    ASSET_CARD_CARRYOVER_LOCKED("AST_034", "error.asset.card.carryover.locked", HttpStatus.BAD_REQUEST),
    // 결제 대기 청구분을 넣을 회차가 없다 — 이번 달 결제일이 지났거나 오늘, 또는 등록한 달이 아니다
    ASSET_CARD_DUE_CARRYOVER_CLOSED("AST_035", "error.asset.card.due.carryover.closed", HttpStatus.BAD_REQUEST),
    // 외화 자산은 환율(통화 1단위당 원화)이 있어야 예수금 · 거래를 원화로 잴 수 있다 — 0 이하 · 비움은 거절
    ASSET_FOREIGN_RATE_REQUIRED("AST_036", "error.asset.foreign.rate.required", HttpStatus.BAD_REQUEST),
    // 신용 · 체크카드는 원화만 — 청구 · 상계 · 결제 장부가 원화다. 해외 결제는 거래의 통화 칸으로 적는다
    ASSET_CARD_FOREIGN_CURRENCY("AST_037", "error.asset.card.foreign.currency", HttpStatus.BAD_REQUEST),

    // Asset
    ASSET_NOT_FOUND("ASSET_001", "error.notfound.asset", HttpStatus.NOT_FOUND),
    ASSET_TRANSFER_NOT_FOUND("ASSET_002", "error.notfound.asset.transfer", HttpStatus.NOT_FOUND),
    ASSET_TRANSFER_SAME_ASSET("ASSET_003", "error.asset.transfer.same", HttpStatus.BAD_REQUEST),
    ASSET_ACCESS_DENIED("ASSET_004", "error.asset.access.denied", HttpStatus.FORBIDDEN),
    ASSET_TRANSFER_INVALID_AMOUNT("ASSET_005", "error.asset.transfer.invalid.amount", HttpStatus.BAD_REQUEST),
    ASSET_TRANSFER_INVALID_INTEREST("ASSET_014", "error.asset.transfer.invalid.interest", HttpStatus.BAD_REQUEST),
    /**
     * 수수료가 음수.
     *
     * <p>종전엔 {@code ASSET_005}(이체 금액)를 돌려 써서 "이체 금액은 0보다 커야 해요" 가 떴다 —
     * 금액은 멀쩡한데 금액을 고치라는 말이라 사용자가 고칠 자리를 못 찾는다.
     */
    ASSET_TRANSFER_INVALID_FEE("ASSET_015", "error.asset.transfer.invalid.fee", HttpStatus.BAD_REQUEST),
    ASSET_TRANSFER_CHECK_CARD("ASSET_006", "error.asset.transfer.check.card", HttpStatus.BAD_REQUEST),
    /**
     * 반복·프리셋 이체의 당사자로 카드를 고른 경우.
     *
     * <p>공용 이체 검증({@code validateTransfer})은 체크카드만 막고 <b>신용카드는 일부러
     * 허용</b>한다 — 결제일 자동이체가 신용카드로 들어가는 이체이기 때문이다. 그래서 카드
     * 금지는 그 자리에 넣을 수 없고, <b>반복·프리셋을 만들 때</b> 따로 건다.
     *
     * <p>실행 시점이 아니라 만들 때 막는 이유: 자정 배치는 실패해도 로그만 남는다. 만들 때
     * 안 막으면 사용자는 "저장됐다" 고 믿고 매일 밤 조용히 실패하는 규칙을 갖게 된다.
     */
    RECURRING_TRANSFER_CARD_NOT_ALLOWED("EXP_026", "error.recurring.transfer.card.not.allowed", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_NOT_FOUND("ASSET_007", "error.notfound.asset.trade", HttpStatus.NOT_FOUND),
    ASSET_TRADE_NOT_INVESTMENT("ASSET_008", "error.asset.trade.not.investment", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_INVALID_QUANTITY("ASSET_009", "error.asset.trade.invalid.quantity", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_INVALID_AMOUNT("ASSET_010", "error.asset.trade.invalid.amount", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_INSUFFICIENT_CASH("ASSET_011", "error.asset.trade.insufficient.cash", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_INSUFFICIENT_QUANTITY("ASSET_012", "error.asset.trade.insufficient.quantity", HttpStatus.BAD_REQUEST),
    ASSET_TRADE_HOLDING_NOT_FOUND("ASSET_013", "error.asset.trade.holding.not.found", HttpStatus.BAD_REQUEST),

    // Saving Goal
    SAVING_GOAL_NOT_FOUND("SAVING_001", "error.notfound.saving.goal", HttpStatus.NOT_FOUND),
    SAVING_GOAL_ACCESS_DENIED("SAVING_002", "error.saving.goal.access.denied", HttpStatus.FORBIDDEN),
    SAVING_GOAL_DUPLICATE_NAME("SAVING_003", "error.duplicate.saving.goal", HttpStatus.CONFLICT),

    // Dutch Pay
    DUTCH_PAY_NOT_FOUND("DUTCH_001", "error.notfound.dutch.pay", HttpStatus.NOT_FOUND),
    DUTCH_PAY_PARTICIPANT_NOT_FOUND("DUTCH_002", "error.notfound.dutch.pay.participant", HttpStatus.NOT_FOUND),
    DUTCHPAY_ACCESS_DENIED("DUTCH_003", "error.dutchpay.access.denied", HttpStatus.FORBIDDEN),
    DUTCH_PAY_INVALID_PARTICIPANT_AMOUNT("DUTCH_004", "error.dutchpay.invalid.participant.amount", HttpStatus.BAD_REQUEST),
    DUTCH_PAY_DUPLICATE_PARTICIPANT("DUTCH_005", "error.duplicate.dutch.pay.participant", HttpStatus.CONFLICT),
    DUTCH_PAY_INVALID_PAYER("DUTCH_006", "error.dutchpay.invalid.payer", HttpStatus.BAD_REQUEST),
    DUTCH_PAY_PAYER_CONFLICT("DUTCH_007", "error.dutchpay.payer.conflict", HttpStatus.CONFLICT),
    DUTCH_PAY_PAYER_REQUIRED("DUTCH_008", "error.dutchpay.payer.required", HttpStatus.BAD_REQUEST),

    // Notification
    NOTIFICATION_NOT_FOUND("NOTI_001", "error.notfound.notification", HttpStatus.NOT_FOUND),
    NOTIFICATION_ACCESS_DENIED("NOTI_002", "error.notification.access.denied", HttpStatus.FORBIDDEN),

    // Event Comment
    EVENT_COMMENT_NOT_FOUND("GROUP_003", "error.notfound.event.comment", HttpStatus.NOT_FOUND),
    EVENT_COMMENT_ACCESS_DENIED("GROUP_004", "error.event.comment.access.denied", HttpStatus.FORBIDDEN),

    // File
    FILE_NOT_FOUND("FILE_001", "error.notfound.file", HttpStatus.NOT_FOUND),
    FILE_ACCESS_DENIED("FILE_002", "error.file.access.denied", HttpStatus.FORBIDDEN),
    FILE_INVALID_TYPE("FILE_003", "error.file.invalid.type", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE("FILE_004", "error.file.too.large", HttpStatus.BAD_REQUEST),

    // Card
    CARD_CATALOG_NOT_FOUND("CARD_001", "error.notfound.card.catalog", HttpStatus.NOT_FOUND),
    CARD_BENEFIT_MAPPING_NOT_FOUND("CARD_002", "error.notfound.card.benefit.mapping", HttpStatus.NOT_FOUND),
    CARD_BENEFIT_MAPPING_ACCESS_DENIED("CARD_003", "error.card.benefit.mapping.access.denied", HttpStatus.FORBIDDEN),
    CARD_BENEFIT_MAPPING_DUPLICATE("CARD_004", "error.duplicate.card.benefit.mapping", HttpStatus.CONFLICT),
    CARD_BILLING_NOT_CREDIT_CARD("CARD_005", "error.card.billing.not.credit.card", HttpStatus.BAD_REQUEST),
    CARD_BILLING_PAYMENT_ASSET_REQUIRED("CARD_006", "error.card.billing.payment.asset.required", HttpStatus.BAD_REQUEST),
    CARD_BILLING_INSUFFICIENT_BALANCE("CARD_007", "error.card.billing.insufficient.balance", HttpStatus.BAD_REQUEST),
    CARD_BILLING_INVALID_AMOUNT("CARD_008", "error.card.billing.invalid.amount", HttpStatus.BAD_REQUEST),
    CARD_BILLING_NOT_FOUND("CARD_009", "error.card.billing.not.found", HttpStatus.NOT_FOUND),
    CARD_BILLING_NOT_CANCELABLE("CARD_010", "error.card.billing.not.cancelable", HttpStatus.BAD_REQUEST),
    CARD_INSTALLMENT_NOT_FOUND("CARD_011", "error.card.installment.not.found", HttpStatus.NOT_FOUND),
    CARD_INSTALLMENT_NOT_INSTALLMENT("CARD_012", "error.card.installment.not.installment", HttpStatus.BAD_REQUEST),
    CARD_INSTALLMENT_FINISHED("CARD_013", "error.card.installment.finished", HttpStatus.BAD_REQUEST),
    CARD_INSTALLMENT_ALREADY_PAID_OFF("CARD_014", "error.card.installment.already.paid.off", HttpStatus.BAD_REQUEST),
    CARD_INSTALLMENT_NOT_PAID_OFF("CARD_015", "error.card.installment.not.paid.off", HttpStatus.BAD_REQUEST),
    CARD_BILLING_INVALID_CYCLE("CARD_016", "error.card.billing.invalid.cycle", HttpStatus.BAD_REQUEST),
    // 결제 취소 차단(D6) — 결제일이 된 회차·환급이 나간 회차.
    CARD_BILLING_CYCLE_CLOSED("CARD_017", "error.card.billing.cycle.closed", HttpStatus.BAD_REQUEST),
    CARD_BILLING_REFUNDED("CARD_018", "error.card.billing.refunded", HttpStatus.BAD_REQUEST),
    CARD_INSTALLMENT_PAYOFF_CLOSED("CARD_019", "error.card.installment.payoff.closed", HttpStatus.BAD_REQUEST),

    // Securities (증권사 Open API 연동 — 토스증권 · 나무증권)
    // 메시지에 증권사 이름을 넣지 않는다: 메시지 리졸버가 인자를 받지 않아 파라미터화가 불가능하고,
    // 어느 증권사에 요청했는지는 호출한 쪽이 이미 안다.
    SECURITIES_NOT_CONFIGURED("SEC_001", "error.securities.not.configured", HttpStatus.SERVICE_UNAVAILABLE),
    SECURITIES_AUTH_ERROR("SEC_002", "error.securities.auth.error", HttpStatus.BAD_GATEWAY),
    SECURITIES_API_ERROR("SEC_003", "error.securities.api.error", HttpStatus.BAD_GATEWAY),
    SECURITIES_CREDENTIAL_REQUIRED("SEC_004", "error.securities.credential.required", HttpStatus.FORBIDDEN),
    SECURITIES_CREDENTIAL_INVALID("SEC_005", "error.securities.credential.invalid", HttpStatus.BAD_GATEWAY),
    SECURITIES_SYMBOL_INVALID("SEC_006", "error.securities.symbol.invalid", HttpStatus.BAD_REQUEST),
    SECURITIES_BROKER_UNSUPPORTED("SEC_007", "error.securities.broker.unsupported", HttpStatus.BAD_REQUEST),
    SECURITIES_MARKET_UNSUPPORTED("SEC_008", "error.securities.market.unsupported", HttpStatus.BAD_REQUEST),
    // 계좌구분(운영/모의투자)과 연동 환경이 어긋나는 자리. 업스트림 장애가 아니라 요청·설정 문제라
    // 502 로 뭉뚱그리지 않는다 — 502 + "API 호출에 실패했습니다" 로는 무엇을 해야 할지 알 수 없다.
    SECURITIES_ACCOUNT_NOT_FOUND("SEC_009", "error.securities.account.not.found", HttpStatus.BAD_REQUEST),
    SECURITIES_ACCOUNT_ENVIRONMENT_MISMATCH("SEC_010", "error.securities.account.environment.mismatch", HttpStatus.BAD_REQUEST),
    SECURITIES_ACCOUNT_ENVIRONMENT_UNAVAILABLE("SEC_011", "error.securities.account.environment.unavailable", HttpStatus.CONFLICT),
    // 해외 잔고는 거래국가가 미국 고정이라 통화도 USD 만 뜻이 통한다. 안 맞는 조합을 그냥 태우면
    // 나무가 에러 대신 0건을 줘 "보유 종목 없음" 으로 둔갑한다 — 그래서 400 으로 먼저 막는다.
    SECURITIES_CURRENCY_UNSUPPORTED("SEC_012", "error.securities.currency.unsupported", HttpStatus.BAD_REQUEST),

    /**
     * 연결한 증권사 중 캔들(기간별시세)을 주는 곳이 없다.
     * <b>{@code SECURITIES_CREDENTIAL_REQUIRED}(연결이 없다)와 뜻이 다르다</b> — 이쪽은 이미
     * 연결한 사용자라, "연결하세요" 를 띄우면 고칠 방법 없는 안내가 된다.
     */
    SECURITIES_CANDLE_UNSUPPORTED("SEC_013", "error.securities.candle.unsupported", HttpStatus.CONFLICT),

    /** 모르는 캔들 주기. 지금은 {@code 1m}·{@code 1d} 만 있다. */
    SECURITIES_INTERVAL_UNSUPPORTED("SEC_014", "error.securities.interval.unsupported", HttpStatus.BAD_REQUEST),

    /**
     * 연결은 돼 있는데 증권사가 호출을 거절했다 — 업스트림 401·403(토큰 만료 · 키 무효 · 권한 없음 — 403 은 원인을 단정할 수 없어 메시지도 '만료'로 단정하지 않는다).
     * <b>{@code SECURITIES_API_ERROR}(잠깐 안 된다)와 뜻이 다르다</b> — 기다려도 안 풀리고 다시 연결해야 한다.
     * {@code SECURITIES_CREDENTIAL_REQUIRED}(연결이 없다)와도 다르다.
     * HTTP 상태를 401 로 두지 않는다: 클라이언트의 401 처리(토큰 갱신 · 로그아웃)를 타면 안 된다 —
     * 이 401 은 우리 로그인이 아니라 증권사 쪽 인증이다. 502 를 유지해 옛 앱은 일반 오류로 읽는다.
     */
    SECURITIES_AUTH_EXPIRED("SEC_015", "error.securities.auth.expired", HttpStatus.BAD_GATEWAY),

    // API Token (프로그램용 증권 조회 토큰)
    /** 없는 토큰 · 폐기된 토큰 · 주인이 해지한 토큰. 어느 쪽인지는 밝히지 않는다. */
    API_TOKEN_INVALID("APITOKEN_001", "error.api.token.invalid", HttpStatus.UNAUTHORIZED),
    /** 토큰은 맞지만 그 자리는 토큰으로 못 연다 — 열리는 건 증권 조회(GET) 셋뿐이다. */
    API_TOKEN_SCOPE_DENIED("APITOKEN_002", "error.api.token.scope.denied", HttpStatus.FORBIDDEN),
    API_TOKEN_RATE_LIMITED("APITOKEN_003", "error.api.token.rate.limited", HttpStatus.TOO_MANY_REQUESTS),
    API_TOKEN_LIMIT_EXCEEDED("APITOKEN_004", "error.api.token.limit", HttpStatus.BAD_REQUEST),
    API_TOKEN_NOT_FOUND("APITOKEN_005", "error.notfound.api.token", HttpStatus.NOT_FOUND),
    API_TOKEN_NAME_INVALID("APITOKEN_006", "error.api.token.name.invalid", HttpStatus.BAD_REQUEST),

    // Subscription (구독·기능권한)
    SUBSCRIPTION_REQUIRED("SUBS_001", "error.subscription.required", HttpStatus.FORBIDDEN),
    SUBSCRIPTION_NOT_FOUND("SUBS_002", "error.notfound.subscription", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_PLAN_NOT_FOUND("SUBS_003", "error.notfound.subscription.plan", HttpStatus.NOT_FOUND),
    SUBSCRIPTION_ALREADY_ACTIVE("SUBS_004", "error.subscription.already.active", HttpStatus.CONFLICT),

    // Data Import (데이터 가져오기)
    IMPORT_EMPTY_FILE("IMPORT_001", "error.import.empty.file", HttpStatus.BAD_REQUEST),
    IMPORT_PARSE_FAILED("IMPORT_002", "error.import.parse.failed", HttpStatus.BAD_REQUEST),
    IMPORT_UNSUPPORTED_FORMAT("IMPORT_003", "error.import.unsupported.format", HttpStatus.BAD_REQUEST),
    IMPORT_MAPPING_REQUIRED("IMPORT_004", "error.import.mapping.required", HttpStatus.BAD_REQUEST),
    IMPORT_TOO_MANY_ROWS("IMPORT_005", "error.import.too.many.rows", HttpStatus.BAD_REQUEST),

    // Stock (종목 마스터·관심목록)
    STOCK_NOT_FOUND("STOCK_001", "error.notfound.stock", HttpStatus.NOT_FOUND),
    STOCK_WATCH_GROUP_NOT_FOUND("STOCK_002", "error.notfound.stock.watch.group", HttpStatus.NOT_FOUND),
    STOCK_WATCH_ITEM_NOT_FOUND("STOCK_003", "error.notfound.stock.watch.item", HttpStatus.NOT_FOUND),
    STOCK_WATCH_GROUP_LIMIT_EXCEEDED("STOCK_004", "error.stock.watch.group.limit", HttpStatus.BAD_REQUEST),
    STOCK_WATCH_ITEM_LIMIT_EXCEEDED("STOCK_005", "error.stock.watch.item.limit", HttpStatus.BAD_REQUEST),
    STOCK_WATCH_GROUP_NAME_DUPLICATE("STOCK_006", "error.duplicate.stock.watch.group.name", HttpStatus.CONFLICT),

    // SMS (결제 문자 가져오기)
    SMS_NOT_RECOGNIZED("SMS_001", "error.sms.not.recognized", HttpStatus.BAD_REQUEST),
    SMS_CANCEL_NOT_SUPPORTED("SMS_002", "error.sms.cancel.not.supported", HttpStatus.BAD_REQUEST),
    SMS_CARD_MAPPING_NOT_FOUND("SMS_003", "error.notfound.sms.card.mapping", HttpStatus.NOT_FOUND),
    ;

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;
}
