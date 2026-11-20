-- 系统中心：租户（学校）、用户、身份、角色、菜单、字典、操作日志、下载凭证
-- 语法兼容 MySQL 8 与 H2（MySQL 模式）

CREATE TABLE sys_tenant (
    id            BIGINT       NOT NULL PRIMARY KEY,
    tenant_name   VARCHAR(100) NOT NULL,
    region_code   VARCHAR(20),
    periods       VARCHAR(50),
    logo          VARCHAR(255),
    package_code  VARCHAR(50),
    expire_at     DATETIME,
    invite_code   VARCHAR(20),
    tenant_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME,
    CONSTRAINT uk_tenant_invite UNIQUE (invite_code)
);

CREATE TABLE sys_user (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    phone               VARCHAR(20),
    username            VARCHAR(64),
    nickname            VARCHAR(64),
    avatar              VARCHAR(255),
    password            VARCHAR(100) NOT NULL,
    must_change_pwd     TINYINT      NOT NULL DEFAULT 0,
    user_status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    login_fail_count    INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME,
    default_identity_id BIGINT,
    last_identity_id    BIGINT,
    last_login_at       DATETIME,
    created_by          BIGINT,
    created_at          DATETIME,
    updated_at          DATETIME,
    CONSTRAINT uk_user_phone UNIQUE (phone),
    CONSTRAINT uk_user_username UNIQUE (username)
);

-- 一个自然人多个身份（R3）；不按租户隔离，因为「身份切换」要列出所有学校的身份
CREATE TABLE sys_user_identity (
    id              BIGINT      NOT NULL PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    identity_type   VARCHAR(30) NOT NULL,
    display_name    VARCHAR(100),
    student_id      BIGINT,
    identity_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    revoked_at      DATETIME,
    revoke_reason   VARCHAR(50),
    created_by      BIGINT,
    created_at      DATETIME,
    updated_at      DATETIME
);
CREATE INDEX idx_identity_user ON sys_user_identity (user_id);
CREATE INDEX idx_identity_tenant ON sys_user_identity (tenant_id, identity_type);

-- 角色：tenant_id 为空是平台预置模板，学校可自建角色
CREATE TABLE sys_role (
    id            BIGINT       NOT NULL PRIMARY KEY,
    tenant_id     BIGINT,
    role_key      VARCHAR(50)  NOT NULL,
    role_name     VARCHAR(50)  NOT NULL,
    identity_type VARCHAR(30)  NOT NULL,
    data_scope    VARCHAR(20)  NOT NULL,
    perms         VARCHAR(2000),
    sort_no       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_role_key ON sys_role (role_key);

CREATE TABLE sys_identity_role (
    id          BIGINT NOT NULL PRIMARY KEY,
    identity_id BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_identity_role UNIQUE (identity_id, role_id)
);

-- 菜单：按当前身份的权限码下发（R1）；menu_status=COMING_SOON 的入口灰显并带「即将上线」角标（AC-17）
CREATE TABLE sys_menu (
    id            BIGINT       NOT NULL PRIMARY KEY,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    menu_name     VARCHAR(50)  NOT NULL,
    path          VARCHAR(200),
    component     VARCHAR(200),
    perm_required VARCHAR(100),
    menu_type     VARCHAR(10)  NOT NULL DEFAULT 'C',
    app_code      VARCHAR(20)  NOT NULL DEFAULT 'WEB',
    menu_status   VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    sort_no       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);

CREATE TABLE sys_dict_data (
    id          BIGINT       NOT NULL PRIMARY KEY,
    dict_type   VARCHAR(50)  NOT NULL,
    dict_label  VARCHAR(100) NOT NULL,
    dict_value  VARCHAR(100) NOT NULL,
    parent_value VARCHAR(100),
    sort_no     INT          NOT NULL DEFAULT 0,
    dict_status VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_dict UNIQUE (dict_type, dict_value)
);

CREATE TABLE sys_oper_log (
    id          BIGINT       NOT NULL PRIMARY KEY,
    tenant_id   BIGINT,
    user_id     BIGINT,
    identity_id BIGINT,
    module      VARCHAR(50),
    action      VARCHAR(100),
    method      VARCHAR(255),
    args        VARCHAR(2000),
    success     TINYINT,
    error_msg   VARCHAR(500),
    cost_ms     BIGINT,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);
CREATE INDEX idx_operlog_tenant ON sys_oper_log (tenant_id, created_at);

-- 导出文件下载凭证：绑定身份，身份撤销即失效（AC-14）
CREATE TABLE sys_download_token (
    id          BIGINT       NOT NULL PRIMARY KEY,
    token       VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    identity_id BIGINT       NOT NULL,
    tenant_id   BIGINT,
    storage_key VARCHAR(255) NOT NULL,
    file_name   VARCHAR(200),
    expire_at   DATETIME     NOT NULL,
    revoked     TINYINT      NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_download_token UNIQUE (token)
);
CREATE INDEX idx_download_identity ON sys_download_token (identity_id);
