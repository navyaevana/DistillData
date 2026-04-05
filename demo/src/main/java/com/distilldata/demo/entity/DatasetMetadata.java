package com.distilldata.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "dataset_metadata")
@Data
public class DatasetMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType; // CSV or EXCEL

    @Column(nullable = false)
    private Long originalRowCount;

    @Column(nullable = false)
    private Long cleanedRowCount;

    @Column(nullable = false)
    private LocalDateTime uploadTime;

    @Column(nullable = false)
    private String status; // UPLOADED, PROCESSED, FAILED

    @Lob
    private String analysisResults; // JSON string with analysis data

    @Column
    private String processedFilePath; // Path to the processed file

    @Lob
    private String originalData; // JSON string with original data

    @Lob
    private String cleanedData; // JSON string with cleaned data
}