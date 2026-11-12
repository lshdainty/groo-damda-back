package io.groo.damda.export.service;

import io.groo.damda.common.exception.DeskErrorCode;

import io.groo.core.exception.InvalidValueException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.groo.damda.dataimport.service.FileParser;
import io.groo.damda.dataimport.service.ImportColumnMapper;
import io.groo.damda.dataimport.service.ParsedFile;
import io.groo.damda.dataimport.service.StandardRow;
import io.groo.damda.dataimport.type.ImportSource;
import io.groo.damda.export.controller.dto.ExportApiDto;
import io.groo.damda.export.type.ExportFormat;
import io.groo.damda.export.type.ExportPeriod;
import io.groo.damda.export.type.ExportType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import io.groo.core.time.ServiceClock;
import io.groo.core.time.UserClock;

/**
 * 데이터 내보내기 오케스트레이션 단위 테스트.
 *
 * <p>{@link PeriodResolver}·{@link ExportDataService} 는 mock — 기간 해석/데이터 조회는 신뢰하고,
 * {@link ExportServiceImpl} 의 책임(타입 dedup·선언순 정렬, 파일명/Content-Type 계산,
 * 형식별 분기[CSV/JSON/Excel], 다종 ZIP 패키징, 미리보기 상한, 소유권 userRowId·mask 전달)만 검증한다.
 *
 * <p>{@link ExportFileWriter} 는 static 유틸이라 실제로 실행된다 → writeExport 는 실제 CSV/JSON/xlsx
 * 바이트를 만들어 되읽어 값·이스케이프·구조를 확인한다(입력과 독립된 기대값으로 assert).
 */
@ExtendWith(MockitoExtension.class)
class ExportServiceImplTest {

    @Mock private PeriodResolver periodResolver;
    @Mock private ExportDataService dataService;
    // 날짜 판정은 실제 동작이 필요 — mock 이면 null 이 흘러 파일명 기간이 비어버린다
    @Spy private UserClock userClock = new UserClock(rowId -> null, new ServiceClock("Asia/Seoul"));

    @InjectMocks private ExportServiceImpl sut;

