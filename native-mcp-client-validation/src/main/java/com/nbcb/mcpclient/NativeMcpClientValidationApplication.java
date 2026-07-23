package com.nbcb.mcpclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 原生 MCP Client 验证应用。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NativeMcpClientValidationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NativeMcpClientValidationApplication.class, args);
    }
}
