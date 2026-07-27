package com.example.temperate.service.admin.aimodel.icon.image.svg.impl;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgCssPolicy;
import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSDeclarationList;
import com.helger.css.decl.CSSMediaQuery;
import com.helger.css.decl.CSSMediaRule;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.decl.ICSSTopLevelRule;
import com.helger.css.handler.DoNothingCSSParseExceptionCallback;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderDeclarationList;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.ThrowingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriterSettings;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 使用 ph-css AST 验证可信官方 SVG 的样式表和行内声明。
 *
 * <p>策略只接受普通样式规则和深浅色媒体查询，拒绝 import、外部字体、动画、属性选择器、
 * 任意外部 URL 与未知函数。解析错误处理器不会把第三方 CSS 原文输出到服务端日志。</p>
 */
@Component
public final class AiModelIconSvgCssPolicyImpl
        implements AiModelIconSvgCssPolicy {

    private static final int MAX_CSS_CHARACTERS = 32 * 1024;
    private static final int MAX_RULES = 256;
    private static final int MAX_MEDIA_RULES = 8;
    private static final int MAX_SELECTORS = 128;
    private static final int MAX_SELECTOR_CHARACTERS = 256;
    private static final int MAX_DECLARATIONS = 1024;
    private static final int MAX_VALUE_CHARACTERS = 512;

    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
            "color",
            "fill",
            "fill-opacity",
            "fill-rule",
            "stroke",
            "stroke-width",
            "stroke-linecap",
            "stroke-linejoin",
            "stroke-miterlimit",
            "stroke-dasharray",
            "stroke-dashoffset",
            "stroke-opacity",
            "opacity",
            "clip-path",
            "clip-rule",
            "mask",
            "stop-color",
            "stop-opacity",
            "display",
            "visibility",
            "overflow",
            "enable-background",
            "vector-effect",
            "text-anchor",
            "dominant-baseline",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "letter-spacing",
            "word-spacing",
            "transform");
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "rgb",
            "rgba",
            "hsl",
            "hsla",
            "url");

    private static final String SIMPLE_SELECTOR =
            "(?::root|[a-z][a-z0-9-]*|"
                    + "(?:[a-z][a-z0-9-]*)?(?:[.#][a-z_][a-z0-9_-]*)+)";
    private static final Pattern SELECTOR = Pattern.compile(
            "^" + SIMPLE_SELECTOR + "$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FUNCTION =
            Pattern.compile("([a-z-]+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_URL = Pattern.compile(
            "url\\(\\s*['\"]?#[A-Za-z_][A-Za-z0-9_.:-]*['\"]?\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_VALUE_CHARACTERS = Pattern.compile(
            "^[A-Za-z0-9_#(),.%+\\-\\s'\"/:]*$");

    @Override
    public void validateStyleSheet(String css) {
        requireBoundedCss(css);
        CascadingStyleSheet styleSheet = CSSReader.readFromStringReader(
                css,
                readerSettings());
        if (styleSheet == null
                || styleSheet.hasImportRules()
                || styleSheet.hasNamespaceRules()) {
            throw unsafeCss();
        }
        CssBudget budget = new CssBudget();
        validateRules(styleSheet.getAllRules(), true, budget);
    }

    @Override
    public void validateDeclarationList(String css) {
        requireBoundedCss(css);
        CSSDeclarationList declarations = CSSReaderDeclarationList.readFromString(
                css,
                readerSettings());
        if (declarations == null) {
            throw unsafeCss();
        }
        CssBudget budget = new CssBudget();
        validateDeclarations(declarations.getAllDeclarations(), budget);
    }

    private static CSSReaderSettings readerSettings() {
        return new CSSReaderSettings()
                .setCustomErrorHandler(new ThrowingCSSParseErrorHandler())
                .setCustomExceptionHandler(new DoNothingCSSParseExceptionCallback())
                .setInterpretErrorHandler(new RejectingCSSInterpretErrorHandler())
                .setBrowserCompliantMode(false)
                .setKeepDeprecatedProperties(false)
                .setUseSourceLocation(false);
    }

    private static void validateRules(
            Iterable<? extends ICSSTopLevelRule> rules,
            boolean allowMedia,
            CssBudget budget) {
        for (ICSSTopLevelRule rule : rules) {
            budget.rules++;
            if (budget.rules > MAX_RULES) {
                throw unsafeCss();
            }
            if (rule instanceof CSSStyleRule styleRule) {
                validateStyleRule(styleRule, budget);
                continue;
            }
            if (allowMedia && rule instanceof CSSMediaRule mediaRule) {
                budget.mediaRules++;
                if (budget.mediaRules > MAX_MEDIA_RULES) {
                    throw unsafeCss();
                }
                validateMediaRule(mediaRule, budget);
                continue;
            }
            throw unsafeCss();
        }
    }

    private static void validateMediaRule(
            CSSMediaRule mediaRule,
            CssBudget budget) {
        if (mediaRule.getMediaQueryCount() != 1) {
            throw unsafeCss();
        }
        CSSMediaQuery query = mediaRule.getMediaQueryAtIndex(0);
        if (query == null) {
            throw unsafeCss();
        }
        String normalized = query.getAsCSSString(
                        CSSWriterSettings.DEFAULT_SETTINGS,
                        0)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (!"(prefers-color-scheme:dark)".equals(normalized)
                && !"(prefers-color-scheme:light)".equals(normalized)) {
            throw unsafeCss();
        }
        // 媒体查询内部只允许普通样式规则，禁止继续嵌套其他 at-rule。
        validateRules(mediaRule.getAllRules(), false, budget);
    }

    private static void validateStyleRule(
            CSSStyleRule styleRule,
            CssBudget budget) {
        if (styleRule.hasRules()
                || !styleRule.hasSelectors()
                || !styleRule.hasDeclarations()) {
            throw unsafeCss();
        }
        for (CSSSelector selector : styleRule.getAllSelectors()) {
            budget.selectors++;
            if (budget.selectors > MAX_SELECTORS) {
                throw unsafeCss();
            }
            String text = selector.getAsCSSString(
                    CSSWriterSettings.DEFAULT_SETTINGS,
                    0);
            if (text == null
                    || text.length() > MAX_SELECTOR_CHARACTERS
                    || !SELECTOR.matcher(text.trim()).matches()) {
                throw unsafeCss();
            }
        }
        validateDeclarations(styleRule.getAllDeclarations(), budget);
    }

    private static void validateDeclarations(
            Iterable<CSSDeclaration> declarations,
            CssBudget budget) {
        for (CSSDeclaration declaration : declarations) {
            budget.declarations++;
            if (budget.declarations > MAX_DECLARATIONS
                    || declaration == null
                    || declaration.isImportant()
                    || !ALLOWED_PROPERTIES.contains(
                            declaration.getProperty().toLowerCase(Locale.ROOT))) {
                throw unsafeCss();
            }
            validateValue(declaration.getExpressionAsCSSString());
        }
    }

    private static void validateValue(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_VALUE_CHARACTERS
                || !SAFE_VALUE_CHARACTERS.matcher(value).matches()) {
            throw unsafeCss();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("@")
                || normalized.contains("javascript:")
                || normalized.contains("data:")
                || normalized.contains("http:")
                || normalized.contains("https:")
                || normalized.contains("file:")
                || normalized.contains("ftp:")
                || normalized.contains("expression(")
                || normalized.contains("behavior:")
                || normalized.contains("-moz-binding")
                || normalized.contains("var(")
                || normalized.contains("--")
                || normalized.contains("\\")) {
            throw unsafeCss();
        }

        Matcher functionMatcher = FUNCTION.matcher(normalized);
        while (functionMatcher.find()) {
            if (!ALLOWED_FUNCTIONS.contains(functionMatcher.group(1))) {
                throw unsafeCss();
            }
        }

        String withoutLocalUrls = LOCAL_URL.matcher(normalized).replaceAll("");
        if (withoutLocalUrls.contains("url(")) {
            throw unsafeCss();
        }
    }

    private static void requireBoundedCss(String css) {
        if (css == null
                || css.isBlank()
                || css.length() > MAX_CSS_CHARACTERS
                || css.indexOf('\u0000') >= 0) {
            throw unsafeCss();
        }
    }

    private static AiModelIconException unsafeCss() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                "AI model icon SVG CSS is outside the trusted official policy.");
    }

    private static final class CssBudget {
        private int rules;
        private int mediaRules;
        private int selectors;
        private int declarations;
    }

    /**
     * 将 CSS 解释阶段的警告和错误都转换为受控拒绝，且不保留第三方原始消息。
     */
    private static final class RejectingCSSInterpretErrorHandler
            implements ICSSInterpretErrorHandler {

        @Override
        public void onCSSInterpretationWarning(String message) {
            throw unsafeCss();
        }

        @Override
        public void onCSSInterpretationError(String message) {
            throw unsafeCss();
        }
    }
}
