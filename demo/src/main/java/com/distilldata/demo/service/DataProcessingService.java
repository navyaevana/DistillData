package com.distilldata.demo.service;

import com.distilldata.demo.entity.DatasetMetadata;
import com.distilldata.demo.repository.DatasetMetadataRepository;
import com.distilldata.demo.dto.CleaningOptions;
import com.distilldata.demo.dto.CleaningResult;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataProcessingService {

    @Autowired
    private DatasetMetadataRepository metadataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatasetMetadata processFile(MultipartFile file) throws Exception {
        DatasetMetadata metadata = new DatasetMetadata();
        metadata.setFileName(file.getOriginalFilename());
        metadata.setUploadTime(LocalDateTime.now());
        metadata.setStatus("PROCESSING");

        String fileType = determineFileType(file.getOriginalFilename());
        metadata.setFileType(fileType);

        List<String[]> data = readFile(file, fileType);
        metadata.setOriginalRowCount((long) data.size());

        // Store original data as JSON
        metadata.setOriginalData(convertToJson(data));

        // Clean data
        List<String[]> cleanedData = cleanData(data);
        metadata.setCleanedRowCount((long) cleanedData.size());

        // Store cleaned data as JSON
        metadata.setCleanedData(convertToJson(cleanedData));

        // Analyze data
        String analysis = analyzeData(cleanedData);
        metadata.setAnalysisResults(analysis);

        // Save processed file
        String processedFilePath = saveProcessedFile(cleanedData, file.getOriginalFilename(), fileType);
        metadata.setProcessedFilePath(processedFilePath);

        metadata.setStatus("PROCESSED");
        return metadataRepository.save(metadata);
    }

    public List<DatasetMetadata> getAllDatasets() {
        return metadataRepository.findAll();
    }

    public DatasetMetadata getDatasetById(Long id) {
        return metadataRepository.findById(id).orElse(null);
    }

    public CleaningResult cleanDataset(Long datasetId, CleaningOptions options) throws Exception {
        DatasetMetadata metadata = getDatasetById(datasetId);
        if (metadata == null) {
            throw new Exception("Dataset not found");
        }

        // Parse original data
        List<String[]> originalData = parseJsonData(metadata.getOriginalData());
        
        // Apply selective cleaning
        CleaningStats stats = new CleaningStats();
        List<String[]> cleanedData = applySelectiveCleaning(originalData, options, stats);

        // Update metadata with new cleaning results
        metadata.setCleanedData(convertToJson(cleanedData));
        metadata.setCleanedRowCount((long) cleanedData.size());

        // Analyze cleaned data
        String analysis = analyzeData(cleanedData);
        metadata.setAnalysisResults(analysis);

        // Save processed file
        String fileType = metadata.getFileType();
        String processedFilePath = saveProcessedFile(cleanedData, metadata.getFileName(), fileType);
        metadata.setProcessedFilePath(processedFilePath);
        metadataRepository.save(metadata);

        // Build result
        CleaningResult result = new CleaningResult();
        result.setDatasetId(datasetId);
        result.setOriginalRowCount((long) originalData.size());
        result.setCleanedRowCount((long) cleanedData.size());
        result.setDuplicatesRemoved(stats.duplicatesRemoved);
        result.setNullRowsRemoved(stats.nullRowsRemoved);
        result.setOriginalData(metadata.getOriginalData());
        result.setCleanedData(convertToJson(cleanedData));
        result.setAnalysisResults(analysis);

        return result;
    }

    private String determineFileType(String filename) {
        if (filename.toLowerCase().endsWith(".csv")) {
            return "CSV";
        } else if (filename.toLowerCase().endsWith(".xlsx") || filename.toLowerCase().endsWith(".xls")) {
            return "EXCEL";
        }
        throw new IllegalArgumentException("Unsupported file type");
    }

    private List<String[]> readFile(MultipartFile file, String fileType) throws Exception {
        if ("CSV".equals(fileType)) {
            return readCSV(file);
        } else {
            return readExcel(file);
        }
    }

    private List<String[]> readCSV(MultipartFile file) throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            return reader.readAll();
        }
    }

    private List<String[]> readExcel(MultipartFile file) throws IOException {
        Workbook workbook;
        if (file.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
            workbook = new XSSFWorkbook(file.getInputStream());
        } else {
            // For .xls files, use HSSFWorkbook
            workbook = new XSSFWorkbook(file.getInputStream()); // Fallback to XSSF for now
        }
        Sheet sheet = workbook.getSheetAt(0);
        List<String[]> data = new ArrayList<>();

        for (Row row : sheet) {
            List<String> rowData = new ArrayList<>();
            for (Cell cell : row) {
                rowData.add(getCellValueAsString(cell));
            }
            data.add(rowData.toArray(new String[0]));
        }
        workbook.close();
        return data;
    }

    private String getCellValueAsString(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    private List<String[]> cleanData(List<String[]> data) {
        if (data.isEmpty()) return data;

        // Remove rows with all null/empty values
        data = data.stream()
                .filter(row -> Arrays.stream(row).anyMatch(cell -> cell != null && !cell.trim().isEmpty()))
                .collect(Collectors.toList());

        // Remove duplicate rows (more robust comparison)
        Set<String> seen = new HashSet<>();
        data = data.stream()
                .filter(row -> {
                    // Create a normalized string representation for comparison
                    String rowStr = Arrays.stream(row)
                            .map(cell -> cell == null ? "" : cell.trim())
                            .collect(Collectors.joining("|"));
                    return seen.add(rowStr);
                })
                .collect(Collectors.toList());

        return data;
    }

    private String analyzeData(List<String[]> data) {
        if (data.size() <= 1) return "{}"; // No data or only header

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("totalRows", data.size() - 1); // Excluding header
        analysis.put("totalColumns", data.get(0).length);

        // Basic column analysis
        List<Map<String, Object>> columnAnalysis = new ArrayList<>();
        String[] headers = data.get(0);
        for (int i = 0; i < headers.length; i++) {
            Map<String, Object> colInfo = new HashMap<>();
            colInfo.put("name", headers[i]);
            List<String> values = new ArrayList<>();
            for (int j = 1; j < data.size(); j++) {
                if (i < data.get(j).length) {
                    values.add(data.get(j)[i]);
                }
            }
            colInfo.put("nonNullCount", values.stream().filter(v -> v != null && !v.trim().isEmpty()).count());
            colInfo.put("nullCount", values.size() - (long) colInfo.get("nonNullCount"));

            // Try to detect numeric columns and calculate basic stats
            List<Double> numericValues = values.stream()
                    .filter(v -> v != null && !v.trim().isEmpty())
                    .map(v -> {
                        try {
                            return Double.parseDouble(v);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (!numericValues.isEmpty()) {
                colInfo.put("isNumeric", true);
                colInfo.put("min", numericValues.stream().min(Comparator.naturalOrder()).orElse(0.0));
                colInfo.put("max", numericValues.stream().max(Comparator.naturalOrder()).orElse(0.0));
                colInfo.put("average", numericValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
            } else {
                colInfo.put("isNumeric", false);
            }

            columnAnalysis.add(colInfo);
        }
        analysis.put("columns", columnAnalysis);

        // Convert to JSON string
        try {
            return objectMapper.writeValueAsString(analysis);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String saveProcessedFile(List<String[]> data, String originalFilename, String fileType) throws IOException {
        String baseName = originalFilename.substring(0, originalFilename.lastIndexOf('.'));
        String extension = "CSV".equals(fileType) ? ".csv" : ".xlsx";
        String processedFilename = baseName + "_processed" + extension;

        Path uploadDir = Paths.get("uploads");
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
        Path filePath = uploadDir.resolve(processedFilename);

        if ("CSV".equals(fileType)) {
            try (CSVWriter writer = new CSVWriter(new FileWriter(filePath.toFile()))) {
                writer.writeAll(data);
            }
        } else {
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("Processed Data");

            for (int i = 0; i < data.size(); i++) {
                Row row = sheet.createRow(i);
                String[] rowData = data.get(i);
                for (int j = 0; j < rowData.length; j++) {
                    Cell cell = row.createCell(j);
                    cell.setCellValue(rowData[j]);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                workbook.write(fos);
            }
            workbook.close();
        }

        return filePath.toString();
    }

    private String convertToJson(List<String[]> data) {
        try {
            List<List<String>> dataList = new ArrayList<>();
            for (String[] row : data) {
                dataList.add(Arrays.asList(row));
            }
            return objectMapper.writeValueAsString(dataList);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String[]> applySelectiveCleaning(List<String[]> data, CleaningOptions options, CleaningStats stats) {
        if (data.isEmpty()) return data;

        List<String[]> result = new ArrayList<>(data);
        String[] headers = result.isEmpty() ? new String[0] : result.get(0);

        // Step 1: Apply trim whitespace
        if (options.isTrimWhitespace()) {
            result = result.stream()
                    .map(row -> Arrays.stream(row)
                            .map(cell -> cell == null ? "" : cell.trim())
                            .toArray(String[]::new))
                    .collect(Collectors.toList());
        }

        // Step 2: Normalize common null patterns
        result = normalizeNullPatterns(result);

        // Step 3: Handle missing values (Mean, Median, Mode imputation)
        if (options.isRemoveNulls()) {
            result = imputeMissingValues(result, stats);
        }

        // Step 4: Remove completely empty rows
        if (options.isRemoveEmptyRows()) {
            long beforeSize = result.size();
            List<String[]> nonEmptyRows = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                if (i == 0) {
                    nonEmptyRows.add(result.get(i)); // Keep header
                } else {
                    String[] row = result.get(i);
                    if (Arrays.stream(row).anyMatch(cell -> cell != null && !cell.isEmpty() && !cell.equals("N/A"))) {
                        nonEmptyRows.add(row);
                    }
                }
            }
            stats.nullRowsRemoved += (beforeSize - nonEmptyRows.size());
            result = nonEmptyRows;
        }

        // Step 5: Remove duplicate rows (excluding header)
        if (options.isRemoveDuplicates()) {
            long beforeSize = result.size();
            List<String[]> uniqueRows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            
            for (int i = 0; i < result.size(); i++) {
                String[] row = result.get(i);
                if (i == 0) {
                    uniqueRows.add(row); // Always keep header
                    continue;
                }
                
                // Create normalized string for duplicate detection
                String rowStr = Arrays.stream(row)
                        .map(cell -> cell == null ? "NULL" : cell.trim().toUpperCase())
                        .collect(Collectors.joining("|"));
                
                if (seen.add(rowStr)) {
                    uniqueRows.add(row);
                }
            }
            stats.duplicatesRemoved = beforeSize - uniqueRows.size();
            result = uniqueRows;
        }

        return result;
    }

    private List<String[]> normalizeNullPatterns(List<String[]> data) {
        return data.stream()
                .map(row -> Arrays.stream(row)
                        .map(cell -> {
                            if (cell == null) return "";
                            String normalized = cell.trim();
                            // Replace common null patterns with empty string
                            if (normalized.isEmpty() || 
                                normalized.equalsIgnoreCase("null") || 
                                normalized.equalsIgnoreCase("n/a") ||
                                normalized.equalsIgnoreCase("na") ||
                                normalized.equalsIgnoreCase("none") ||
                                normalized.equalsIgnoreCase("unknown") ||
                                normalized.equals("-") ||
                                normalized.equals("--") ||
                                normalized.equals("---")) {
                                return "";
                            }
                            return normalized;
                        })
                        .toArray(String[]::new))
                .collect(Collectors.toList());
    }

    private List<String[]> imputeMissingValues(List<String[]> data, CleaningStats stats) {
        if (data.size() <= 1) return data; // No data rows (only header)

        String[] headers = data.get(0);
        int columnCount = headers.length;

        // Analyze each column to determine if it's numeric and calculate imputation values
        Map<Integer, ColumnStats> columnStats = new HashMap<>();
        
        for (int col = 0; col < columnCount; col++) {
            ColumnStats stats_col = analyzeColumn(data, col);
            columnStats.put(col, stats_col);
        }

        // Apply imputation
        List<String[]> result = new ArrayList<>();
        result.add(headers); // Add header

        for (int i = 1; i < data.size(); i++) {
            String[] row = data.get(i);
            String[] imputedRow = new String[columnCount];

            for (int col = 0; col < columnCount; col++) {
                String value = col < row.length ? row[col] : "";
                
                // Check if value is missing or empty
                if (value == null || value.trim().isEmpty()) {
                    ColumnStats colStats = columnStats.get(col);
                    
                    if (colStats.isNumeric && !colStats.meanValue.equals("0")) {
                        // Use Mean imputation for numeric columns
                        imputedRow[col] = colStats.meanValue;
                    } else if (!colStats.isNumeric && !colStats.modeValue.isEmpty()) {
                        // Use Mode imputation for categorical columns
                        imputedRow[col] = colStats.modeValue;
                    } else {
                        // Default value
                        imputedRow[col] = "N/A";
                    }
                    stats.nullRowsRemoved++;
                } else {
                    imputedRow[col] = value.trim();
                }
            }
            result.add(imputedRow);
        }

        return result;
    }

    private ColumnStats analyzeColumn(List<String[]> data, int columnIndex) {
        ColumnStats stats = new ColumnStats();
        List<Double> numericValues = new ArrayList<>();
        Map<String, Integer> categoricalFrequency = new HashMap<>();
        int totalNonEmpty = 0;
        int totalNumeric = 0;

        // Collect non-null values
        for (int i = 1; i < data.size(); i++) {
            String[] row = data.get(i);
            String value = (columnIndex < row.length && row[columnIndex] != null) ? row[columnIndex].trim() : "";

            if (!value.isEmpty()) {
                totalNonEmpty++;
                // Try to parse as numeric
                try {
                    double numVal = Double.parseDouble(value);
                    numericValues.add(numVal);
                    totalNumeric++;
                } catch (NumberFormatException e) {
                    // Track frequency for mode calculation
                    categoricalFrequency.put(value, categoricalFrequency.getOrDefault(value, 0) + 1);
                }
            }
        }

        // Determine if column is numeric or categorical
        // If more than 70% of values are numeric, treat as numeric
        if (totalNonEmpty > 0 && totalNumeric >= (totalNonEmpty * 0.7)) {
            stats.isNumeric = true;
        } else {
            stats.isNumeric = false;
        }

        // Calculate mean for numeric columns
        if (stats.isNumeric && !numericValues.isEmpty()) {
            double sum = numericValues.stream().mapToDouble(Double::doubleValue).sum();
            double mean = sum / numericValues.size();
            stats.meanValue = String.format("%.2f", mean);
        } else {
            stats.meanValue = "0";
        }

        // Find mode (most frequent value) for categorical columns
        if (!categoricalFrequency.isEmpty()) {
            stats.modeValue = categoricalFrequency.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse("");
        }

        return stats;
    }

    private List<String[]> parseJsonData(String jsonData) throws Exception {
        if (jsonData == null || jsonData.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<String>> dataList = objectMapper.readValue(jsonData, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, List.class));
        return dataList.stream()
                .map(list -> list.stream().map(Object::toString).toArray(String[]::new))
                .collect(Collectors.toList());
    }

    private static class CleaningStats {
        long duplicatesRemoved = 0;
        long nullRowsRemoved = 0;
    }

    private static class ColumnStats {
        boolean isNumeric = true;
        String meanValue = "0";
        String modeValue = "";
    }
}