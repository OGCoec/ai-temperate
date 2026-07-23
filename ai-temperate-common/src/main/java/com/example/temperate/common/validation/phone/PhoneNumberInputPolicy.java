package com.example.temperate.common.validation.phone;

import java.util.regex.Pattern;

/**
 * 定义认证流程手机号入参的基础字符约束。
 *
 * <p>该类只判断字符串是否由可解析的国际号码字符组成，不负责判断号码是否属于某个国家、是否真实可用或是否为手机号。</p>
 */
public final class PhoneNumberInputPolicy {

    public static final String BASIC_PHONE_PATTERN = "^\\+?[0-9]{1,15}$";

    private static final Pattern BASIC_PHONE = Pattern.compile(BASIC_PHONE_PATTERN);

    private PhoneNumberInputPolicy() {
    }

    public static boolean isBasicPhoneInput(String value) {
        return value != null && BASIC_PHONE.matcher(value).matches();
    }
}
