package io.groo.damda.dataimport.service;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보안 점검 DAM-6 — 가져오기 파일을 끝까지 메모리에 올리기 전에 행 수·크기 한도로 멈춘다.
 *
 * <p>값 규칙(날짜·숫자·빈 칸)이 통합 문서 경로와 같은지도 여기서 본다 — xlsx 를 SAX 로 바꿨기 때문이다.
 */
@DisplayName("FileParser — 스트리밍 읽기와 한도")
class FileParserStreamingTest {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // ── CSV ──────────────────────────────────────────────────

    /** 바이트를 미리 만들지 않고 흘려 주며 몇 바이트를 읽어 갔는지 센다. */
    private static final class LazyCsvFile implements MultipartFile {
        private final long rows;
        final AtomicLong consumed = new AtomicLong();

        LazyCsvFile(long rows) { this.rows = rows; }

        long total() { return 2 + rows * 2; } // "h\n" + "a\n" × rows

        @Override public InputStream getInputStream() {
            return new InputStream() {
                long pos;
                @Override public int read() {
                    if (pos >= total()) return -1;
                    int b = pos < 2 ? (pos == 0 ? 'h' : '\n') : ((pos % 2 == 0) ? 'a' : '\n');
                    pos++;
                    consumed.incrementAndGet();
                    return b;
                }
            };
        }

