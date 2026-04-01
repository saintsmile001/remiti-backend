package com.autoinvoice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final String START_TIME = "startTime";

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) {
        request.setAttribute(START_TIME, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler, Exception ex) {
        long start = (Long) request.getAttribute(START_TIME);
        long duration = System.currentTimeMillis() - start;
        String userId = request.getHeader("X-User-Id");
        String userTag = userId != null ? "[" + userId + "]" : "[anonymous]";

        log.info("→ {} {} {} {} ({}ms)",
            request.getMethod(),
            request.getRequestURI(),
            userTag,
            response.getStatus(),
            duration);
    }
}
