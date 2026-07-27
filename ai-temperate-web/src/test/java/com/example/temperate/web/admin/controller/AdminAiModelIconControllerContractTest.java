package com.example.temperate.web.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconResult;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 验证模型图标管理接口、跨端上传兼容入口和对外响应字段保持已确定的 HTTP 契约。
 */
final class AdminAiModelIconControllerContractTest {

    @Test
    void remainsProxyableForMethodValidation() {
        assertThat(Modifier.isFinal(AdminAiModelIconController.class.getModifiers())).isFalse();
    }

    @Test
    void exposesCompleteCrudUnderDedicatedAdminNamespace() {
        RequestMapping root = AdminAiModelIconController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/admin/ai-model-icons");

        Method[] methods = AdminAiModelIconController.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(Method::getName))
                .contains("list", "detail");
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(Method::getName))
                .contains("createRemote", "createUpload");
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(PatchMapping.class))
                .map(Method::getName))
                .contains("patch");
        assertThat(Arrays.stream(methods)
                .filter(method -> method.isAnnotationPresent(DeleteMapping.class))
                .map(Method::getName))
                .contains("delete");
    }

    @Test
    void fileReplacementKeepsPutAndAddsPostForUniUploadFileCompatibility()
            throws NoSuchMethodException {
        Method method = AdminAiModelIconController.class.getDeclaredMethod(
                "replaceFile",
                com.example.temperate.web.admin.aimodelicon.AiModelIconPublicId.class,
                org.springframework.web.multipart.MultipartFile.class,
                jakarta.servlet.http.HttpServletResponse.class);
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);

        assertThat(mapping.value()).containsExactly("/{publicId}/file");
        assertThat(mapping.method()).containsExactlyInAnyOrder(
                RequestMethod.PUT,
                RequestMethod.POST);
    }

    @Test
    void responseDoesNotExposeInternalDatabaseOrOssIdentifiers() {
        assertThat(Arrays.stream(AiModelIconResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "publicId",
                        "iconName",
                        "iconUrl",
                        "description",
                        "createdAt",
                        "updatedAt")
                .doesNotContain("id", "objectKey");
    }
}
