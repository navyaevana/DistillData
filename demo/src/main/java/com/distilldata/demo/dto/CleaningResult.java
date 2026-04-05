package com.distilldata.demo.dto;

import lombok.Data;

@Data
public class CleaningResult {
    private Long datasetId;
    private Long originalRowCount;
    private Long cleanedRowCount;
    private Long duplicatesRemoved;
    private Long nullRowsRemoved;
    private String originalData;
    private String cleanedData;
    private String analysisResults;
}
