package com.hmdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFrontendConsistencyTest {

    private static final Path FRONTEND_ROOT = Paths.get("..", "nginx-1.18.0", "html", "hmdp");

    @Test
    void commonJsProvidesUnifiedTokenHelpers() throws IOException {
        String commonJs = readFrontendFile("js", "common.js");

        assertTrue(commonJs.contains("function getAuthToken()"));
        assertTrue(commonJs.contains("function setAuthToken(token)"));
        assertTrue(commonJs.contains("function clearAuthToken()"));
        assertTrue(commonJs.contains("localStorage.getItem(\"token\") || sessionStorage.getItem(\"token\")"));
        assertTrue(commonJs.contains("config.headers['satoken'] = token"));
    }

    @Test
    void pagesUseUnifiedTokenHelpersInsteadOfDirectTokenStorage() throws IOException {
        assertUsesHelper("login.html", "setAuthToken(res.data)");
        assertUsesHelper("login2.html", "setAuthToken(res.data)");
        assertUsesHelper("info.html", "clearAuthToken()");
        assertUsesHelper("blog-edit.html", "getAuthToken()");
    }

    @Test
    void loginPagesUsePolishedCommunityBuyingLayout() throws IOException {
        String codeLogin = readFrontendFile("login.html");
        String passwordLogin = readFrontendFile("login2.html");
        String loginCss = readFrontendFile("css", "login.css");

        assertLoginPageLayout(codeLogin, "手机验证码登录", "/login2.html");
        assertLoginPageLayout(passwordLogin, "手机号密码登录", "/login.html");
        assertTrue(loginCss.contains(".login-shell"));
        assertTrue(loginCss.contains(".login-hero"));
        assertTrue(loginCss.contains(".benefit-grid"));
        assertTrue(loginCss.contains(".login-card"));
        assertTrue(loginCss.contains("@media (max-width: 520px)"));
    }

    private static void assertUsesHelper(String fileName, String expected) throws IOException {
        String content = readFrontendFile(fileName);

        assertTrue(content.contains(expected));
        assertFalse(content.contains("localStorage.setItem(\"token\""));
        assertFalse(content.contains("sessionStorage.setItem(\"token\""));
        assertFalse(content.contains("localStorage.removeItem(\"token\""));
        assertFalse(content.contains("sessionStorage.removeItem(\"token\""));
        assertFalse(content.contains("localStorage.getItem(\"token\""));
        assertFalse(content.contains("sessionStorage.getItem(\"token\""));
    }

    private static void assertLoginPageLayout(String content, String formTitle, String switchHref) {
        assertTrue(content.contains("邻里团购"));
        assertTrue(content.contains("社区自提"));
        assertTrue(content.contains("login-shell"));
        assertTrue(content.contains("login-hero"));
        assertTrue(content.contains("benefit-grid"));
        assertTrue(content.contains("login-card"));
        assertTrue(content.contains(formTitle));
        assertTrue(content.contains("href=\"" + switchHref + "\""));
        assertFalse(content.contains("�"));
        assertFalse(content.contains("閭"));
        assertFalse(content.contains("璇"));
    }

    private static String readFrontendFile(String first, String... more) throws IOException {
        Path path = FRONTEND_ROOT.resolve(Paths.get(first, more));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
