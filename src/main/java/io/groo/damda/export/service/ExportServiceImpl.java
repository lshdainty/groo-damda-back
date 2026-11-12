package io.groo.damda.export.service;

import io.groo.core.exception.InvalidValueException;
import io.groo.damda.common.exception.DeskErrorCode;

import io.groo.damda.export.controller.dto.ExportApiDto;
import io.groo.damda.export.type.ExportFormat;
import io.groo.damda.export.type.ExportType;
import io.groo.core.time.UserClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private static final int PREVIEW_LIMIT = 10;

    private final PeriodResolver periodResolver;
    private final UserClock userClock;
    private final ExportDataService dataService;

    @Override
    public ExportDescriptor describe(ExportApiDto.ExportRequest req, Long userRowId) {
        List<ExportType> types = orderedTypes(req.types());
        PeriodResolver.DateRange range = periodResolver.resolve(req.period(), req.startDate(), req.endDate(), userClock.today(userRowId));
        String rangePart = range.start() + "_" + range.end();
        ExportFormat format = req.format();

        boolean zip = format != ExportFormat.EXCEL && types.size() > 1;
        String filename;
        String contentType;
        if (zip) {
            filename = "groo-export-" + rangePart + ".zip";
            contentType = "application/zip";
        } else {
            String namePart = types.size() == 1 ? types.get(0).slug() : "export";
            filename = "groo-" + namePart + "-" + rangePart + "." + format.extension();
            contentType = format.contentType();
        }
        return new ExportDescriptor(filename, contentType);
    }

    @Override
    @Transactional(readOnly = true)
    public void writeExport(OutputStream out, ExportApiDto.ExportRequest req, Long userRowId) throws IOException {
        List<ExportType> types = orderedTypes(req.types());
        PeriodResolver.DateRange range = periodResolver.resolve(req.period(), req.startDate(), req.endDate(), userClock.today(userRowId));

        List<ExportTable> tables = new ArrayList<>();
        for (ExportType type : types) {
            tables.add(dataService.buildTable(type, userRowId, range.start(), range.end(), req.mask()));
        }

        ExportFormat format = req.format();
        if (format == ExportFormat.EXCEL) {
            // Excel 은 항상 1파일 종류별 시트 (ZIP 안 씀).
            ExportFileWriter.writeExcel(out, tables);
            return;
        }

        if (tables.size() == 1) {
            writeSingle(out, format, tables.get(0));
            return;
        }

        // CSV/JSON 다종 → 종류별 파일 ZIP.
        String rangePart = range.start() + "_" + range.end();
        ZipOutputStream zos = new ZipOutputStream(out);
        for (ExportTable table : tables) {
            String entryName = "groo-" + table.type().slug() + "-" + rangePart + "." + format.extension();
            zos.putNextEntry(new ZipEntry(entryName));
            writeSingle(zos, format, table);
            zos.closeEntry();
        }
        zos.finish();
        zos.flush();
    }

    private void writeSingle(OutputStream out, ExportFormat format, ExportTable table) throws IOException {
        if (format == ExportFormat.JSON) {
            ExportFileWriter.writeJson(out, table);
        } else {
            ExportFileWriter.writeCsv(out, table);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ExportApiDto.CountResponse counts(ExportApiDto.CountRequest req, Long userRowId) {
        List<ExportType> types = orderedTypes(req.types());
        PeriodResolver.DateRange range = periodResolver.resolve(req.period(), req.startDate(), req.endDate(), userClock.today(userRowId));
        List<ExportApiDto.TypeCount> counts = new ArrayList<>();
        for (ExportType type : types) {
            long c = dataService.count(type, userRowId, range.start(), range.end());
            counts.add(new ExportApiDto.TypeCount(type.slug(), type.displayName(), c));
        }
        return new ExportApiDto.CountResponse(counts);
    }

    @Override
    @Transactional(readOnly = true)
    public ExportApiDto.PreviewResponse preview(ExportApiDto.PreviewRequest req, Long userRowId) {
        List<ExportType> types = orderedTypes(req.types());
        PeriodResolver.DateRange range = periodResolver.resolve(req.period(), req.startDate(), req.endDate(), userClock.today(userRowId));
        List<ExportApiDto.PreviewTable> tables = new ArrayList<>();
        for (ExportType type : types) {
            ExportTable table = dataService.buildTable(type, userRowId, range.start(), range.end(), false);
            int total = table.rows().size();
            List<List<String>> rows = table.rows().subList(0, Math.min(PREVIEW_LIMIT, total));
            tables.add(new ExportApiDto.PreviewTable(
                type.slug(), type.displayName(), table.headers(), new ArrayList<>(rows), total));
        }
        return new ExportApiDto.PreviewResponse(tables);
    }

    /** 요청 종 dedup + 선언 순서 정렬. 비어있으면 예외. */
    private List<ExportType> orderedTypes(List<ExportType> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new InvalidValueException(DeskErrorCode.EXPORT_TYPE_REQUIRED);
        }
        LinkedHashSet<ExportType> set = new LinkedHashSet<>(requested);
        List<ExportType> ordered = new ArrayList<>();
        for (ExportType t : ExportType.values()) {
            if (set.contains(t)) ordered.add(t);
        }
        return ordered;
    }
}
