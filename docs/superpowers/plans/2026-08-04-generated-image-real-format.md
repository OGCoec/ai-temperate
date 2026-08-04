# Generated Image Real Format Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every generated image is labelled, previewed, uploaded, and persisted using its decoded bytes' real PNG/JPEG/WebP format rather than the requested `output_format` preference.

**Architecture:** Add a small image-generation-domain format detector that accepts only PNG, JPEG, and WebP magic signatures. The Images API SSE mapper will detect the format immediately after Base64 decoding and put the real MIME type in the existing in-memory image event. The finalization path will derive the generated file extension from that MIME type, so the existing OSS uploader and existing `response_attachments` JSON use matching metadata without any schema change.

**Tech Stack:** Java 21, Spring Boot, Jackson, Reactor, JUnit 5, AssertJ, Mockito, Aliyun OSS.

---

## Scope and invariants

- No database tables, fields, indexes, migrations, YAML properties, API request fields, or frontend API contracts are added.
- `output_format` remains an upstream preference (`webp` is still requested); it is not proof of the actual response bytes.
- Only the generated-image path changes. User upload validation and admin model-icon validation remain separate domains and are not reused.
- A partial preview and a final image must report the actual MIME type. Only the final image is sent to OSS and persisted, exactly as today.
- Unsupported or malformed image bytes fail through the existing upstream-protocol failure/refund path. They must never be named, uploaded, or inserted as an attachment.
- This plan fixes future generations. Historical objects are intentionally not rewritten by the normal runtime path.

## File map

| File | Change | Responsibility |
| --- | --- | --- |
| `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/AiConversationGeneratedImageFormat.java` | Create | Detect PNG/JPEG/WebP byte signatures and provide the canonical MIME type and extension. |
| `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiImagesGenerationEventMapper.java` | Modify | Use the detected format after Base64 decoding instead of `options.outputFormat()`. |
| `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/worker/impl/AiConversationGenerationWorkerImpl.java` | Modify | Build the final generated filename from the detected MIME format rather than hard-coding `generated.webp`. |
| `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image/AiConversationGeneratedImageFormatTest.java` | Create | Unit-test the allowed signatures and unsupported input rejection. |
| `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiImagesGenerationEventMapperTest.java` | Modify | Prove PNG bytes returned for a WebP request become `image/png`; preserve partial/final/usage coverage. |

## Task 1: Define the generated-image format boundary

**Files:**

- Create: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/AiConversationGeneratedImageFormat.java`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image/AiConversationGeneratedImageFormatTest.java`

- [ ] **Step 1: Write the failing format-detection tests**

```java
@Test
void detectsPngFromItsBinarySignature() {
    assertThat(AiConversationGeneratedImageFormat.detect(new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    })).isEqualTo(AiConversationGeneratedImageFormat.PNG);
}

@Test
void detectsJpegAndWebpAndExposesCanonicalMetadata() {
    assertThat(AiConversationGeneratedImageFormat.detect(new byte[] {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff
    }).contentType()).isEqualTo("image/jpeg");
    assertThat(AiConversationGeneratedImageFormat.detect(new byte[] {
            'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
    }).extension()).isEqualTo("webp");
}

@Test
void rejectsUnknownOrTruncatedBytes() {
    assertThatThrownBy(() -> AiConversationGeneratedImageFormat.detect(
            new byte[] {'G', 'I', 'F', '8', '9', 'a'}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsupported");
}
```

- [ ] **Step 2: Implement the smallest isolated enum**

```java
public enum AiConversationGeneratedImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    public static AiConversationGeneratedImageFormat detect(byte[] bytes) {
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return PNG;
        }
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) {
            return JPEG;
        }
        if (startsWithAscii(bytes, 0, "RIFF") && startsWithAscii(bytes, 8, "WEBP")) {
            return WEBP;
        }
        throw new IllegalStateException("Generated image format is unsupported.");
    }
}
```

Add the required Chinese JavaDoc explaining that this detector is intentionally local to generated images: it does not accept SVG/GIF/AVIF and does not reuse an unrelated user-upload/admin-icon validator.

- [ ] **Step 3: Keep the detector deterministic and bounded**

