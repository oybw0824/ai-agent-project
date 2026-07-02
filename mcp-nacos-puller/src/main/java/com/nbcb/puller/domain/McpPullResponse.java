package com.nbcb.puller.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpPullResponse {
    private boolean success;
    private String message;
    private int totalServiceCount;
    private int totalInstanceCount;
    private List<McpServiceSummary> services;
}