package com.jinbang.common.security;

/**
 * 7 级数据范围（PRD 8.2）。数字越小范围越大；一个身份有多个角色时取最大范围（最小数字）。
 */
public enum DataScope {
    ALL(1), REGION(2), SCHOOL(3), GRADE(4), SUBJECT_GROUP(5), CLASS(6), SELF(7);

    private final int level;

    DataScope(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public static DataScope widest(DataScope a, DataScope b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.level <= b.level ? a : b;
    }
}