Only inspect the first twelve bytes. Do not invoke image transcoding, do not infer format from the requested output format, filename, URL, or HTTP `Content-Type`, and do not create an image-conversion dependency.

- [ ] **Step 4: Stage-2 verification command, only after user approval**

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiConversationGeneratedImageFormatTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected result: all PNG/JPEG/WebP tests pass and GIF/unknown inputs are rejected.

## Task 2: Correct the SSE image metadata at the source

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiImagesGenerationEventMapper.java:66-116`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/model/stream/impl/OpenAiImagesGenerationEventMapperTest.java`

- [ ] **Step 1: Replace synthetic invalid test bytes with real signature fixtures**

The current test fixture begins with `RIFF` but does not contain the mandatory `WEBP` marker, so it must be replaced. Define compact signature fixtures:

```java
private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
};
private static final byte[] WEBP = new byte[] {
        'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
};
```

- [ ] **Step 2: Add the regression test for the observed fault**

```java
@Test
void usesPngMetadataWhenAWebpRequestReturnsPngBytes() {
    String base64 = Base64.getEncoder().encodeToString(PNG);
    OpenAiResponsesSseEvent event = new OpenAiResponsesSseEvent(
            "image_generation.completed",
            "{\"type\":\"image_generation.completed\",\"b64_json\":\"" + base64 + "\"}");

    AiConversationModelEvent.Image mapped = (AiConversationModelEvent.Image)
            mapper.map(event, webpOptions()).get(0);

    assertThat(mapped.value().contentType()).isEqualTo("image/png");
    assertThat(mapped.value().bytes()).containsExactly(PNG);
}
```

Also assert that a WebP byte sequence still yields `image/webp`, and that an unknown signature causes `IllegalStateException` before any `AiConversationModelEvent.Image` is created.

- [ ] **Step 3: Change the mapper implementation**

In `image(...)`, detect after `decode(...)` and use the detector result:

```java
byte[] bytes = decode(text(root, "b64_json"));
AiConversationGeneratedImageFormat format =
        AiConversationGeneratedImageFormat.detect(bytes);
return new AiConversationGeneratedImage(
        imageId, phase, index, format.contentType(),
        options.aspect().width(), options.aspect().height(), bytes);
```

Keep the existing Base64 byte-limit check before format detection. Add a Chinese comment explaining that CLIProxyAPI/OpenAI may return a format different from the output preference; the byte signature is the authoritative metadata source.

- [ ] **Step 4: Verify preview contract without frontend changes**

Do not edit `AiConversationImagePreviewBrokerImpl` or `fornted/common/aichat/ai-conversation-image-generation.js`. Both already forward `image.contentType()` into the SSE `image-preview` data URL. With the mapper corrected, a PNG preview becomes `data:image/png;base64,...` automatically.

- [ ] **Step 5: Stage-2 verification command, only after user approval**

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiConversationGeneratedImageFormatTest,OpenAiImagesGenerationEventMapperTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected result: a requested WebP response with PNG bytes exposes `image/png`; no unsupported payload reaches preview or finalization.

## Task 3: Make the final OSS key and persisted attachment agree with the real format

**Files:**

- Modify: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/generation/worker/impl/AiConversationGenerationWorkerImpl.java:612-615`
- Test: `ai-temperate-service/src/test/java/com/example/temperate/service/user/aiconversation/image/AiConversationGeneratedImageFormatTest.java`

- [ ] **Step 1: Add canonical filename derivation to the format enum**

```java
public String generatedFileName() {
    return "generated." + extension;
}

public static AiConversationGeneratedImageFormat fromContentType(String contentType) {
    return Arrays.stream(values())
            .filter(format -> format.contentType.equalsIgnoreCase(contentType))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                    "Unsupported generated image content type."));
}
```

Add tests for `generated.png`, `generated.jpg`, and `generated.webp`.

- [ ] **Step 2: Replace the hard-coded filename in finalization**

Replace:

```java
"generated.webp"
```

with:

```java
AiConversationGeneratedImageFormat.fromContentType(finalImage.contentType())
        .generatedFileName()
