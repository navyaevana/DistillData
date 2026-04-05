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

        // Apply trim whitespace
        if (options.isTrimWhitespace()) {
            result = result.stream()
                    .map(row -> Arrays.stream(row)
                            .map(cell -> cell == null ? "" : cell.trim())
                            .toArray(String[]::new))
                    .collect(Collectors.toList());
        }

        // Remove empty rows
        if (options.isRemoveEmptyRows()) {
            long beforeSize = result.size();
            result = result.stream()
                    .filter(row -> Arrays.stream(row).anyMatch(cell -> cell != null && !cell.isEmpty()))
                    .collect(Collectors.toList());
            stats.nullRowsRemoved += (beforeSize - result.size());
        }

        // Remove rows with all null/empty values
        if (options.isRemoveNulls()) {
            long beforeSize = result.size();
            result = result.stream()
                    .filter(row -> Arrays.stream(row).anyMatch(cell -> cell != null && !cell.isEmpty()))
                    .collect(Collectors.toList());
            stats.nullRowsRemoved += (beforeSize - result.size());
        }

        // Remove duplicates
        if (options.isRemoveDuplicates()) {
            long beforeSize = result.size();
            Set<String> seen = new HashSet<>();
            result = result.stream()
                    .filter(row -> {
                        String rowStr = Arrays.stream(row)
                                .collect(Collectors.joining("|"));
                        return seen.add(rowStr);
                    })
                    .collect(Collectors.toList());
            stats.duplicatesRemoved = beforeSize - result.size();
        }

        return result;
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
}