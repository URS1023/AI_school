-- 资源中心：目录树、资源（平台 / 校本 / 个人）、下载权益、收藏、资源篮、评论纠错投诉、资源征集
-- 题库：统一题目模型（平台 / 校本 / 个人 / AI，PRD 15 ➕P0）、试题篮、试卷

CREATE TABLE res_catalog (
    id            BIGINT       NOT NULL PRIMARY KEY,
    catalog_type  VARCHAR(20)  NOT NULL,
    owner_user_id BIGINT,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    period        VARCHAR(10),
    subject       VARCHAR(30),
    edition       VARCHAR(50),
    volume        VARCHAR(50),
    catalog_name  VARCHAR(100) NOT NULL,
    sort_no       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_catalog_tree ON res_catalog (catalog_type, period, subject, parent_id);

CREATE TABLE res_resource (
    id             BIGINT       NOT NULL PRIMARY KEY,
    res_scope      VARCHAR(20)  NOT NULL,
    tenant_id      BIGINT,
    author_user_id BIGINT       NOT NULL,
    title          VARCHAR(200) NOT NULL,
    res_type       VARCHAR(30)  NOT NULL,
    res_level      VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    price_coins    BIGINT       NOT NULL DEFAULT 0,
    period         VARCHAR(10),
    subject        VARCHAR(30),
    catalog_id     BIGINT,
    region_code    VARCHAR(20),
    res_year       INT,
    file_key       VARCHAR(255),
    file_ext       VARCHAR(10),
    file_size      BIGINT       NOT NULL DEFAULT 0,
    summary        VARCHAR(500),
    res_status     VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    reject_reason  VARCHAR(300),
    publish_range  VARCHAR(20),
    range_value    VARCHAR(50),
    activity_id    BIGINT,
    download_count BIGINT       NOT NULL DEFAULT 0,
    view_count     BIGINT       NOT NULL DEFAULT 0,
    published_at   DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_res_list ON res_resource (res_scope, res_status, period, subject);
CREATE INDEX idx_res_author ON res_resource (author_user_id);
CREATE INDEX idx_res_tenant ON res_resource (tenant_id, res_scope);

-- 下载权益：付费资源买一次永久可下（3.8 售后规则）
CREATE TABLE res_download (
    id          BIGINT   NOT NULL PRIMARY KEY,
    user_id     BIGINT   NOT NULL,
    resource_id BIGINT   NOT NULL,
    paid_coins  BIGINT   NOT NULL DEFAULT 0,
    times       INT      NOT NULL DEFAULT 1,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_res_download UNIQUE (user_id, resource_id)
);

CREATE TABLE res_favorite (
    id          BIGINT       NOT NULL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    target_type VARCHAR(20)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    folder      VARCHAR(50)  NOT NULL DEFAULT '默认收藏夹',
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_res_favorite UNIQUE (user_id, target_type, target_id)
);

-- 资源篮 / 试题篮
CREATE TABLE res_basket (
    id         BIGINT      NOT NULL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    item_type  VARCHAR(20) NOT NULL,
    item_id    BIGINT      NOT NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    CONSTRAINT uk_res_basket UNIQUE (user_id, item_type, item_id)
);

CREATE TABLE res_comment (
    id             BIGINT        NOT NULL PRIMARY KEY,
    resource_id    BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    comment_type   VARCHAR(20)   NOT NULL,
    content        VARCHAR(1000) NOT NULL,
    comment_status VARCHAR(20)   NOT NULL DEFAULT 'VISIBLE',
    reply          VARCHAR(500),
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_res_comment ON res_comment (resource_id, comment_type);

CREATE TABLE res_collection_activity (
    id              BIGINT       NOT NULL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    cover           VARCHAR(255),
    rules           TEXT,
    periods         VARCHAR(50),
    subjects        VARCHAR(200),
    reward_coins    BIGINT       NOT NULL DEFAULT 0,
    reward_exp      BIGINT       NOT NULL DEFAULT 0,
    start_at        DATETIME,
    end_at          DATETIME,
    activity_status VARCHAR(20)  NOT NULL DEFAULT 'ONGOING',
    created_by      BIGINT,
    created_at      DATETIME,
    updated_at      DATETIME
);

CREATE TABLE q_question (
    id             BIGINT       NOT NULL PRIMARY KEY,
    q_scope        VARCHAR(20)  NOT NULL,
    tenant_id      BIGINT,
    owner_user_id  BIGINT,
    ref_question_id BIGINT,
    period         VARCHAR(10),
    subject        VARCHAR(30),
    q_type         VARCHAR(30)  NOT NULL,
    difficulty     VARCHAR(10),
    q_source       VARCHAR(30),
    category       VARCHAR(30),
    stem           TEXT         NOT NULL,
    options        TEXT,
    answer         TEXT,
    analysis       TEXT,
    knowledge      VARCHAR(500),
    catalog_id     BIGINT,
    group_id       BIGINT,
    group_no       INT,
    q_year         INT,
    region_code    VARCHAR(20),
    used_count     BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_question_scope ON q_question (q_scope, subject, q_type, difficulty);
CREATE INDEX idx_question_owner ON q_question (owner_user_id, catalog_id);

CREATE TABLE q_paper (
    id            BIGINT       NOT NULL PRIMARY KEY,
    owner_user_id BIGINT       NOT NULL,
    tenant_id     BIGINT,
    title         VARCHAR(200) NOT NULL,
    period        VARCHAR(10),
    subject       VARCHAR(30),
    total_score   DECIMAL(8, 2) NOT NULL DEFAULT 0,
    paper_mode    VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    seal_line     VARCHAR(100),
    folder        VARCHAR(100) NOT NULL DEFAULT '我的试卷',
    deleted       TINYINT      NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_paper_owner ON q_paper (owner_user_id, deleted);

CREATE TABLE q_paper_item (
    id            BIGINT        NOT NULL PRIMARY KEY,
    paper_id      BIGINT        NOT NULL,
    question_id   BIGINT        NOT NULL,
    section_title VARCHAR(50),
    item_no       INT           NOT NULL,
    score         DECIMAL(6, 2) NOT NULL,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_paper_item ON q_paper_item (paper_id, item_no);

-- 演示用平台目录与题目
INSERT INTO res_catalog (id, catalog_type, parent_id, period, subject, edition, volume, catalog_name, sort_no) VALUES
(1, 'TEXTBOOK', 0, '1', 'MATH', '人教A版', '必修第一册', '第一章 集合与常用逻辑用语', 1),
(2, 'TEXTBOOK', 1, '1', 'MATH', '人教A版', '必修第一册', '1.1 集合的概念', 1),
(3, 'TEXTBOOK', 0, '1', 'MATH', '人教A版', '必修第一册', '第三章 函数的概念与性质', 3),
(10, 'KNOWLEDGE', 0, '1', 'MATH', NULL, NULL, '集合', 1),
(11, 'KNOWLEDGE', 0, '1', 'MATH', NULL, NULL, '函数', 2),
(12, 'KNOWLEDGE', 0, '1', 'MATH', NULL, NULL, '导数', 3);

INSERT INTO q_question (id, q_scope, period, subject, q_type, difficulty, q_source, stem, options, answer, analysis, knowledge, catalog_id, q_year) VALUES
(1, 'PLATFORM', '1', 'MATH', 'SINGLE', '1', 'PRACTICE', '已知集合 A={1,2,3}，B={2,3,4}，则 A∩B=（ ）', '["A. {1}","B. {2,3}","C. {1,2,3,4}","D. ∅"]', 'B', '交集为两集合的公共元素', '集合', 2, 2025),
(2, 'PLATFORM', '1', 'MATH', 'SINGLE', '2', 'PRACTICE', '函数 f(x)=√(x-1) 的定义域是（ ）', '["A. x≥1","B. x>1","C. x≤1","D. R"]', 'A', '被开方数非负', '函数', 3, 2025),
(3, 'PLATFORM', '1', 'MATH', 'BLANK', '3', 'MOCK', '函数 f(x)=x³ 在 x=2 处的导数为____', NULL, '12', 'f''(x)=3x²', '导数', NULL, 2025),
(4, 'PLATFORM', '1', 'MATH', 'ANSWER', '4', 'GAOKAO', '已知函数 f(x)=x²-2ax+1，讨论 f(x) 在 [0,2] 上的最小值。', NULL, '分 a≤0、0<a<2、a≥2 三种情况', '对称轴分类讨论', '函数', 3, 2024),
(5, 'PLATFORM', '1', 'MATH', 'SINGLE', '3', 'MOCK', '若集合 A={x|x²-3x+2=0}，则 A 的子集个数为（ ）', '["A. 2","B. 3","C. 4","D. 8"]', 'C', 'A={1,2}，子集 2²=4 个', '集合', 2, 2025),
(6, 'PLATFORM', '1', 'MATH', 'JUDGE', '1', 'PRACTICE', '空集是任何集合的子集。', NULL, '正确', '定义', '集合', 2, 2025);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_no) VALUES
(60, 'resource_type', '数字图书', 'BOOK', 9), (61, 'resource_type', '素养课程', 'COURSE', 10);
