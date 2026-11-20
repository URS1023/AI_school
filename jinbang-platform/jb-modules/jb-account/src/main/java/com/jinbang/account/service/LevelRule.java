package com.jinbang.account.service;

import java.util.List;

/** 成长等级（PRD 5.3 实测）。 */
public final class LevelRule {
    public record Level(int no, String name, long minExp, String privilege) {
    }

    public static final List<Level> LEVELS = List.of(
            new Level(0, "见习", 0, "基础功能"),
            new Level(1, "青铜助教", 100, "基础功能"),
            new Level(2, "白银学者", 1_000, "优先参与小型活动"),
            new Level(3, "黄金专家", 5_000, "每月 10 次免费预览收费资源"),
            new Level(4, "铂金宗师", 30_000, "每日额外 1 次免费下载收费资源"),
            new Level(5, "钻石圣贤", 100_000, "线下交流活动"),
            new Level(6, "星耀泰斗", 300_000, "上传资源平台推荐、新功能优先体验"),
            new Level(7, "王者至尊", 1_000_000, "荣誉证书、首页展示优秀资源、参与平台决策"));

    private LevelRule() {
    }

    public static Level of(long exp) {
        Level cur = LEVELS.getFirst();
        for (Level l : LEVELS) {
            if (exp >= l.minExp()) {
                cur = l;
            }
        }
        return cur;
    }

    /** 距离下一级还差多少经验；已是最高级返回 0。 */
    public static long toNext(long exp) {
        for (Level l : LEVELS) {
            if (l.minExp() > exp) {
                return l.minExp() - exp;
            }
        }
        return 0;
    }
}
