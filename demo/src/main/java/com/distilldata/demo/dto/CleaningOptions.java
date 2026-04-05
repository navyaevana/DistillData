package com.distilldata.demo.dto;

import lombok.Data;

@Data
public class CleaningOptions {
    private boolean removeNulls = true;
    private boolean removeDuplicates = true;
    private boolean removeEmptyRows = true;
    private boolean trimWhitespace = true;
}
