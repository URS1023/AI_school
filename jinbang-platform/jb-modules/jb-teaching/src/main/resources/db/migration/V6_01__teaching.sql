-- 教学中心：备课目录、备课资料与版本、模板、智能备课、集体备课、社区（金榜杏坛 / 学海共渡）
-- 个人备课归个人（离职后保留，PRD 8.3），所以备课表不按租户隔离

CREATE TABLE tc_prep_catalog (
    id            BIGINT       NOT NULL PRIMARY KEY,
    owner_user_id BIGINT       NOT NULL,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    catalog_name  VARCHAR(100) NOT NULL,
    period        VARCHAR(10),
    subject       VARCHAR(30),
    edition       VARCHAR(50),
    sort_no       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_prep_catalog_owner ON tc_prep_catalog (owner_user_id, parent_id);

CREATE TABLE tc_material (
    id            BIGINT       NOT NULL PRIMARY KEY,
    owner_user_id BIGINT       NOT NULL,
    catalog_id    BIGINT,
    file_name     VARCHAR(200) NOT NULL,
    file_type     VARCHAR(20)  NOT NULL,
    storage_key   VARCHAR(255) NOT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    source        VARCHAR(20)  NOT NULL,
    source_ref    VARCHAR(64),
    version_no    INT          NOT NULL DEFAULT 1,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    deleted_at    DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_material_owner ON tc_material (owner_user_id, catalog_id, deleted);

CREATE TABLE tc_material_version (
    id          BIGINT       NOT NULL PRIMARY KEY,
    material_id BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_material_version UNIQUE (material_id, version_no)
);

CREATE TABLE tc_smart_prep (
    id            BIGINT       NOT NULL PRIMARY KEY,
    owner_user_id BIGINT       NOT NULL,
    catalog_id    BIGINT,
    topic         VARCHAR(200) NOT NULL,
    edition       VARCHAR(50),
    course        VARCHAR(100),
    requirement   VARCHAR(500),
    outline_json  TEXT,
    template_code VARCHAR(30),
    prep_status   VARCHAR(20)  NOT NULL,
    material_id   BIGINT,
    rewarded      TINYINT      NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);

CREATE TABLE tc_collective_theme (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    organizer_user_id   BIGINT       NOT NULL,
    title               VARCHAR(200) NOT NULL,
    subject             VARCHAR(30),
    description         VARCHAR(1000),
    member_user_ids     TEXT         NOT NULL,
    main_material_id    BIGINT,
    theme_status        VARCHAR(20)  NOT NULL DEFAULT 'ONGOING',
    deadline            DATETIME,
    finished_at         DATETIME,
    created_by          BIGINT,
    created_at          DATETIME,
    updated_at          DATETIME
);

CREATE TABLE tc_collective_comment (
    id         BIGINT        NOT NULL PRIMARY KEY,
    tenant_id  BIGINT        NOT NULL,
    theme_id   BIGINT        NOT NULL,
    user_id    BIGINT        NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);

-- 社区：board = XINGTAN 金榜杏坛（教师）/ STUDENT_FORUM 学海共渡（学生，按学校隔离，发布前人工审核）
CREATE TABLE tc_post (
    id             BIGINT       NOT NULL PRIMARY KEY,
    board          VARCHAR(20)  NOT NULL,
    school_id      BIGINT,
    author_user_id BIGINT       NOT NULL,
    author_name    VARCHAR(50),
    title          VARCHAR(200),
    content        VARCHAR(2000) NOT NULL,
    images         VARCHAR(1000),
    attachment_key VARCHAR(255),
    period         VARCHAR(10),
    subject        VARCHAR(30),
    post_status    VARCHAR(20)  NOT NULL,
    reject_reason  VARCHAR(300),
    like_count     INT          NOT NULL DEFAULT 0,
    comment_count  INT          NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_post_board ON tc_post (board, post_status, created_at);

CREATE TABLE tc_post_comment (
    id             BIGINT        NOT NULL PRIMARY KEY,
    post_id        BIGINT        NOT NULL,
    user_id        BIGINT        NOT NULL,
    content        VARCHAR(1000) NOT NULL,
    comment_status VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED',
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);

CREATE TABLE tc_post_like (
    id         BIGINT NOT NULL PRIMARY KEY,
    post_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    CONSTRAINT uk_post_like UNIQUE (post_id, user_id)
);
