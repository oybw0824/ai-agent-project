package com.nbcb.puller.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NacosServiceInfo {
    private String serviceName;
    private String groupName;
    private Integer clusterCount;
    private Integer ipCount;
    private Integer healthyInstanceCount;
    private List<InstanceInfo> instances;
    private Map<String, String> metadata;
}