    private static final long USER_ID = 7L;
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);
    private static final PeriodResolver.DateRange RANGE = new PeriodResolver.DateRange(START, END);
    private static final String RANGE_PART = "2026-06-01_2026-06-30";

    /** 기간 해석은 별도 검증 대상(PeriodResolver 책임) → 고정 범위로 스텁. */
    private void givenResolvedRange() {
        given(periodResolver.resolve(any(), any(), any(), any())).willReturn(RANGE);
    }

    private ExportApiDto.ExportRequest exportReq(ExportFormat format, boolean mask, ExportType... types) {
        return new ExportApiDto.ExportRequest(format, ExportPeriod.THIS_MONTH, null, null, List.of(types), mask);
    }

    private ExportApiDto.CountRequest countReq(ExportType... types) {
        return new ExportApiDto.CountRequest(ExportPeriod.THIS_MONTH, null, null, List.of(types));
    }

    private ExportApiDto.PreviewRequest previewReq(ExportType... types) {
        return new ExportApiDto.PreviewRequest(ExportPeriod.THIS_MONTH, null, null, List.of(types));
    }

    private ExportTable table(ExportType type, List<String> headers, List<List<String>> rows) {
        return new ExportTable(type, headers, rows);
    }

    // ── describe: 파일명/Content-Type 계산(데이터 조회 없음) ─────────────────────
    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("단일 CSV — groo-<slug>-<기간>.csv + text/csv")
        void singleCsv() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.CSV, false, ExportType.EXPENSE), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-expense-" + RANGE_PART + ".csv");
            assertThat(d.contentType()).isEqualTo("text/csv; charset=UTF-8");
        }

        @Test
        @DisplayName("단일 EXCEL — .xlsx + 스프레드시트 Content-Type")
        void singleExcel() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.EXCEL, false, ExportType.EXPENSE), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-expense-" + RANGE_PART + ".xlsx");
            assertThat(d.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        @Test
        @DisplayName("다종 CSV — 종류별 파일을 ZIP 으로: groo-export-<기간>.zip + application/zip")
        void multiCsvZips() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.CSV, false, ExportType.EXPENSE, ExportType.TODO), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-export-" + RANGE_PART + ".zip");
            assertThat(d.contentType()).isEqualTo("application/zip");
        }

        @Test
        @DisplayName("다종 JSON — ZIP")
        void multiJsonZips() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.JSON, false, ExportType.EXPENSE, ExportType.MEMO), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-export-" + RANGE_PART + ".zip");
            assertThat(d.contentType()).isEqualTo("application/zip");
        }

        @Test
        @DisplayName("다종 EXCEL — ZIP 안 씀(한 워크북 시트 분리): groo-export-<기간>.xlsx")
        void multiExcelNeverZips() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.EXCEL, false, ExportType.EXPENSE, ExportType.TODO), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-export-" + RANGE_PART + ".xlsx");
            assertThat(d.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        @Test
        @DisplayName("같은 종류 중복 선택은 1종으로 축약 → 단일 파일명(ZIP 아님)")
        void dedupCollapsesToSingle() {
            givenResolvedRange();
            var d = sut.describe(exportReq(ExportFormat.CSV, false, ExportType.EXPENSE, ExportType.EXPENSE), USER_ID);
            assertThat(d.filename()).isEqualTo("groo-expense-" + RANGE_PART + ".csv");
            assertThat(d.contentType()).isEqualTo("text/csv; charset=UTF-8");
        }

        @Test
        @DisplayName("헤더 계산은 데이터를 조회하지 않는다(dataService 미접근)")
        void doesNotQueryData() {
            givenResolvedRange();
            sut.describe(exportReq(ExportFormat.CSV, false, ExportType.EXPENSE), USER_ID);
            verifyNoInteractions(dataService);
        }
    }

    // ── counts: 종별 건수(정렬·dedup·소유권) ─────────────────────────────────
    @Nested
    @DisplayName("counts")
    class Counts {

        @Test
        @DisplayName("선언 순서로 정렬해 종별 건수를 매핑하고 호출자 userRowId 를 그대로 위임한다")
        void ordersMapsAndForwardsOwnership() {
            givenResolvedRange();
            given(dataService.count(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END))).willReturn(3L);
            given(dataService.count(eq(ExportType.TODO), eq(USER_ID), eq(START), eq(END))).willReturn(5L);

            // 요청은 [TODO, EXPENSE] 지만 선언 순서(EXPENSE→TODO)로 정렬돼 나와야 한다.
            var res = sut.counts(countReq(ExportType.TODO, ExportType.EXPENSE), USER_ID);

            assertThat(res.counts()).containsExactly(
                new ExportApiDto.TypeCount("expense", "거래 내역", 3L),
                new ExportApiDto.TypeCount("todo", "할 일", 5L));
            // 소유권: 남의 데이터 조회 방지 — 조회 계층에 호출자 userRowId 를 그대로 전달.
            verify(dataService).count(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END));
            verify(dataService).count(eq(ExportType.TODO), eq(USER_ID), eq(START), eq(END));
        }

        @Test
        @DisplayName("중복 종류는 한 번만 집계한다(dedup)")
        void dedups() {
            givenResolvedRange();
            given(dataService.count(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END))).willReturn(3L);
            given(dataService.count(eq(ExportType.TODO), eq(USER_ID), eq(START), eq(END))).willReturn(5L);

            var res = sut.counts(countReq(ExportType.EXPENSE, ExportType.EXPENSE, ExportType.TODO), USER_ID);

            assertThat(res.counts()).containsExactly(
                new ExportApiDto.TypeCount("expense", "거래 내역", 3L),
                new ExportApiDto.TypeCount("todo", "할 일", 5L));
        }
    }

    // ── preview: 종별 상위 N행 + 총건수 ─────────────────────────────────────
    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("상위 10행으로 자르고 총건수는 원본 크기, mask=false 로 조회하며 userRowId 위임")
        void truncatesToLimit() {
            givenResolvedRange();
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < 12; i++) rows.add(List.of("r" + i));
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("행"), rows));

            var res = sut.preview(previewReq(ExportType.EXPENSE), USER_ID);

            assertThat(res.tables()).hasSize(1);
            var pt = res.tables().get(0);
            assertThat(pt.type()).isEqualTo("expense");
            assertThat(pt.displayName()).isEqualTo("거래 내역");
            assertThat(pt.headers()).containsExactly("행");
            assertThat(pt.rows()).hasSize(10);
            assertThat(pt.rows().get(0)).containsExactly("r0");
            assertThat(pt.rows().get(9)).containsExactly("r9");
            assertThat(pt.totalCount()).isEqualTo(12);
            // 미리보기는 항상 비마스킹(원본) 조회이며, 소유권은 userRowId 로 격리한다.
            verify(dataService).buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false));
        }

        @Test
        @DisplayName("행 수가 상한 미만이면 전부 반환하고 총건수와 일치")
        void fewerThanLimit() {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.MEMO), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.MEMO, List.of("제목"),
                    List.of(List.of("a"), List.of("b"), List.of("c"))));

            var res = sut.preview(previewReq(ExportType.MEMO), USER_ID);

            var pt = res.tables().get(0);
            assertThat(pt.rows()).hasSize(3);
            assertThat(pt.totalCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("빈 데이터 — 미리보기 행 없음, 총건수 0")
        void emptyData() {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.ASSET), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.ASSET, List.of("자산명"), List.of()));

            var res = sut.preview(previewReq(ExportType.ASSET), USER_ID);

            var pt = res.tables().get(0);
            assertThat(pt.rows()).isEmpty();
            assertThat(pt.totalCount()).isZero();
        }
    }

    // ── writeExport: 실제 CSV/JSON/xlsx 바이트 생성 검증 ──────────────────────
    @Nested
    @DisplayName("writeExport")
    class WriteExport {

        @Test
        @DisplayName("단일 CSV — BOM + RFC4180(모든 셀 인용, 내부 따옴표 이중화, 콤마/개행 보존)")
        void singleCsvEscapesSpecialChars() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("설명"),
                    List.of(List.of("a\"b"), List.of("x,y"), List.of("p\nq"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.CSV, false, ExportType.EXPENSE), USER_ID);

            String csv = out.toString(StandardCharsets.UTF_8);
            assertThat(csv).isEqualTo(
                "\uFEFF"
                + "\"설명\"\r\n"
                + "\"a\"\"b\"\r\n"
                + "\"x,y\"\r\n"
                + "\"p\nq\"\r\n");
        }

        @Test
        @DisplayName("CSV 수식 주입 — = + - @ TAB CR 로 시작하는 셀에 공백 접두(인용·이중화와 함께)")
        void csvGuardsFormulaInjection() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("거래처", "금액"),
                    List.of(
                        List.of("=HYPERLINK(\"http://evil\",\"x\")", "1000"),
                        List.of("@SUM(A1)", "-50000"),
                        List.of("+1+1", "0"),
                        List.of("-3+2", "0"),
                        List.of("\tTAB", "1"),
                        List.of("\rCR", "1"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.CSV, false, ExportType.EXPENSE), USER_ID);

            String csv = out.toString(StandardCharsets.UTF_8);
            assertThat(csv).isEqualTo(
                "\uFEFF"
                + "\"거래처\",\"금액\"\r\n"
                // 접두는 따옴표 안쪽 — 인용 전에 붙어야 셀이 깨지지 않는다.
                + "\" =HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",\"1000\"\r\n"
                // 금액 -50000 은 순수 숫자라 접두 대상이 아니다(음수 잔액 보존).
                + "\" @SUM(A1)\",\"-50000\"\r\n"
                + "\" +1+1\",\"0\"\r\n"
                + "\" -3+2\",\"0\"\r\n"
                + "\" \tTAB\",\"1\"\r\n"
                + "\" \rCR\",\"1\"\r\n");
        }

        @Test
        @DisplayName("CSV 수식 가드 — 순수 십진수(음수 잔액 포함)는 접두 없이 숫자로 남는다")
        void csvKeepsPlainNumbersUnprefixed() throws Exception {
            givenResolvedRange();
            // 부채 계열(LOAN·CREDIT_CARD) 잔액은 항상 음수 → 여기에 접두가 붙으면 금액 열이 통째로 텍스트가 된다.
            given(dataService.buildTable(eq(ExportType.ASSET), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.ASSET, List.of("잔액"),
                    List.of(List.of("-5000000"), List.of("+1000"), List.of("-1.5"), List.of("0"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.CSV, false, ExportType.ASSET), USER_ID);

            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(
                "\uFEFF"
                + "\"잔액\"\r\n"
                + "\"-5000000\"\r\n"
                + "\"+1000\"\r\n"
                + "\"-1.5\"\r\n"
                + "\"0\"\r\n");
        }

        @Test
        @DisplayName("내보낸 CSV 재업로드 왕복 — 공백 접두는 가져오기 trim 이 벗겨 원래 값이 복원된다")
        void csvFormulaGuardSurvivesImportRoundTrip() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("날짜", "유형", "금액", "설명", "거래처"),
                    List.of(List.of("2026-06-01 09:22", "EXPENSE", "1000", "=1+1", "@SUM(A1)"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.CSV, false, ExportType.EXPENSE), USER_ID);

            // 내보낸 바이트를 그대로 다시 업로드한다(가져오기가 하는 일과 같은 경로).
            ParsedFile parsed = FileParser.parse(
                new MockMultipartFile("file", "groo-expense.csv", "text/csv", out.toByteArray()));
            var mapping = ImportColumnMapper.suggest(ImportSource.POREST, parsed.headers());
            StandardRow row = ImportColumnMapper.mapRow(mapping, parsed.rows().get(0), 2);

            assertThat(row.memo()).isEqualTo("=1+1");
            assertThat(row.merchant()).isEqualTo("@SUM(A1)");
            assertThat(row.amount()).isEqualTo(1000L);
            assertThat(row.date()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 1, 9, 22));
            assertThat(row.error()).isNull();
        }

        @Test
        @DisplayName("수식 가드는 CSV 전용 — JSON·xlsx 는 값 그대로(문자열 셀이라 수식으로 평가되지 않는다)")
        void formulaGuardIsCsvOnly() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("거래처"), List.of(List.of("=1+1"))));

            // 가드를 cell() 로 옮기면 미리보기 API 응답까지 오염된다 — 자리는 CSV writer 하나뿐이어야 한다.
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            sut.writeExport(json, exportReq(ExportFormat.JSON, false, ExportType.EXPENSE), USER_ID);
            assertThat(new ObjectMapper().readTree(json.toByteArray()).get(0).get("거래처").asText())
                .isEqualTo("=1+1");

            ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
            sut.writeExport(xlsx, exportReq(ExportFormat.EXCEL, false, ExportType.EXPENSE), USER_ID);
            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx.toByteArray()))) {
                assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("=1+1");
            }
        }

        @Test
        @DisplayName("단일 JSON — 헤더를 키로 한 객체 배열(값 보존)")
        void singleJsonObjectsPerRow() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("제목", "금액"),
                    List.of(List.of("급여", "3,000,000"), List.of("a\"b", "0"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.JSON, false, ExportType.EXPENSE), USER_ID);

            JsonNode root = new ObjectMapper().readTree(out.toByteArray());
            assertThat(root.isArray()).isTrue();
            assertThat(root.size()).isEqualTo(2);
            assertThat(root.get(0).get("제목").asText()).isEqualTo("급여");
            assertThat(root.get(0).get("금액").asText()).isEqualTo("3,000,000");
            assertThat(root.get(1).get("제목").asText()).isEqualTo("a\"b");
            assertThat(root.get(1).get("금액").asText()).isEqualTo("0");
        }

        @Test
        @DisplayName("다종 CSV — 종류별 CSV 를 선언 순서로 ZIP 패키징(엔트리명=slug+기간)")
        void multiCsvZipsPerType() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("금액"), List.of(List.of("1000"))));
            given(dataService.buildTable(eq(ExportType.TODO), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.TODO, List.of("제목"), List.of(List.of("장보기"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // 요청 [TODO, EXPENSE] → 정렬 [EXPENSE, TODO] 순으로 엔트리가 나와야 한다.
            sut.writeExport(out, exportReq(ExportFormat.CSV, false, ExportType.TODO, ExportType.EXPENSE), USER_ID);

            Map<String, String> entries = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    entries.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                    zis.closeEntry();
                }
            }

            assertThat(entries.keySet()).containsExactly(
                "groo-expense-" + RANGE_PART + ".csv",
                "groo-todo-" + RANGE_PART + ".csv");
            assertThat(entries.get("groo-expense-" + RANGE_PART + ".csv"))
                .isEqualTo("\uFEFF\"금액\"\r\n\"1000\"\r\n");
            assertThat(entries.get("groo-todo-" + RANGE_PART + ".csv"))
                .isEqualTo("\uFEFF\"제목\"\r\n\"장보기\"\r\n");
        }

        @Test
        @DisplayName("다종 EXCEL — ZIP 아닌 한 워크북, 종류별 시트(시트명=표시명, 선언 순서)")
        void multiExcelSingleWorkbookSheets() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.EXPENSE), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.EXPENSE, List.of("날짜", "금액"),
                    List.of(List.of("2026-06-01", "1000"))));
            given(dataService.buildTable(eq(ExportType.TODO), eq(USER_ID), eq(START), eq(END), eq(false)))
                .willReturn(table(ExportType.TODO, List.of("제목"), List.of(List.of("장보기"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.EXCEL, false, ExportType.TODO, ExportType.EXPENSE), USER_ID);

            try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(2);

                Sheet expenseSheet = wb.getSheetAt(0);
                assertThat(expenseSheet.getSheetName()).isEqualTo("거래 내역");
                Row eh = expenseSheet.getRow(0);
                assertThat(eh.getCell(0).getStringCellValue()).isEqualTo("날짜");
                assertThat(eh.getCell(1).getStringCellValue()).isEqualTo("금액");
                Row er = expenseSheet.getRow(1);
                assertThat(er.getCell(0).getStringCellValue()).isEqualTo("2026-06-01");
                assertThat(er.getCell(1).getStringCellValue()).isEqualTo("1000");

                Sheet todoSheet = wb.getSheetAt(1);
                assertThat(todoSheet.getSheetName()).isEqualTo("할 일");
                assertThat(todoSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("제목");
                assertThat(todoSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("장보기");
            }
        }

        @Test
        @DisplayName("mask 옵션은 데이터 조회 계층으로 그대로 전달된다")
        void forwardsMaskFlag() throws Exception {
            givenResolvedRange();
            given(dataService.buildTable(eq(ExportType.ASSET), eq(USER_ID), eq(START), eq(END), eq(true)))
                .willReturn(table(ExportType.ASSET, List.of("잔액"), List.of(List.of("****"))));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sut.writeExport(out, exportReq(ExportFormat.CSV, true, ExportType.ASSET), USER_ID);

            verify(dataService).buildTable(eq(ExportType.ASSET), eq(USER_ID), eq(START), eq(END), eq(true));
        }
    }

    // ── orderedTypes 공통 가드(빈/누락 선택 방지) ────────────────────────────
    @Nested
    @DisplayName("타입 정렬·검증(orderedTypes)")
    class TypeValidation {

        @Test
        @DisplayName("빈 종류 목록은 거부(기간 해석·데이터 조회 이전에 실패)")
        void rejectsEmptyTypes() {
            assertThatThrownBy(() -> sut.counts(countReq(), USER_ID))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.EXPORT_TYPE_REQUIRED);
            verifyNoInteractions(periodResolver, dataService);
        }

        @Test
        @DisplayName("null 종류 목록도 거부")
        void rejectsNullTypes() {
            var req = new ExportApiDto.ExportRequest(
                ExportFormat.CSV, ExportPeriod.THIS_MONTH, null, null, null, false);
            assertThatThrownBy(() -> sut.describe(req, USER_ID))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.EXPORT_TYPE_REQUIRED);
            verifyNoInteractions(periodResolver, dataService);
        }
    }
}
