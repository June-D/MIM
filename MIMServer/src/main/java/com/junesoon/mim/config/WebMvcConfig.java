package com.junesoon.mim.config;

import com.junesoon.mim.auth.CurrentDeviceArgumentResolver;
import com.junesoon.mim.auth.CurrentUserArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final CurrentDeviceArgumentResolver currentDeviceArgumentResolver;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver,
        CurrentDeviceArgumentResolver currentDeviceArgumentResolver) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.currentDeviceArgumentResolver = currentDeviceArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
        resolvers.add(currentDeviceArgumentResolver);
    }
}