```

The existing `finalImage.contentType()` is now detected metadata. The existing attachment service already uses the generated media's filename for the OSS key and its MIME type for `putPublic`, so no change is required in `AiConversationAttachmentServiceImpl` or the OSS implementation.

- [ ] **Step 3: Preserve current failure behavior**

If detection fails, it occurs before `freezeCompletedImage(...)` calls `finalizeAttachments(...)`. Therefore no OSS object, no `response_attachments` item, and no mistaken filename can be created. Existing stream failure handling continues to set the established terminal/refund outcome; do not add a retry or image re-generation.

- [ ] **Step 4: Add an image-finalization regression test**

Extend the image-capable Worker fixture or add a focused package-level Worker test that streams:

```java
new AiConversationModelEvent.Image(new AiConversationGeneratedImage(
        "image-final", AiConversationGeneratedImagePhase.FINAL, 3,
        "image/png", 1024, 1024, PNG))
```

then capture the `AiConversationGeneratedMedia` passed to `finalizeAttachments(...)` and assert:

```java
assertThat(media.fileName()).isEqualTo("generated.png");
assertThat(media.contentType()).isEqualTo("image/png");
```

The fixture must also stream a normal terminal `AiConversationUsage`, because generated images only settle after both a final image and usage have been received.

- [ ] **Step 5: Stage-2 verification command, only after user approval**

```powershell
mvn -pl ai-temperate-service -am "-Dtest=AiConversationGeneratedImageFormatTest,OpenAiImagesGenerationEventMapperTest,AiConversationGenerationWorkerImplTest" -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected result: PNG bytes produce `generated.png` and `image/png` through the finalization boundary; the existing text-generation Worker tests remain green.

## Task 4: Review the full future-generation contract

**Files:**

- Review only: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/image/impl/AiConversationImagePreviewBrokerImpl.java`
- Review only: `ai-temperate-service/src/main/java/com/example/temperate/service/user/aiconversation/attachment/impl/AiConversationAttachmentServiceImpl.java`
- Review only: `fornted/common/aichat/ai-conversation-image-generation.js`

- [ ] **Step 1: Verify the MIME value travels unchanged**

Confirm this exact flow for an upstream PNG returned despite `output_format=webp`:

```text
Base64 PNG bytes
→ detector returns image/png + png
→ image-preview contentType=image/png
→ browser data:image/png;base64,...
→ final generated.png + image/png
→ OSS object key ends in .png and OSS Content-Type=image/png
→ completed.attachments and response_attachments contain generated.png/image/png/PNG URL
```

- [ ] **Step 2: Confirm non-goals**

Do not add a frontend conversion, do not convert PNG bytes into WebP, do not change CORS, do not add an environment variable, and do not store Base64 in PostgreSQL.

- [ ] **Step 3: Stage-2 safe test boundary**

No real CLIProxyAPI, OpenAI, OSS, PostgreSQL, Redis, or RabbitMQ call is needed for the unit tests above. A separate opt-in local integration request is required before testing a real image generation and OSS upload.

## Historical data policy

The object already generated at `.../eQhMC9ekk7SK9b8AowV8ml5lkC6uddVn0qgFMD.webp` contains PNG bytes but has a WebP name and MIME metadata. The runtime code change will not modify it; it only prevents future incorrect objects.

If historical repair is explicitly approved later, perform it as a separate one-object maintenance operation:

1. Read the exact OSS object and verify the PNG signature and checksum.
2. Upload identical bytes to a new `.png` key with `Content-Type: image/png`; do not overwrite the old key.
3. In one PostgreSQL local transaction, update only that message's `response_attachments` JSON item so `fileName`, `contentType`, and `url` all refer to the new PNG object.
4. Verify the new URL returns PNG bytes and `image/png` metadata before any cleanup.
5. Keep the old `.webp` object until an explicit retention/rollback decision; never delete it as part of the first repair.

This historical operation requires separate user approval because it writes to OSS and PostgreSQL, but it needs no schema migration.

## Completion criteria

- A CLIProxyAPI/OpenAI response that requests WebP but returns PNG bytes becomes a PNG preview and a `generated.png` attachment.
- A true WebP remains WebP; JPEG becomes `.jpg` and `image/jpeg`.
- Unknown bytes cannot reach OSS or `response_attachments`.
- No database schema change, no Base64 persistence, and no CORS/configuration change occurs.
- All listed unit tests are executed only after second-stage test approval and pass with newly produced evidence.
