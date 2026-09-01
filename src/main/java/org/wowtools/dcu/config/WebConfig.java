package org.wowtools.dcu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册接口层鉴权拦截器。
 *
 * <p>受保护路径 = 需要登录才能看的数据：请求明细、用户统计、用户管理。
 * 服务统计（overview / by-time / by-model）与登录相关接口保持公开，
 * /v1/messages 走 x-api-key 鉴权，不在此列。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor())
                .addPathPatterns(
                        "/stats/records", "/stats/records/**",
                        "/stats/users", "/stats/users/**",
                        "/admin/users", "/admin/users/**");
    }
}
