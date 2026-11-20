package com.jinbang.common.security;

/** 身份类型（PRD 8.1）。同一自然人可以拥有多个身份，通过「身份切换」改变当前上下文（R3）。 */
public enum IdentityType {
    /** 游客教师：自注册，归属访客学校，只能用资源、备课、AI。 */
    VISITOR_TEACHER,
    /** 学校教师：任课/班主任/年级主任/学科组长/校领导由任教关系和角色决定。 */
    TEACHER,
    SCHOOL_ADMIN,
    STUDENT,
    PARENT,
    /** 区域教研员 / 教育局。 */
    REGION,
    /** 平台运营（超管、运营、内容编辑、审核、客服、财务都在这一身份下用角色区分）。 */
    PLATFORM;

    public boolean isSchoolMember() {
        return this == TEACHER || this == SCHOOL_ADMIN;
    }
}
