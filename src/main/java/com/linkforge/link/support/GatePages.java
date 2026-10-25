package com.linkforge.link.support;

import org.springframework.web.util.HtmlUtils;

/**
 * Server-rendered HTML for the redirect gates (no template engine, prod serves no
 * static resources). Every dynamic value passes HtmlUtils.htmlEscape, pages load no
 * JS and no external resources; the controller adds the CSP/nosniff headers.
 */
public final class GatePages {

    /** Locks the pages down: no script, no framing, no external forms. */
    public static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    private GatePages() {
    }

    /** B9 hard block: no continue affordance, and never any echo of the target. */
    public static String block() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="robots" content="noindex">
                <title>Blocked</title>
                </head>
                <body style="font-family:system-ui,sans-serif;max-width:36em;margin:4rem auto;padding:0 1rem">
                <h1>此链接已被拦截 / This link is blocked</h1>
                <p>该短链的目标域名在本地策略名单中，已阻止访问。 / The target of this short link is on the local block list and access was denied.</p>
                </body>
                </html>
                """;
    }

    /** B9 warn interstitial: shows the escaped target, one explicit continue link. */
    public static String warn(String targetUrl, String asciiHost, String code) {
        String safeUrl = HtmlUtils.htmlEscape(targetUrl);
        String safeHost = HtmlUtils.htmlEscape(asciiHost);
        String safeCode = HtmlUtils.htmlEscape(code);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="robots" content="noindex">
                <title>Forwarding notice</title>
                </head>
                <body style="font-family:system-ui,sans-serif;max-width:36em;margin:4rem auto;padding:0 1rem">
                <h1>即将离开本站 / You are leaving this site</h1>
                <p>此短链将跳转到：<br><code>%s</code></p>
                <p>目标域名：<code>%s</code></p>
                <p>目标站点与本项目无关，请自行判断其安全性。 / The target is not affiliated with this service; judge its safety yourself.</p>
                <p><a href="/r/%s?lf_continue=1">仍要继续 / Continue anyway</a></p>
                </body>
                </html>
                """.formatted(safeUrl, safeHost, safeCode);
    }

    /** B3 unlock form: the code is the only dynamic value, and never the target. */
    public static String unlock(String code, String errorMessage) {
        String safeCode = HtmlUtils.htmlEscape(code);
        String error = errorMessage == null ? "" : "<p style=\"color:#b00\">"
                + HtmlUtils.htmlEscape(errorMessage) + "</p>";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="robots" content="noindex">
                <title>Link locked</title>
                </head>
                <body style="font-family:system-ui,sans-serif;max-width:24em;margin:4rem auto;padding:0 1rem">
                <h1>此链接受密码保护 / This link is password-protected</h1>
                %s
                <form method="post" action="/r/%s/unlock">
                <label>密码 / Password <input type="password" name="password" required autofocus></label>
                <button type="submit">解锁 / Unlock</button>
                </form>
                </body>
                </html>
                """.formatted(error, safeCode);
    }
}
