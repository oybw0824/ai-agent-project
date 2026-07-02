package com.nbcb.puller.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceInfo {
    private String instanceId;
    private String ip;
    private int port;
    private double weight;
    private boolean healthy;
    private boolean enabled;
    private Map<String, String> metadata;
    private String serviceName;
    private String clusterName;
}