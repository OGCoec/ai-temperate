package com.example.temperate.mapper.user.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

/**
 * 验证 Java 持久化层的类型、方法签名和模块边界契约。
 */
class PersistenceJavaContractTest {

    @Test
    void identityModelExposesPersistenceProperties() throws IntrospectionException {
        Class<?> identity = loadClass("com.example.temperate.model.user.entity.UserLoginIdentity");

        assertBeanProperty(identity, "id", Long.class);
        assertBeanProperty(identity, "email", String.class);
        assertBeanProperty(identity, "phone", String.class);
        assertBeanProperty(identity, "passwordHash", String.class);
        assertBeanProperty(identity, "passwordVersion", Long.class);
        assertNoBeanProperty(identity, "passwordStrengthLevel");
        assertNoBeanProperty(identity, "passwordPolicyVersion");
        assertNoBeanProperty(identity, "emailVerifiedAt");
        assertNoBeanProperty(identity, "phoneVerifiedAt");
        assertNoBeanProperty(identity, "passwordChangedAt");
        assertBeanProperty(identity, "createdAt", OffsetDateTime.class);
        assertBeanProperty(identity, "updatedAt", OffsetDateTime.class);
        assertTrue(Arrays.stream(identity.getDeclaredConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 0));
    }

    @Test
    void profileModelExposesLogicalIdentityRelationship() throws IntrospectionException {
        Class<?> profile = loadClass("com.example.temperate.model.user.entity.UserProfile");

        assertBeanProperty(profile, "id", Long.class);
        assertBeanProperty(profile, "loginIdentityId", Long.class);
        assertBeanProperty(profile, "displayName", String.class);
        assertNoBeanProperty(profile, "membershipTier");
        assertTrue(Arrays.stream(profile.getDeclaredConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 0));
    }

    @Test
    void membershipQuotaModelExposesScaledIntegerBalance() throws IntrospectionException {
        Class<?> membershipQuota = loadClass(
                "com.example.temperate.model.user.entity.UserMembershipQuota");

        assertBeanProperty(membershipQuota, "id", Long.class);
        assertBeanProperty(membershipQuota, "loginIdentityId", Long.class);
        assertBeanProperty(membershipQuota, "membershipTier", Integer.class);
        assertBeanProperty(membershipQuota, "quotaBalanceMinor", Long.class);
        assertTrue(Arrays.stream(membershipQuota.getDeclaredConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 0));
    }

    @Test
    void identityMapperProvidesBoundedQueriesAndAffectedRowWriteContracts()
            throws ReflectiveOperationException {
        Class<?> identity = loadClass("com.example.temperate.model.user.entity.UserLoginIdentity");
        Class<?> mapper = loadClass(
                "com.example.temperate.mapper.user.identity.UserLoginIdentityMapper");

        assertTrue(mapper.isInterface());
        assertNotNull(mapper.getAnnotation(Mapper.class));

        Method conflicts = mapper.getMethod("findConflicts", String.class, String.class);
        assertEquals(List.class, conflicts.getReturnType());
        assertParamNames(conflicts, "normalizedEmail", "normalizedPhone");

        Method byEmail = mapper.getMethod("findByNormalizedEmail", String.class);
        assertEquals(identity, byEmail.getReturnType());
        assertParamNames(byEmail, "normalizedEmail");

        Method byPhone = mapper.getMethod("findByNormalizedPhone", String.class);
        assertEquals(identity, byPhone.getReturnType());
        assertParamNames(byPhone, "normalizedPhone");

        Method insert = mapper.getMethod("insert", identity);
        assertEquals(int.class, insert.getReturnType());

        Method updatePassword = mapper.getMethod(
                "updatePasswordHash",
                Long.class,
                String.class);
        assertEquals(int.class, updatePassword.getReturnType());
        assertParamNames(updatePassword, "id", "passwordHash");

        Method updatePasswordAndIncrementVersion = mapper.getMethod(
                "updatePasswordHashAndIncrementVersion",
                long.class,
                String.class);
        assertEquals(int.class, updatePasswordAndIncrementVersion.getReturnType());
        assertParamNames(updatePasswordAndIncrementVersion, "id", "passwordHash");
    }

    @Test
    void profileMapperReturnsAffectedRowCountForInsert() throws ReflectiveOperationException {
        Class<?> profile = loadClass("com.example.temperate.model.user.entity.UserProfile");
        Class<?> mapper = loadClass("com.example.temperate.mapper.user.profile.UserProfileMapper");

        assertTrue(mapper.isInterface());
        assertNotNull(mapper.getAnnotation(Mapper.class));
        Method insert = mapper.getMethod("insert", profile);
        assertEquals(int.class, insert.getReturnType());
    }

    @Test
    void membershipQuotaMapperProvidesInsertAndIdentityLookup()
            throws ReflectiveOperationException {
        Class<?> membershipQuota = loadClass(
                "com.example.temperate.model.user.entity.UserMembershipQuota");
        Class<?> mapper = loadClass(
                "com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper");

        assertTrue(mapper.isInterface());
        assertNotNull(mapper.getAnnotation(Mapper.class));
        assertEquals(int.class, mapper.getMethod("insert", membershipQuota).getReturnType());
        Method lookup = mapper.getMethod("findByLoginIdentityId", long.class);
        assertEquals(membershipQuota, lookup.getReturnType());
        assertParamNames(lookup, "loginIdentityId");
    }

    private static Class<?> loadClass(String className) {
        return assertDoesNotThrow(
                () -> Class.forName(className),
                () -> "Expected class to exist: " + className);
    }

    private static void assertBeanProperty(Class<?> type, String name, Class<?> propertyType)
            throws IntrospectionException {
        Map<String, java.beans.PropertyDescriptor> properties = Arrays.stream(
                        Introspector.getBeanInfo(type).getPropertyDescriptors())
                .collect(Collectors.toMap(
                        java.beans.PropertyDescriptor::getName,
                        Function.identity()));
        java.beans.PropertyDescriptor property = properties.get(name);

        assertNotNull(property, () -> "Missing bean property " + type.getName() + "." + name);
        assertEquals(propertyType, property.getPropertyType());
        assertNotNull(property.getReadMethod(), () -> "Missing getter for " + name);
        assertNotNull(property.getWriteMethod(), () -> "Missing setter for " + name);
    }

    private static void assertNoBeanProperty(Class<?> type, String name)
            throws IntrospectionException {
        assertTrue(Arrays.stream(Introspector.getBeanInfo(type).getPropertyDescriptors())
                .noneMatch(property -> name.equals(property.getName())));
    }

    private static void assertParamNames(Method method, String... expectedNames) {
        java.lang.annotation.Annotation[][] annotations = method.getParameterAnnotations();
        assertEquals(expectedNames.length, annotations.length);

        for (int index = 0; index < expectedNames.length; index++) {
            int parameterIndex = index;
            Param param = Arrays.stream(annotations[index])
                    .filter(Param.class::isInstance)
                    .map(Param.class::cast)
                    .findFirst()
                    .orElse(null);
            assertNotNull(param, () -> "Missing @Param at index " + parameterIndex);
            assertEquals(expectedNames[index], param.value());
        }
    }
}
