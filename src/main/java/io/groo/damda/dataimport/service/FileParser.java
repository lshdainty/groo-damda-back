package io.groo.damda.dataimport.service;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.ExcelNumberFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 업로드 파일(CSV / Excel)을 {@link ParsedFile}(헤더 + 행)로 파싱. static util.
 *
 * <p>CSV 는 RFC4180(따옴표·이스케이프·필드 내 개행) 문자 단위 파서 + UTF-8 BOM 제거.
 * Excel 은 xlsx 를 SAX 로 한 행씩 읽고(xls 는 POI {@link WorkbookFactory}), 날짜 서식 셀은 ISO 문자열로,
 * 숫자 셀은 불필요한 소수 0 을 제거한 평문으로 정규화한다(값 해석은 {@link ValueNormalizer}).
 *
 * <p><b>전부 스트리밍으로 읽으며 행을 센다</b>(보안 점검 DAM-6). 종전엔 CSV 는 파일 전체를 문자열로
 * 올려 모든 행을 리스트로 만든 뒤에, xlsx 는 통합 문서 전체를 메모리 객체로 올린 뒤에 행 수를 봤다.
 * 실측(힙 2GB 테스트 JVM): 개행만 10MB 인 CSV 가 최고 힙 1.26GB, 정상 범위(20만 행 × 8열, 7MB)
 * xlsx 가 1.96GB 를 썼다 — 업로드 상한(10MB) 안의 파일 두세 개로 메모리가 바닥난다.
 */
public final class FileParser {

    private FileParser() {}

    /**
     * 방어적 행 상한 — 초대용량 파일로 인한 OOM 방지.
     *
     * <p>업로드 자체가 {@code spring.servlet.multipart.max-file-size}(기본 10MB)로 이미 제한되지만,
     * 엑셀은 압축돼 있어 같은 용량에 훨씬 많은 행이 들어간다. 그래서 행 수로도 한 겹 막는다.
     * 읽는 도중에 세므로 상한을 넘는 순간 멈춘다.
     *
     * <p>넘으면 <b>조용히 자르지 않고 거부한다.</b> 예전엔 상한까지만 읽고 멈춰서,
     * 사용자는 전부 들어간 줄 알지만 나머지가 통째로 없어졌다.
     */
    private static final int MAX_ROWS = 200_000;

    /**
     * xlsx 안 파일 하나(시트 XML·공유 문자열 등)의 압축 해제 후 최대 크기.
     *
     * <p>근거 — 상한 행 수(20만 행 × 8열, 문자열을 시트에 직접 쓴 최악의 형태)의 시트 XML 이 실측 86MB 다.
     * 열이 더 많은 파일을 위해 두 배 넘게 잡았다. POI 기본값은 4GB 라 업로드 10MB 가 압축 비율 하한(1%)까지
     * 1GB 로 풀려도 막지 않았다. 이 값은 POI 전역 설정이다 — 이 레포에서 xlsx 를 <b>읽는</b> 곳은 여기뿐이다.
     */
    static final long MAX_XLSX_ENTRY_BYTES = 200L * 1024 * 1024;

    /**
     * 열 수 상한 — 머리글 폭과 행 하나에서 받아 두는 칸 수.
     *
     * <p>종전엔 머리글 폭에 제한이 없어 모든 행을 그 폭으로 늘렸다 — 머리글 10만 칸 × 20만 행이면 빈 칸만으로
     * 메모리가 폭발한다. xlsx 는 열 참조({@code XFD} = 16,384번째)만으로 앞 칸을 빈 값으로 채웠다(보안 점검 DAM-6 잔여).
     *
     * <p>근거 — 가져오기 프리셋의 실제 최대 열 수가 10 이다(담다 백업 9 · 뱅크샐러드 10 · 편한가계부 A~G 7 ·
     * 토스 거래내역 10 안쪽). 직접 만든 파일(CUSTOM)을 위해 다섯 배 넉넉히 둔다. 넘으면 조용히 자르지 않고 거부한다.
     */
    static final int MAX_COLUMNS = 50;

    /** 셀 하나의 최대 글자 수 — 엑셀이 셀에 담을 수 있는 한도(32,767자)와 같다. 넘으면 엑셀 파일이 아니다. */
    static final int MAX_CELL_CHARS = 32_767;

    static {
        ZipSecureFile.setMaxEntrySize(MAX_XLSX_ENTRY_BYTES);
    }

