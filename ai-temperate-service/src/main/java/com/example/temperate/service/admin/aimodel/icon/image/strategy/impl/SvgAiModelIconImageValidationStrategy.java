package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconSvgPolicyProfile;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgCssPolicy;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgEmbeddedRasterMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgEmbeddedRasterValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 安全解析、白名单验证并重新序列化静态 SVG 模型图标。
 *
 * <p>严格档位保持原有纯静态 SVG 子集；可信官方档位额外允许经过 CSS AST 验证的样式，
 * 以及完成真实像素解码的 PNG、JPEG、WebP 内嵌图片。两种档位都拒绝脚本、事件、动画、
 * foreignObject、外部资源引用、实体和未建模能力，不会静默删除节点后继续接受。</p>
 */
@Component
public final class SvgAiModelIconImageValidationStrategy
        implements AiModelIconImageValidationStrategy {

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ELEMENTS = 2048;
    private static final int MAX_ATTRIBUTES = 32;
    private static final int MAX_EMBEDDED_IMAGES = 8;
    private static final int MAX_EMBEDDED_BYTES = 1024 * 1024;
    private static final int MAX_CSS_CHARACTERS = 32 * 1024;
    private static final int MAX_IDENTIFIER_CHARACTERS = 128;

    private static final Set<String> STRICT_ELEMENTS = Set.of(
            "svg",
            "g",
            "defs",
            "title",
            "desc",
            "symbol",
            "use",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "linearGradient",
            "radialGradient",
            "stop",
            "clipPath",
            "mask",
            "text",
            "tspan");
    private static final Set<String> TRUSTED_OFFICIAL_ELEMENTS =
            Set.of("style", "image");

    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "id",
            "viewBox",
            "width",
            "height",
            "x",
            "y",
            "x1",
            "y1",
            "x2",
            "y2",
            "cx",
            "cy",
            "r",
            "rx",
            "ry",
            "fx",
            "fy",
            "fr",
            "d",
            "points",
            "transform",
            "opacity",
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
            "clip-path",
            "clip-rule",
            "mask",
            "offset",
            "stop-color",
            "stop-opacity",
            "gradientUnits",
            "gradientTransform",
            "spreadMethod",
            "clipPathUnits",
            "maskUnits",
            "maskContentUnits",
            "preserveAspectRatio",
            "vector-effect",
            "text-anchor",
            "dominant-baseline",
            "font-family",
            "font-size",
            "font-style",
            "font-weight",
            "letter-spacing",
            "word-spacing",
            "dx",
            "dy",
            "rotate",
            "textLength",
            "lengthAdjust",
            "role",
            "aria-label",
            "focusable",
            "version",
            "baseProfile",
            "color",
            "display",
            "visibility",
            "overflow");

    private static final Set<String> LOCAL_URL_ATTRIBUTES = Set.of(
            "fill",
            "stroke",
            "clip-path",
            "mask");

    private static final Pattern LOCAL_IDENTIFIER =
            Pattern.compile("^#[A-Za-z_][A-Za-z0-9_.:-]*$");
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_.:-]*$");
    private static final Pattern LOCAL_URL =
            Pattern.compile(
                    "^url\\(\\s*['\"]?#[A-Za-z_][A-Za-z0-9_.:-]*['\"]?\\s*\\)$");
    private static final Pattern LOCAL_URL_REFERENCE = Pattern.compile(
            "url\\(\\s*['\"]?#([A-Za-z_][A-Za-z0-9_.:-]*)['\"]?\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_OR_PIXEL =
            Pattern.compile("^(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:px)?$");
    private static final Pattern CLASS_NAMES = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_-]*(?:\\s+[A-Za-z_][A-Za-z0-9_-]*)*$");

    private final AiModelIconSvgCssPolicy cssPolicy;
    private final AiModelIconSvgEmbeddedRasterValidator embeddedRasterValidator;

    public SvgAiModelIconImageValidationStrategy(
            AiModelIconSvgCssPolicy cssPolicy,
            AiModelIconSvgEmbeddedRasterValidator embeddedRasterValidator) {
        this.cssPolicy = Objects.requireNonNull(cssPolicy);
        this.embeddedRasterValidator =
                Objects.requireNonNull(embeddedRasterValidator);
    }

    @Override
    public AiModelIconImageFormat type() {
        return AiModelIconImageFormat.SVG;
    }

    @Override
    public AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType,
            AiModelIconImageValidationContext context) {
        Objects.requireNonNull(context, "context");
        if (bytes == null
                || bytes.length == 0
                || !type().matchesContentType(declaredContentType)) {
            throw invalidImage();
        }
        if (bytes.length > MAX_BYTES) {
            throw unsafeImage();
        }
        String sourceText = new String(bytes, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (sourceText.contains("<!doctype") || sourceText.contains("<!entity")) {
            throw unsafeImage();
        }

        Document document = parse(bytes);
        Element root = document.getDocumentElement();
        if (root == null
                || !"svg".equals(root.getLocalName())
                || !SVG_NAMESPACE.equals(root.getNamespaceURI())) {
            throw unsafeImage();
        }
        validateDocumentChildren(document);

        ValidationBudget budget = new ValidationBudget();
        validateNode(root, 1, context, budget);
        validateReferenceClosure(budget);
        DisplayBounds bounds = resolveDisplayBounds(root);
        byte[] safeBytes = serialize(document);
        if (safeBytes.length == 0 || safeBytes.length > MAX_BYTES) {
            throw unsafeImage();
        }
        return new AiModelIconImageMetadata(
                type(),
                bounds.width(),
                bounds.height(),
                1,
                safeBytes);
    }

    private static void validateDocumentChildren(Document document) {
        NodeList documentChildren = document.getChildNodes();
        for (int index = 0; index < documentChildren.getLength(); index++) {
            short nodeType = documentChildren.item(index).getNodeType();
            if (nodeType != Node.ELEMENT_NODE && nodeType != Node.COMMENT_NODE) {
                throw unsafeImage();
            }
        }
    }

    private static Document parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            // XML 解析器不得把错误回显为包含原始 SVG 内容的业务日志。
            builder.setErrorHandler(new SilentSvgErrorHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException exception) {
            throw decoderUnavailable(exception);
        } catch (SAXException | java.io.IOException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                    "AI model icon SVG is malformed.",
                    exception);
        } catch (RuntimeException exception) {
            throw invalidImage(exception);
        }
    }

    private void validateNode(
            Node node,
            int depth,
            AiModelIconImageValidationContext context,
            ValidationBudget budget) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            validateElement((Element) node, depth, context, budget);
        } else if (node.getNodeType() == Node.CDATA_SECTION_NODE) {
            if (!(node.getParentNode() instanceof Element parent)
                    || !"style".equals(parent.getLocalName())
                    || !isTrustedOfficial(context)) {
                throw unsafeImage();
            }
        } else if (node.getNodeType() != Node.TEXT_NODE
                && node.getNodeType() != Node.COMMENT_NODE) {
            throw unsafeImage();
        }

        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            int childDepth = child.getNodeType() == Node.ELEMENT_NODE
                    ? depth + 1
                    : depth;
            validateNode(child, childDepth, context, budget);
        }
    }

    private void validateElement(
            Element element,
            int depth,
            AiModelIconImageValidationContext context,
            ValidationBudget budget) {
        if (depth > MAX_DEPTH) {
            throw unsafeImage();
        }
        budget.elementCount++;
        String localName = element.getLocalName();
        boolean trustedExtension = TRUSTED_OFFICIAL_ELEMENTS.contains(localName);
        if (budget.elementCount > MAX_ELEMENTS
                || !SVG_NAMESPACE.equals(element.getNamespaceURI())
                || (!STRICT_ELEMENTS.contains(localName)
                && !(trustedExtension && isTrustedOfficial(context)))) {
            throw unsafeImage();
        }

        int embeddedHrefCount = validateAttributes(
                element,
                context,
                budget);
        if ("style".equals(localName)) {
            validateStyleElement(element, context, budget);
        } else if ("image".equals(localName)) {
            if (embeddedHrefCount != 1 || hasElementChildren(element)) {
                throw unsafeImage();
            }
            validateOptionalDimension(element.getAttribute("width"));
            validateOptionalDimension(element.getAttribute("height"));
        }
    }

    private int validateAttributes(
            Element element,
            AiModelIconImageValidationContext context,
            ValidationBudget budget) {
        NamedNodeMap attributes = element.getAttributes();
        if (attributes.getLength() > MAX_ATTRIBUTES) {
            throw unsafeImage();
        }
        int embeddedHrefCount = 0;
        for (int index = 0; index < attributes.getLength(); index++) {
            Attr attribute = (Attr) attributes.item(index);
            String qualifiedName = attribute.getName();
            String localName = attribute.getLocalName() == null
                    ? qualifiedName
                    : attribute.getLocalName();
            String namespace = attribute.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) {
                validateNamespaceDeclaration(
                        element,
                        qualifiedName,
                        attribute.getValue());
                continue;
            }
            if (qualifiedName.toLowerCase(Locale.ROOT).startsWith("on")) {
                throw unsafeImage();
            }
            if (XLINK_NAMESPACE.equals(namespace)) {
                if (!"href".equals(localName)) {
                    throw unsafeImage();
                }
                embeddedHrefCount += validateHref(
                        element,
                        attribute.getValue(),
                        context,
                        budget);
                continue;
            }
            if (XMLConstants.XML_NS_URI.equals(namespace)) {
                if (!"space".equals(localName)
                        || (!"preserve".equals(attribute.getValue())
                        && !"default".equals(attribute.getValue()))) {
                    throw unsafeImage();
                }
                continue;
            }
            if (namespace != null && !namespace.isEmpty()) {
                throw unsafeImage();
            }
            if ("href".equals(localName)) {
                embeddedHrefCount += validateHref(
                        element,
                        attribute.getValue(),
                        context,
                        budget);
                continue;
            }
            if ("id".equals(localName)) {
                registerIdentifier(
                        attribute.getValue(),
                        element.getLocalName(),
                        budget);
                continue;
            }
            if ("class".equals(localName)) {
                if (!isTrustedOfficial(context)
                        || attribute.getValue().length()
                        > MAX_IDENTIFIER_CHARACTERS
                        || !CLASS_NAMES.matcher(attribute.getValue().trim()).matches()) {
                    throw unsafeImage();
                }
                continue;
            }
            if ("style".equals(localName)) {
                if (!isTrustedOfficial(context)
                        || "style".equals(element.getLocalName())) {
                    throw unsafeImage();
                }
                addCssCharacters(attribute.getValue(), budget);
                cssPolicy.validateDeclarationList(attribute.getValue());
                registerLocalUrlReferences(attribute.getValue(), budget);
                continue;
            }
            if ("type".equals(localName) && "style".equals(element.getLocalName())) {
                if (!isTrustedOfficial(context)
                        || !"text/css".equalsIgnoreCase(attribute.getValue().trim())) {
                    throw unsafeImage();
                }
                continue;
            }
            if (!ALLOWED_ATTRIBUTES.contains(localName)) {
                throw unsafeImage();
            }
            validateAttributeValue(localName, attribute.getValue());
            if (LOCAL_URL_ATTRIBUTES.contains(localName)) {
                registerLocalUrlReferences(attribute.getValue(), budget);
            }
        }
        return embeddedHrefCount;
    }

    private int validateHref(
            Element element,
            String value,
            AiModelIconImageValidationContext context,
            ValidationBudget budget) {
        if ("use".equals(element.getLocalName()) && isLocalIdentifier(value)) {
            budget.referencedIds.add(value.trim().substring(1));
            return 0;
        }
        if (!"image".equals(element.getLocalName())
                || !isTrustedOfficial(context)) {
            throw unsafeImage();
        }
        AiModelIconSvgEmbeddedRasterMetadata metadata =
                embeddedRasterValidator.validate(value);
        budget.embeddedImageCount++;
        try {
            budget.embeddedBytes = Math.addExact(
                    budget.embeddedBytes,
                    metadata.decodedBytes());
            budget.embeddedPixels = Math.addExact(
                    budget.embeddedPixels,
                    Math.multiplyExact(
                            (long) metadata.width(),
                            metadata.height()));
        } catch (ArithmeticException exception) {
            throw unsafeImage();
        }
        if (budget.embeddedImageCount > MAX_EMBEDDED_IMAGES
                || budget.embeddedBytes > MAX_EMBEDDED_BYTES
                || budget.embeddedPixels
                > AiModelIconImageValidator.MAX_TOTAL_PIXELS) {
            throw unsafeImage();
        }
        return 1;
    }

    private void validateStyleElement(
            Element element,
            AiModelIconImageValidationContext context,
            ValidationBudget budget) {
        if (!isTrustedOfficial(context) || hasElementChildren(element)) {
            throw unsafeImage();
        }
        addCssCharacters(element.getTextContent(), budget);
        cssPolicy.validateStyleSheet(element.getTextContent());
        registerLocalUrlReferences(element.getTextContent(), budget);
    }

    private static void addCssCharacters(
            String css,
            ValidationBudget budget) {
        try {
            budget.cssCharacters = Math.addExact(
                    budget.cssCharacters,
                    css == null ? 0 : css.length());
        } catch (ArithmeticException exception) {
            throw unsafeImage();
        }
        if (budget.cssCharacters > MAX_CSS_CHARACTERS) {
            throw unsafeImage();
        }
    }

    private static void registerIdentifier(
            String value,
            String elementName,
            ValidationBudget budget) {
        String identifier = value == null ? "" : value.trim();
        if (identifier.length() > MAX_IDENTIFIER_CHARACTERS
                || !IDENTIFIER.matcher(identifier).matches()
                || budget.declaredIds.putIfAbsent(identifier, elementName) != null) {
            throw unsafeImage();
        }
    }

    private static void registerLocalUrlReferences(
            String value,
            ValidationBudget budget) {
        if (value == null || value.isBlank()) {
            return;
        }
        Matcher matcher = LOCAL_URL_REFERENCE.matcher(value);
        while (matcher.find()) {
            budget.referencedIds.add(matcher.group(1));
        }
    }

    private static void validateReferenceClosure(ValidationBudget budget) {
        for (String reference : budget.referencedIds) {
            String targetElement = budget.declaredIds.get(reference);
            if (targetElement == null
                    || "style".equals(targetElement)
                    || "image".equals(targetElement)) {
                throw unsafeImage();
            }
        }
    }

    private static boolean hasElementChildren(Element element) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static void validateNamespaceDeclaration(
            Element element,
            String qualifiedName,
            String value) {
        if (element.getParentNode() instanceof Document
                && (("xmlns".equals(qualifiedName)
                && SVG_NAMESPACE.equals(value))
                || ("xmlns:xlink".equals(qualifiedName)
                && XLINK_NAMESPACE.equals(value)))) {
            return;
        }
        throw unsafeImage();
    }

    private static void validateAttributeValue(String name, String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("javascript:")
                || normalized.contains("data:")
                || normalized.contains("@import")
                || normalized.contains("expression(")
                || normalized.contains("http:")
                || normalized.contains("https:")
                || normalized.contains("file:")
                || normalized.contains("ftp:")
                || normalized.startsWith("//")
                || normalized.contains("\\")) {
            throw unsafeImage();
        }
        if (normalized.contains("url(")
                && (!LOCAL_URL_ATTRIBUTES.contains(name)
                || !LOCAL_URL.matcher(value.trim()).matches())) {
            throw unsafeImage();
        }
    }

    private static boolean isLocalIdentifier(String value) {
        return value != null && LOCAL_IDENTIFIER.matcher(value.trim()).matches();
    }

    private static boolean isTrustedOfficial(
            AiModelIconImageValidationContext context) {
        return context.svgPolicyProfile()
                == AiModelIconSvgPolicyProfile.TRUSTED_OFFICIAL;
    }

    private static void validateOptionalDimension(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Double parsed = parseLength(value);
        if (parsed == null) {
            throw unsafeImage();
        }
        validateDisplayDimension(parsed);
    }

    private static DisplayBounds resolveDisplayBounds(Element root) {
        double[] viewBox = parseViewBox(root.getAttribute("viewBox"));
        Double width = parseLength(root.getAttribute("width"));
        Double height = parseLength(root.getAttribute("height"));
        if (viewBox == null && (width == null || height == null)) {
            throw unsafeImage();
        }
        if (viewBox != null) {
            validateDisplayDimension(viewBox[2]);
            validateDisplayDimension(viewBox[3]);
        }
        if (width != null) {
            validateDisplayDimension(width);
        }
        if (height != null) {
            validateDisplayDimension(height);
        }
        double resolvedWidth = viewBox == null ? width : viewBox[2];
        double resolvedHeight = viewBox == null ? height : viewBox[3];
        return new DisplayBounds(
                (int) Math.ceil(resolvedWidth),
                (int) Math.ceil(resolvedHeight));
    }

    private static double[] parseViewBox(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split("[\\s,]+");
        if (parts.length != 4) {
            throw unsafeImage();
        }
        double[] numbers = new double[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                numbers[index] = Double.parseDouble(parts[index]);
                if (!Double.isFinite(numbers[index])) {
                    throw unsafeImage();
                }
            }
        } catch (NumberFormatException exception) {
            throw unsafeImage();
        }
        return numbers;
    }

    private static Double parseLength(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!NUMBER_OR_PIXEL.matcher(normalized).matches()) {
            throw unsafeImage();
        }
        String number = normalized.endsWith("px")
                ? normalized.substring(0, normalized.length() - 2)
                : normalized;
        try {
            double parsed = Double.parseDouble(number);
            if (!Double.isFinite(parsed)) {
                throw unsafeImage();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw unsafeImage();
        }
    }

    private static void validateDisplayDimension(double value) {
        if (value <= 0 || value > AiModelIconImageValidator.MAX_DIMENSION) {
            throw unsafeImage();
        }
    }

    private static byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(
                    OutputKeys.ENCODING,
                    StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(
                    OutputKeys.OMIT_XML_DECLARATION,
                    "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(
                    new DOMSource(document),
                    new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException | RuntimeException exception) {
            throw decoderUnavailable(exception);
        }
    }

    private static AiModelIconException invalidImage() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                "AI model icon SVG is invalid.");
    }

    private static AiModelIconException invalidImage(Throwable cause) {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                "AI model icon SVG is invalid.",
                cause);
    }

    private static AiModelIconException unsafeImage() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                "AI model icon SVG contains unsafe or excessive content.");
    }

    private static AiModelIconException decoderUnavailable(Throwable cause) {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE,
                "Required AI model icon SVG processor is unavailable.",
                cause);
    }

    private record DisplayBounds(int width, int height) {
    }

    private static final class ValidationBudget {
        private int elementCount;
        private int embeddedImageCount;
        private int embeddedBytes;
        private int cssCharacters;
        private long embeddedPixels;
        private final Map<String, String> declaredIds = new HashMap<>();
        private final Set<String> referencedIds = new HashSet<>();
    }
}
