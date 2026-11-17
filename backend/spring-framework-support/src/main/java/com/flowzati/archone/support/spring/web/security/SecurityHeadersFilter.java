package com.flowzati.archone.support.spring.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 為每個 HTTP response 加上基本的安全標頭。
 *
 * <p>本服務不驗證呼叫者身分（見 quality-attributes.md 的 QA-001），所以沒有引入 Spring Security；這裡只補上與認證無關、
 * 瀏覽器端的防護標頭。
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    static final String CROSS_ORIGIN_RESOURCE_POLICY = "Cross-Origin-Resource-Policy";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 禁止瀏覽器猜測 Content-Type，避免 JSON response 被當成 script 或 HTML 執行。
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        // 其他 origin 的頁面不得以 <img>、<script> 等方式嵌入本服務的 response。
        response.setHeader(CROSS_ORIGIN_RESOURCE_POLICY, "same-origin");
        filterChain.doFilter(request, response);
    }
}