        @Override public byte[] getBytes() throws IOException { return getInputStream().readAllBytes(); }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "big.csv"; }
        @Override public String getContentType() { return "text/csv"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return total(); }
        @Override public void transferTo(File dest) { throw new UnsupportedOperationException(); }
        @Override public void transferTo(Path dest) { throw new UnsupportedOperationException(); }
    }

    @Nested
    @DisplayName("CSV")
    class Csv {

        @Test
        @DisplayName("행 상한을 넘는 순간 멈춘다 — 파일 끝까지 읽지 않는다")
        void stopsAtRowLimit() {
            LazyCsvFile file = new LazyCsvFile(1_000_000);

            assertThatThrownBy(() -> FileParser.parse(file))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_TOO_MANY_ROWS);
            // 상한(20만 행) 근처에서 멈췄다 — 버퍼 몇 개 분량을 넘어 끝까지 가지 않는다.
            assertThat(file.consumed.get()).isLessThan(file.total() / 2);
        }

        @Test
        @DisplayName("상한까지는 그대로 읽는다(경계 20만 행)")
        void acceptsExactlyRowLimit() {
            ParsedFile p = FileParser.parse(new LazyCsvFile(200_000));
            assertThat(p.headers()).containsExactly("h");
            assertThat(p.rows()).hasSize(200_000);
        }

        @Test
        @DisplayName("따옴표 안 개행·이스케이프된 따옴표·CRLF·BOM 을 그대로 읽는다")
        void rfc4180() {
            String csv = "﻿날짜,메모\r\n2026-05-28,\"첫 줄\n둘째 줄\"\r\n2026-05-27,\"그가 \"\"안녕\"\"\"\r\n";
            ParsedFile p = FileParser.parse(new MockMultipartFile("file", "t.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)));

            assertThat(p.headers()).containsExactly("날짜", "메모");
            assertThat(p.rows()).containsExactly(
                java.util.List.of("2026-05-28", "첫 줄\n둘째 줄"),
                java.util.List.of("2026-05-27", "그가 \"안녕\""));
        }

        @Test
        @DisplayName("마지막 줄에 개행이 없어도 읽는다")
        void lastLineWithoutNewline() {
            ParsedFile p = FileParser.parse(new MockMultipartFile("file", "t.csv", "text/csv",
                "A,B\n1,2".getBytes(StandardCharsets.UTF_8)));
            assertThat(p.rows()).containsExactly(java.util.List.of("1", "2"));
        }
    }

    // ── xlsx ─────────────────────────────────────────────────

    /** 손으로 짠 최소 xlsx — POI 가 쓰기 단계에서 막는 값(32,767자 넘는 셀 등)을 넣기 위해. */
    private static byte[] minimalXlsx(String sheetData, boolean date1904) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""");
            put(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""");
            put(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <workbookPr date1904="%s"/>
                <sheets><sheet name="s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".formatted(date1904 ? "1" : "0"));
            put(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""");
            // 스타일 0 = 일반, 1 = 내장 날짜 서식 14(m/d/yy)
            put(zip, "xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
                <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
                </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>%s</sheetData></worksheet>"""
                .formatted(sheetData));
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static MockMultipartFile xlsx(byte[] bytes) {
        return new MockMultipartFile("file", "t.xlsx", XLSX_MIME, bytes);
    }

    private static String inline(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }

    @Nested
    @DisplayName("xlsx")
    class Xlsx {

        @Test
        @DisplayName("행 상한을 넘으면 읽는 도중에 거부한다")
        void rejectsOverRowLimit() throws Exception {
            byte[] bytes;
            try (SXSSFWorkbook wb = new SXSSFWorkbook(100); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                Sheet s = wb.createSheet("내역");
                s.createRow(0).createCell(0).setCellValue("금액");
                for (int r = 1; r <= 200_001; r++) s.createRow(r).createCell(0).setCellValue(r);
                wb.write(bos);
                wb.dispose();
                bytes = bos.toByteArray();
            }

            assertThatThrownBy(() -> FileParser.parse(xlsx(bytes)))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_TOO_MANY_ROWS);
        }

        @Test
        @DisplayName("엑셀 셀 한도(32,767자)를 넘는 셀은 거부한다 — 엑셀이 만들 수 없는 파일이다")
        void rejectsOversizedCell() throws Exception {
            String sheet = "<row r=\"1\">" + inline("A1", "메모") + "</row>"
                + "<row r=\"2\">" + inline("A2", "x".repeat(40_000)) + "</row>";

            assertThatThrownBy(() -> FileParser.parse(xlsx(minimalXlsx(sheet, false))))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_PARSE_FAILED);
        }

        @Test
        @DisplayName("압축 해제 한도를 POI 에 건다(항목 하나 200MB)")
        void zipEntryLimitConfigured() throws Exception {
            String sheet = "<row r=\"1\">" + inline("A1", "금액") + "</row>";
            FileParser.parse(xlsx(minimalXlsx(sheet, false)));

            assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(FileParser.MAX_XLSX_ENTRY_BYTES);
        }

        @Test
        @DisplayName("값 규칙이 통합 문서 경로와 같다 — 날짜·소수·수식 결과·불리언·빈 칸·공백 제거·빈 행·둘째 시트")
        void valueRulesMatchWorkbookPath() throws Exception {
            byte[] bytes;
            try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                CreationHelper ch = wb.getCreationHelper();
                CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(ch.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
                Sheet s = wb.createSheet("내역");
                Row h = s.createRow(0);
                h.createCell(0).setCellValue("날짜");
                h.createCell(1).setCellValue("분류");
                h.createCell(2).setCellValue("금액");
                h.createCell(3).setCellValue("합계");
                h.createCell(4).setCellValue("확인");
                h.createCell(6).setCellValue(""); // 끝의 빈 헤더는 버린다
                Row r1 = s.createRow(1);
                r1.createCell(0).setCellValue(LocalDateTime.of(2026, 5, 28, 13, 22));
                r1.getCell(0).setCellStyle(dateStyle);
                r1.createCell(1).setCellValue("  식비  ");
                r1.createCell(2).setCellValue(5700.50);
                r1.createCell(3).setCellFormula("C2*2");
                r1.createCell(4).setCellValue(true);
                s.createRow(2); // 빈 행 — 건너뛴다
                Row r3 = s.createRow(3);
                r3.createCell(2).setCellValue(13460); // 앞 칸 비움
                wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
                wb.createSheet("둘째").createRow(0).createCell(0).setCellValue("무시");
                wb.write(bos);
                bytes = bos.toByteArray();
            }

            ParsedFile p = FileParser.parse(xlsx(bytes));

            assertThat(p.headers()).containsExactly("날짜", "분류", "금액", "합계", "확인");
            assertThat(p.rows()).hasSize(2);
            assertThat(p.rows().get(0)).containsExactly("2026-05-28T13:22:00", "식비", "5700.5", "11401", "true");
            assertThat(p.rows().get(1)).containsExactly("", "", "13460", "", "");
        }

        @Test
        @DisplayName("1904 날짜 체계 파일의 날짜를 4년 밀리지 않게 읽는다 — 서식 없는 숫자는 숫자 그대로")
        void date1904() throws Exception {
            String sheet = "<row r=\"1\">" + inline("A1", "날짜") + inline("B1", "값") + "</row>"
                + "<row r=\"2\"><c r=\"A2\" s=\"1\"><v>1</v></c><c r=\"B2\"><v>45000</v></c></row>";

            assertThat(FileParser.parse(xlsx(minimalXlsx(sheet, true))).rows().get(0))
                .containsExactly("1904-01-02T00:00:00", "45000");
            assertThat(FileParser.parse(xlsx(minimalXlsx(sheet, false))).rows().get(0))
                .containsExactly("1900-01-01T00:00:00", "45000");
        }

        @Test
        @DisplayName("r 속성이 없는 셀은 앞 셀 다음 칸으로 놓는다")
        void cellsWithoutReference() throws Exception {
            String sheet = "<row><c t=\"inlineStr\"><is><t>A</t></is></c><c t=\"inlineStr\"><is><t>B</t></is></c></row>"
                + "<row><c><v>1</v></c><c><v>2</v></c></row>";
            ParsedFile p = FileParser.parse(xlsx(minimalXlsx(sheet, false)));
            assertThat(p.headers()).containsExactly("A", "B");
            assertThat(p.rows().get(0)).containsExactly("1", "2");
        }

        @Test
        @DisplayName("xls(옛 형식)는 통합 문서 경로로 그대로 읽는다")
        void legacyXls() throws Exception {
            byte[] bytes;
            try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                Sheet s = wb.createSheet("내역");
                s.createRow(0).createCell(0).setCellValue("금액");
                s.createRow(1).createCell(0).setCellValue(5700);
                wb.write(bos);
                bytes = bos.toByteArray();
            }
            ParsedFile p = FileParser.parse(new MockMultipartFile("file", "t.xls", "application/vnd.ms-excel", bytes));
            assertThat(p.headers()).containsExactly("금액");
            assertThat(p.rows().get(0)).containsExactly("5700");
        }
    }

    // ── 열 수 · 암호 (보안 점검 DAM-6 잔여) ─────────────────────

    private static MockMultipartFile csvOf(String content) {
        return new MockMultipartFile("file", "t.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("열 수 상한(50)")
    class Columns {

        @Test
        @DisplayName("CSV 머리글이 10만 칸이면 거부한다 — 종전엔 모든 행을 그 폭으로 늘렸다")
        void csvHugeHeaderRejected() {
            String header = String.join(",", java.util.Collections.nCopies(100_000, "h"));
            assertThatThrownBy(() -> FileParser.parse(csvOf(header + "\n1\n2\n")))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_TOO_MANY_COLUMNS);
        }

        @Test
        @DisplayName("머리글 50칸은 받고 51칸은 거부한다. 끝의 빈 머리글은 세지 않는다")
        void boundary() {
            String h50 = String.join(",", java.util.Collections.nCopies(50, "h"));
            assertThat(FileParser.parse(csvOf(h50 + ",,,,\n1\n")).headers()).hasSize(50);
            assertThatThrownBy(() -> FileParser.parse(csvOf(h50 + ",h\n1\n")))
                .isInstanceOf(InvalidValueException.class);
        }

        @Test
        @DisplayName("데이터 행에 칸이 아무리 많아도 머리글 폭으로 자른다")
        void csvWideDataRowTruncated() {
            String row = "1,2" + ",x".repeat(100_000);
            ParsedFile p = FileParser.parse(csvOf("A,B\n" + row + "\n"));
            assertThat(p.rows()).singleElement().isEqualTo(java.util.List.of("1", "2"));
        }

        @Test
        @DisplayName("xlsx 머리글의 먼 열(XFD1)에 값이 있으면 거부한다")
        void xlsxFarHeaderCellRejected() throws Exception {
            String sheet = "<row r=\"1\">" + inline("A1", "금액") + inline("XFD1", "끝") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>1</v></c></row>";
            assertThatThrownBy(() -> FileParser.parse(xlsx(minimalXlsx(sheet, false))))
                .isInstanceOf(InvalidValueException.class)
                .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_TOO_MANY_COLUMNS);
        }

        @Test
        @DisplayName("xlsx 데이터 행의 먼 열은 빈 칸으로 늘리지 않고 버린다 — 열 참조 하나로 1만 6천 칸이 생기던 자리")
        void xlsxFarDataCellIgnored() throws Exception {
            StringBuilder sheet = new StringBuilder("<row r=\"1\">" + inline("A1", "금액") + "</row>");
            for (int r = 2; r <= 2_001; r++) {
                sheet.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\"><v>")
                    .append(r).append("</v></c>").append(inline("XFD" + r, "x")).append("</row>");
            }
            ParsedFile p = FileParser.parse(xlsx(minimalXlsx(sheet.toString(), false)));
            assertThat(p.headers()).containsExactly("금액");
            assertThat(p.rows()).hasSize(2_000).allSatisfy(row -> assertThat(row).hasSize(1));
        }
    }

    @Test
    @DisplayName("암호 걸린 xlsx(엑셀 기본 암호)는 거부한다 — 종전엔 풀어서 통합 문서 전체를 메모리에 올렸다(실행 확인)")
    void encryptedXlsxRejected() throws Exception {
        byte[] plain;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("금액");
            wb.write(b);
            plain = b.toByteArray();
        }
        byte[] encrypted;
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var info = new org.apache.poi.poifs.crypt.EncryptionInfo(org.apache.poi.poifs.crypt.EncryptionMode.agile);
            var enc = info.getEncryptor();
            enc.confirmPassword("VelvetSweatshop");
            try (java.io.OutputStream os = enc.getDataStream(fs)) {
                os.write(plain);
            }
            fs.writeFilesystem(out);
            encrypted = out.toByteArray();
        }

        assertThatThrownBy(() -> FileParser.parse(xlsx(encrypted)))
            .isInstanceOf(InvalidValueException.class)
            .extracting("errorCode").isEqualTo(DeskErrorCode.IMPORT_ENCRYPTED_FILE);
    }
}
