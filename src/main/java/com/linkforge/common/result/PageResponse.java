package com.linkforge.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;
    private long pages;
}
