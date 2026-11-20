package com.jinbang.common.util;

/** 脱敏工具（PRD 13 隐私合规：学生姓名、手机号等对外展示时脱敏）。 */
public final class Masks {
    private Masks() {
    }

    public static String phone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String name(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
