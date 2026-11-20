-- 学校基础数据（手册第 4 章）：学年、年级、班级、教师、任教关系、学生、家长绑定、游客入校申请

CREATE TABLE edu_academic_year (
    id          BIGINT      NOT NULL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    year_name   VARCHAR(50) NOT NULL,
    start_year  INT         NOT NULL,
    is_current  TINYINT     NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_year UNIQUE (tenant_id, start_year)
);

CREATE TABLE edu_grade (
    id           BIGINT      NOT NULL PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL,
    year_id      BIGINT      NOT NULL,
    period       VARCHAR(10) NOT NULL,
    grade_level  INT         NOT NULL,
    grade_name   VARCHAR(20) NOT NULL,
    grade_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME
);
CREATE INDEX idx_grade_year ON edu_grade (tenant_id, year_id);

CREATE TABLE edu_class (
    id                      BIGINT      NOT NULL PRIMARY KEY,
    tenant_id               BIGINT      NOT NULL,
    year_id                 BIGINT      NOT NULL,
    grade_id                BIGINT      NOT NULL,
    class_name              VARCHAR(50) NOT NULL,
    class_type              VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    head_teacher_identity_id BIGINT,
    parent_invite_code      VARCHAR(20),
    prev_class_id           BIGINT,
    class_status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by              BIGINT,
    created_at              DATETIME,
    updated_at              DATETIME
);
CREATE INDEX idx_class_grade ON edu_class (tenant_id, grade_id);

CREATE TABLE edu_teacher (
    id             BIGINT      NOT NULL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    identity_id    BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    teacher_name   VARCHAR(50) NOT NULL,
    phone          VARCHAR(20),
    subjects       VARCHAR(200),
    teacher_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    resigned_at    DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_teacher_identity UNIQUE (identity_id)
);

-- 任教关系：SUBJECT_TEACHER 任课 / HEAD_TEACHER 班主任 / GRADE_LEADER 年级主任 / SUBJECT_LEADER 学科组长 / SCHOOL_LEADER 校领导
CREATE TABLE edu_teach_rel (
    id          BIGINT      NOT NULL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    year_id     BIGINT      NOT NULL,
    identity_id BIGINT      NOT NULL,
    rel_type    VARCHAR(30) NOT NULL,
    grade_id    BIGINT,
    class_id    BIGINT,
    subject     VARCHAR(30),
    rel_status  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ended_at    DATETIME,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);
CREATE INDEX idx_teachrel_identity ON edu_teach_rel (identity_id, rel_status);
CREATE INDEX idx_teachrel_class ON edu_teach_rel (class_id, rel_status);

CREATE TABLE edu_student (
    id             BIGINT      NOT NULL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    student_no     VARCHAR(20) NOT NULL,
    student_name   VARCHAR(50) NOT NULL,
    gender         VARCHAR(10),
    class_id       BIGINT,
    enroll_year    INT,
    register_no    VARCHAR(30),
    card_no        VARCHAR(30),
    user_id        BIGINT,
    student_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_student_no UNIQUE (tenant_id, student_no)
);
CREATE INDEX idx_student_class ON edu_student (class_id);

-- 学生在班记录（转班后新班看全部历史，旧班统计保留只读历史，PRD 8.3）
CREATE TABLE edu_student_class_log (
    id         BIGINT   NOT NULL PRIMARY KEY,
    tenant_id  BIGINT   NOT NULL,
    student_id BIGINT   NOT NULL,
    class_id   BIGINT   NOT NULL,
    year_id    BIGINT,
    joined_at  DATETIME NOT NULL,
    left_at    DATETIME,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);
CREATE INDEX idx_scl_student ON edu_student_class_log (student_id);

CREATE TABLE edu_parent_binding (
    id             BIGINT      NOT NULL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL,
    parent_user_id BIGINT      NOT NULL,
    identity_id    BIGINT      NOT NULL,
    student_id     BIGINT      NOT NULL,
    relation       VARCHAR(20),
    bind_status    VARCHAR(20) NOT NULL DEFAULT 'BOUND',
    unbound_at     DATETIME,
    unbound_by     BIGINT,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_binding_parent ON edu_parent_binding (parent_user_id, bind_status);
CREATE INDEX idx_binding_student ON edu_parent_binding (student_id, bind_status);

CREATE TABLE edu_join_request (
    id            BIGINT      NOT NULL PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    real_name     VARCHAR(50) NOT NULL,
    subject       VARCHAR(30),
    remark        VARCHAR(255),
    req_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reject_reason VARCHAR(255),
    decided_by    BIGINT,
    decided_at    DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_join_tenant ON edu_join_request (tenant_id, req_status);
