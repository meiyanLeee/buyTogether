package com.hmdp.utils;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
public class SaTokenLoginInterceptor implements HandlerInterceptor {

    @Resource
    private AuthenticatedUserResolver authenticatedUserResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String token = StpUtil.getTokenValue();
        log.info("[Auth] request: {} | token: {}", uri, token != null ? token.substring(0, Math.min(8, token.length())) + "..." : "null");
        try {
            StpUtil.checkLogin();
            Object loginId = StpUtil.getLoginId();
            log.info("[Auth] checkLogin passed, loginId={}", loginId);
            UserDTO userDTO = authenticatedUserResolver.resolve(loginId);
            if (userDTO == null) {
                log.warn("[Auth] loginId has no matching user: {}", loginId);
                throw NotLoginException.newInstance(StpUtil.TYPE, "-1", "user not found", null);
            }
            UserHolder.saveUser(userDTO);
            log.info("[Auth] user saved to UserHolder: userId={}", userDTO.getId());
            return true;
        } catch (NotLoginException e) {
            log.warn("[Auth] rejected unauthenticated request: {} - {}", uri, e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"errorMsg\":\"请先登录社区团购账号\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
