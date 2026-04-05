package com.distilldata.demo.controller;

import com.distilldata.demo.entity.DatasetMetadata;
import com.distilldata.demo.service.DataProcessingService;
import com.distilldata.demo.dto.CleaningOptions;
import com.distilldata.demo.dto.CleaningResult;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173", "http://localhost:5174", "http://localhost:5175", "http://localhost:5176"})
public class DataProcessingController {

    private final DataProcessingService dataProcessingService;

    @PostMapping("/upload")
    public ResponseEntity<DatasetMetadata> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            DatasetMetadata metadata = dataProcessingService.processFile(file);
            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/datasets")
    public ResponseEntity<List<DatasetMetadata>> getAllDatasets() {
        List<DatasetMetadata> datasets = dataProcessingService.getAllDatasets();
        return ResponseEntity.ok(datasets);
    }

    @GetMapping("/datasets/{id}")
    public ResponseEntity<DatasetMetadata> getDatasetById(@PathVariable Long id) {
        DatasetMetadata metadata = dataProcessingService.getDatasetById(id);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metadata);
    }

    @PostMapping("/clean/{id}")
    public ResponseEntity<CleaningResult> cleanDataset(@PathVariable Long id, @RequestBody CleaningOptions options) {
        try {
            CleaningResult result = dataProcessingService.cleanDataset(id, options);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadProcessedFile(@PathVariable Long id) {
        try {
            DatasetMetadata metadata = dataProcessingService.getDatasetById(id);
            if (metadata == null || metadata.getProcessedFilePath() == null) {
                return ResponseEntity.notFound().build();
            }

            Path filePath = Paths.get(metadata.getProcessedFilePath());
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);

            // Create a proper filename for download
            String originalName = metadata.getFileName();
            String baseName = originalName.substring(0, originalName.lastIndexOf('.'));
            String extension = originalName.substring(originalName.lastIndexOf('.'));
            String downloadFilename = baseName + "_processed" + extension;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFilename + "\"")
                    .contentType("CSV".equals(metadata.getFileType()) ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