    public static ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.IMPORT_EMPTY_FILE);
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            ParsedFile parsed = (name.endsWith(".xlsx") || name.endsWith(".xls"))
                ? parseExcel(file)
                : parseCsv(file);
            if (parsed.headers().isEmpty()) {
                throw new InvalidValueException(DeskErrorCode.IMPORT_EMPTY_FILE);
            }
            return parsed;
        } catch (InvalidValueException e) {
            throw e;
        } catch (SAXException e) {
            // SAX 핸들러 안에서 던진 상한 초과가 감싸여 올 수 있다 — 원래 오류로 돌려준다.
            if (e.getCause() instanceof InvalidValueException ive) throw ive;
            throw new InvalidValueException(DeskErrorCode.IMPORT_PARSE_FAILED);
        } catch (Exception e) {
            throw new InvalidValueException(DeskErrorCode.IMPORT_PARSE_FAILED);
        }
    }

    /**
     * 행을 하나씩 받아 첫 행은 헤더로, 나머지는 헤더 폭에 맞춰 쌓는다. 빈 행은 건너뛰고,
     * 상한을 넘는 순간 거부한다 — CSV·xlsx 가 같은 규칙을 쓴다.
     */
    private static final class RowCollector implements Consumer<List<String>> {
        private List<String> headers;
        private int width;
        private final List<List<String>> rows = new ArrayList<>();

        @Override
        public void accept(List<String> row) {
            if (headers == null) {
                headers = trimTrailingEmpty(row);
                ensureWithinColumnLimit(headers.size());
                width = headers.size();
                return;
            }
            List<String> normalized = normalizeWidth(row, width);
            if (isBlankRow(normalized)) return;
            rows.add(normalized);
            ensureWithinLimit(rows.size());
        }

        ParsedFile result() {
            return headers == null ? new ParsedFile(List.of(), List.of()) : new ParsedFile(headers, rows);
        }
    }

    // ── CSV ──────────────────────────────────────────────────

    private static ParsedFile parseCsv(MultipartFile file) throws IOException {
        RowCollector collector = new RowCollector();
        try (Reader in = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            readCsv(in, collector);
        }
        return collector.result();
    }

    /** RFC4180 문자 단위 파서 — 따옴표 내 콤마/개행 보존, "" → ". 한 행씩 넘긴다. 맨 앞 BOM 은 버린다. */
    static void readCsv(Reader in, Consumer<List<String>> sink) throws IOException {
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean first = true;
        int ch = in.read();
        while (ch != -1) {
            char c = (char) ch;
            if (first) {
                first = false;
                if (c == '\uFEFF') { ch = in.read(); continue; } // BOM
            }
            if (inQuotes) {
                if (c == '"') {
                    int next = in.read();
                    if (next == '"') { field.append('"'); ch = in.read(); }
                    else { inQuotes = false; ch = next; }
                    continue;
                }
                field.append(c);
            } else {
                if (c == '"') { inQuotes = true; }
                else if (c == ',') { addCapped(cur, field); }
                else if (c == '\r') { /* skip */ }
                else if (c == '\n') { addCapped(cur, field); sink.accept(cur); cur = new ArrayList<>(); }
                else { field.append(c); }
            }
            ch = in.read();
        }
        if (field.length() > 0 || !cur.isEmpty()) { addCapped(cur, field); sink.accept(cur); }
    }

    /**
     * 칸을 하나 넣는다 — 행마다 {@link #MAX_COLUMNS} + 1 칸까지만 들고 그 뒤는 버린다. 한 칸을 더 드는 이유는
     * 머리글이 상한을 넘었는지 알아보기 위해서다(데이터 행은 어차피 머리글 폭으로 잘린다).
     */
    private static void addCapped(List<String> cur, StringBuilder field) {
        if (cur.size() <= MAX_COLUMNS) cur.add(field.toString());
        field.setLength(0);
    }

    // ── Excel ────────────────────────────────────────────────

    private static ParsedFile parseExcel(MultipartFile file) throws Exception {
        try (InputStream is = FileMagic.prepareToCheckMagic(file.getInputStream())) {
            return FileMagic.valueOf(is) == FileMagic.OOXML ? parseXlsx(is) : parseWorkbook(is);
        }
    }

    /**
     * xlsx — 시트 XML 을 SAX 로 한 행씩 읽는다. 통합 문서 객체를 만들지 않는다.
     *
     * <p>패키지는 임시 파일로 연다. 스트림으로 열면 POI 가 압축을 푼 항목을 통째로 메모리에 들고 있는다.
     * 파일로 열면 필요한 항목만 그때그때 풀어 읽고, 그 읽기에 {@link ZipSecureFile} 의 한도(압축 비율·항목 크기)가 걸린다.
     */
    private static ParsedFile parseXlsx(InputStream is) throws Exception {
        Path tmp = Files.createTempFile("damda-import-", ".xlsx");
        try {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            try (OPCPackage pkg = OPCPackage.open(tmp.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pkg);
                StylesTable styles = reader.getStylesTable();
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
                boolean date1904;
                try (InputStream wb = reader.getWorkbookData()) {
                    date1904 = readDate1904(wb);
                }
                RowCollector collector = new RowCollector();
                Iterator<InputStream> sheets = reader.getSheetsData();
                if (sheets.hasNext()) {
                    try (InputStream sheet = sheets.next()) {
                        XMLReader xml = XMLHelper.newXMLReader();
                        xml.setContentHandler(new SheetHandler(styles, strings, date1904, collector));
                        xml.parse(new InputSource(sheet));
                    }
                }
                return collector.result();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 통합 문서 설정의 1904 날짜 체계 여부 — 맥용 옛 엑셀 파일. 날짜 셀 해석이 4년 어긋나지 않게. */
    private static boolean readDate1904(InputStream workbookXml) throws Exception {
        boolean[] date1904 = {false};
        XMLReader xml = XMLHelper.newXMLReader();
        xml.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                if ("workbookPr".equals(localName)) {
                    String v = attrs.getValue("date1904");
                    date1904[0] = "1".equals(v) || "true".equalsIgnoreCase(v);
                }
            }
        });
        xml.parse(new InputSource(workbookXml));
        return date1904[0];
    }

    /**
     * 시트 XML 한 행씩 — {@code <row>} 안의 {@code <c>} 를 열 위치에 맞춰 문자열로 바꿔 넘긴다.
     * 값 규칙은 통합 문서 경로의 {@link #cellToString(Cell)} 과 같다.
     */
    private static final class SheetHandler extends DefaultHandler {
        private final StylesTable styles;
        private final ReadOnlySharedStringsTable strings;
        private final boolean date1904;
        private final Consumer<List<String>> sink;

        private List<String> cells;
        private int nextCol;
        private int col;
        private String type;
        private String style;
        private final StringBuilder value = new StringBuilder();
        private boolean collecting;
        private boolean inInline;
        private boolean inPhonetic;

        SheetHandler(StylesTable styles, ReadOnlySharedStringsTable strings, boolean date1904,
                     Consumer<List<String>> sink) {
            this.styles = styles;
            this.strings = strings;
            this.date1904 = date1904;
            this.sink = sink;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            switch (localName) {
                case "row" -> { cells = new ArrayList<>(); nextCol = 0; }
                case "c" -> {
                    String ref = attrs.getValue("r");
                    col = ref != null ? new CellReference(ref).getCol() : nextCol;
                    type = attrs.getValue("t");
                    style = attrs.getValue("s");
                    value.setLength(0);
                }
                case "v" -> collecting = true;
                case "is" -> inInline = true;
                case "rPh" -> inPhonetic = true;
                case "t" -> collecting = inInline && !inPhonetic;
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!collecting) return;
            if (value.length() + length > MAX_CELL_CHARS) {
                throw new InvalidValueException(DeskErrorCode.IMPORT_PARSE_FAILED);
            }
            value.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v", "t" -> collecting = false;
                case "is" -> inInline = false;
                case "rPh" -> inPhonetic = false;
                case "c" -> {
                    if (cells == null) return;
                    nextCol = col + 1;
                    String v = cellValue();
                    // 상한 밖 열은 빈 칸으로 채워 가며 늘리지 않는다 — 열 참조 하나(XFD1)로 1만 6천 칸이 생겼다.
                    // 값이 있으면 상한 + 1 번째 칸에 표시만 남겨 머리글이 넘쳤는지 알아본다(데이터 행은 머리글 폭으로 잘린다).
                    int at = col;
                    if (at > MAX_COLUMNS) {
                        if (v.isBlank()) return;
                        at = MAX_COLUMNS;
                    }
                    while (cells.size() < at) cells.add("");
                    if (cells.size() == at) cells.add(v);
                    else cells.set(at, v);
                }
                case "row" -> {
                    if (cells != null) sink.accept(cells);
                    cells = null;
                }
                default -> { }
            }
        }

        private String cellValue() {
            String raw = value.toString();
            if (type == null || "n".equals(type)) {
                if (raw.isEmpty()) return "";
                return numericToString(Double.parseDouble(raw), numberFormat(), date1904);
            }
            return switch (type) {
                case "s" -> raw.isEmpty() ? "" : sharedString(Integer.parseInt(raw));
                case "inlineStr", "str" -> raw.trim();
                case "b" -> raw.isEmpty() ? "" : String.valueOf(!"0".equals(raw));
                default -> ""; // e(오류) 등 — 통합 문서 경로도 빈 칸이다
            };
        }

        /** 공유 문자열도 셀 한도를 넘으면 거부한다 — 시트 XML 의 셀과 같은 규칙. */
        private String sharedString(int idx) {
            String text = strings.getItemAt(idx).getString();
            if (text.length() > MAX_CELL_CHARS) {
                throw new InvalidValueException(DeskErrorCode.IMPORT_PARSE_FAILED);
            }
            return text.trim();
        }

        private ExcelNumberFormat numberFormat() {
            if (styles == null) return null;
            XSSFCellStyle st = style != null
                ? styles.getStyleAt(Integer.parseInt(style))
                : (styles.getNumCellStyles() > 0 ? styles.getStyleAt(0) : null);
            return st == null ? null : ExcelNumberFormat.from(st);
        }
    }

    /** xls(OLE2) — 압축이 없어 업로드 상한(10MB)이 곧 메모리 상한이다. 통합 문서로 읽는다. */
    private static ParsedFile parseWorkbook(InputStream is) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(is)) {
            // 암호 걸린 xlsx 는 OLE2 봉투(EncryptedPackage)에 담겨 이 경로로 온다. WorkbookFactory 는 엑셀 기본 암호
            // ("VelvetSweatshop")로 풀어 통합 문서 전체를 메모리에 올린다 — 스트리밍 · 항목 크기 한도를 모두 비켜 간다
            // (실행으로 확인, 보안 점검 DAM-6 잔여). 암호 걸린 파일은 받지 않는다.
            if (fs.getRoot().hasEntry("EncryptedPackage")) {
                throw new InvalidValueException(DeskErrorCode.IMPORT_ENCRYPTED_FILE);
            }
            return parseWorkbook(fs);
        }
    }

    private static ParsedFile parseWorkbook(POIFSFileSystem fs) throws Exception {
        try (Workbook wb = WorkbookFactory.create(fs)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return new ParsedFile(List.of(), List.of());
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return new ParsedFile(List.of(), List.of());

            int width = headerRow.getLastCellNum();
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < width; c++) headers.add(cellToString(headerRow.getCell(c)));
            headers = trimTrailingEmpty(headers);
            ensureWithinColumnLimit(headers.size());
            int w = headers.size();

            List<List<String>> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int r = sheet.getFirstRowNum() + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < w; c++) cells.add(cellToString(row.getCell(c)));
                if (isBlankRow(cells)) continue;
                rows.add(cells);
                ensureWithinLimit(rows.size());
            }
            return new ParsedFile(headers, rows);
        }
    }

    static String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> numericToString(cell);
            case FORMULA -> {
                try { yield numericToString(cell); }
                catch (Exception e) {
                    try { yield cell.getStringCellValue().trim(); } catch (Exception e2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    private static String numericToString(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        double d = cell.getNumericCellValue();
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** 스트리밍 경로의 숫자 셀 — {@link #numericToString(Cell)} 과 같은 판정(유효한 날짜값 + 날짜 서식). */
    private static String numericToString(double d, ExcelNumberFormat format, boolean date1904) {
        if (format != null && DateUtil.isValidExcelDate(d) && DateUtil.isADateFormat(format)) {
            return DateUtil.getLocalDateTime(d, date1904).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ── 공통 ─────────────────────────────────────────────────

    private static void ensureWithinColumnLimit(int columns) {
        if (columns > MAX_COLUMNS) {
            throw new InvalidValueException(DeskErrorCode.IMPORT_TOO_MANY_COLUMNS);
        }
    }

    /** 상한 초과는 조용히 자르지 않고 거부한다 — 잘린 줄 모르고 넘어가는 게 더 위험하다. */
    private static void ensureWithinLimit(int rowCount) {
        if (rowCount > MAX_ROWS) {
            throw new InvalidValueException(DeskErrorCode.IMPORT_TOO_MANY_ROWS);
        }
    }

    private static List<String> normalizeWidth(List<String> row, int width) {
        List<String> out = new ArrayList<>(width);
        for (int i = 0; i < width; i++) out.add(i < row.size() ? row.get(i) : "");
        return out;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String c : row) if (c != null && !c.isBlank()) return false;
        return true;
    }

    /** 헤더 끝의 빈 열 제거(엑셀 trailing 빈 헤더 방어). 중간 빈 헤더는 유지. */
    private static List<String> trimTrailingEmpty(List<String> headers) {
        int end = headers.size();
        while (end > 0 && (headers.get(end - 1) == null || headers.get(end - 1).isBlank())) end--;
        return new ArrayList<>(headers.subList(0, end));
    }
}
