package com.nbcb.agent.governance.config;

import com.nbcb.agent.governance.aop.AgentGovernanceAopInterceptor;
import com.nbcb.agent.governance.interceptor.AgentGovernanceApiInterceptor;
import com.nbcb.agent.governance.mapper.AgentGovernanceMapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ★ Agent 治理组件自动装配 — 仅当 MyBatis-Plus 在 classpath 时激活
 *
 * @author com.nbcb
 */
@AutoConfiguration
@AutoConfigureAfter(MybatisPlusAutoConfiguration.class)
@EnableAspectJAutoProxy
@ConditionalOnClass(name = {
        "com.baomidou.mybatisplus.core.mapper.BaseMapper",
        "org.springframework.web.servlet.config.annotation.WebMvcConfigurer",
        "jakarta.servlet.http.HttpServletRequest"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "agent-governance", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackageClasses = AgentGovernanceMapper.class)
public class AgentGovernanceAutoConfiguration {

    @Bean
    public AgentGovernanceManager agentGovernanceManager(AgentGovernanceMapper mapper) {
        return new AgentGovernanceManager(mapper);
    }

    @Bean
    @ConditionalOnWebApplication
    public AgentGovernanceApiInterceptor agentGovernanceApiInterceptor(AgentGovernanceManager mgr) {
        return new AgentGovernanceApiInterceptor(mgr);
    }

    @Bean
    @ConditionalOnClass(name = "com.alibaba.cloud.ai.graph.agent.ReactAgent")
    public AgentGovernanceAopInterceptor agentGovernanceAopInterceptor(AgentGovernanceManager mgr) {
        return new AgentGovernanceAopInterceptor(mgr);
    }

    /**
     * ★ 注册 API 层拦截器
     * <p>
     * 通过独立 Bean 实现 WebMvcConfigurer 避免循环依赖：
     * 如果 AutoConfiguration 类直接 implements WebMvcConfigurer 并 @Autowired 自身
     * 的 @Bean 方法产物，会在 Spring Boot 3.x 中触发循环引用异常。
     */
    @Bean
    @ConditionalOnWebApplication
    public WebMvcConfigurer governanceInterceptorRegistry(AgentGovernanceApiInterceptor apiInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(apiInterceptor)
                        .addPathPatterns("/api/**")
                        .excludePathPatterns("/api/admin/**");
            }
        };
    }
}
