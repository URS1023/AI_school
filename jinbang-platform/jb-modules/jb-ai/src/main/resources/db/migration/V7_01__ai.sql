-- AI 智能体平台：智能体、计费规则、Prompt 版本、模型路由、会话消息、任务与子任务、知识库、作文档案、推荐问题、回答反馈

CREATE TABLE ai_agent (
    id             BIGINT       NOT NULL PRIMARY KEY,
    agent_code     VARCHAR(40)  NOT NULL,
    agent_name     VARCHAR(50)  NOT NULL,
    description    VARCHAR(300),
    icon           VARCHAR(100),
    category       VARCHAR(30),
    price_mode     VARCHAR(20)  NOT NULL,
    unit_price     BIGINT       NOT NULL DEFAULT 0,
    default_units  INT          NOT NULL DEFAULT 1,
    max_units      INT          NOT NULL DEFAULT 50,
    allowed_types  VARCHAR(200) NOT NULL,
    agent_status   VARCHAR(20)  NOT NULL DEFAULT 'ONLINE',
    model_code     VARCHAR(40),
    sort_no        INT          NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_ai_agent UNIQUE (agent_code)
);

CREATE TABLE ai_prompt (
    id            BIGINT      NOT NULL PRIMARY KEY,
    agent_code    VARCHAR(40) NOT NULL,
    version_no    INT         NOT NULL,
    content       TEXT        NOT NULL,
    prompt_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    gray_percent  INT         NOT NULL DEFAULT 0,
    remark        VARCHAR(200),
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME,
    CONSTRAINT uk_ai_prompt UNIQUE (agent_code, version_no)
);

CREATE TABLE ai_model (
    id          BIGINT       NOT NULL PRIMARY KEY,
    model_code  VARCHAR(40)  NOT NULL,
    provider    VARCHAR(20)  NOT NULL,
    base_url    VARCHAR(255),
    model_name  VARCHAR(80)  NOT NULL,
    api_key_ref VARCHAR(80),
    priority    INT          NOT NULL DEFAULT 0,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    timeout_sec INT          NOT NULL DEFAULT 60,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);

CREATE TABLE ai_session (
    id          BIGINT       NOT NULL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    identity_id BIGINT,
    agent_code  VARCHAR(40)  NOT NULL,
    title       VARCHAR(100),
    pinned      TINYINT      NOT NULL DEFAULT 0,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);
CREATE INDEX idx_ai_session_user ON ai_session (user_id, deleted, updated_at);

CREATE TABLE ai_message (
    id          BIGINT      NOT NULL PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    msg_role    VARCHAR(20) NOT NULL,
    content     TEXT,
    task_id     VARCHAR(64),
    msg_status  VARCHAR(20),
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);
CREATE INDEX idx_ai_message_session ON ai_message (session_id, created_at);

CREATE TABLE ai_task (
    id             BIGINT       NOT NULL PRIMARY KEY,
    task_id        VARCHAR(64)  NOT NULL,
    parent_task_id VARCHAR(64),
    user_id        BIGINT       NOT NULL,
    identity_id    BIGINT,
    tenant_id      BIGINT,
    agent_code     VARCHAR(40)  NOT NULL,
    session_id     BIGINT,
    input_text     TEXT,
    estimate       BIGINT       NOT NULL DEFAULT 0,
    charged        BIGINT       NOT NULL DEFAULT 0,
    task_status    VARCHAR(20)  NOT NULL,
    total_items    INT          NOT NULL DEFAULT 1,
    success_items  INT          NOT NULL DEFAULT 0,
    user_message   VARCHAR(300),
    fail_reason    VARCHAR(1000),
    model_code     VARCHAR(40),
    prompt_version INT,
    tokens_in      BIGINT       NOT NULL DEFAULT 0,
    tokens_out     BIGINT       NOT NULL DEFAULT 0,
    cost_ms        BIGINT       NOT NULL DEFAULT 0,
    finished_at    DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_ai_task UNIQUE (task_id)
);
CREATE INDEX idx_ai_task_user ON ai_task (user_id, created_at);
CREATE INDEX idx_ai_task_agent ON ai_task (agent_code, created_at);

CREATE TABLE ai_subtask (
    id           BIGINT      NOT NULL PRIMARY KEY,
    task_id      VARCHAR(64) NOT NULL,
    item_no      INT         NOT NULL,
    item_label   VARCHAR(100),
    input_text   TEXT,
    output_text  TEXT,
    item_status  VARCHAR(20) NOT NULL,
    attempts     INT         NOT NULL DEFAULT 1,
    units        INT         NOT NULL DEFAULT 0,
    charged      BIGINT      NOT NULL DEFAULT 0,
    fail_message VARCHAR(300),
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME,
    CONSTRAINT uk_ai_subtask UNIQUE (task_id, item_no)
);

CREATE TABLE ai_recommend_question (
    id            BIGINT       NOT NULL PRIMARY KEY,
    agent_code    VARCHAR(40)  NOT NULL,
    identity_type VARCHAR(30),
    question      VARCHAR(200) NOT NULL,
    sort_no       INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);

CREATE TABLE ai_kb (
    id            BIGINT       NOT NULL PRIMARY KEY,
    owner_user_id BIGINT       NOT NULL,
    kb_type       VARCHAR(20)  NOT NULL,
    kb_name       VARCHAR(100) NOT NULL,
    intro         VARCHAR(300),
    quota_bytes   BIGINT       NOT NULL DEFAULT 104857600,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);

CREATE TABLE ai_kb_member (
    id          BIGINT      NOT NULL PRIMARY KEY,
    kb_id       BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    member_role VARCHAR(20) NOT NULL,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_kb_member UNIQUE (kb_id, user_id)
);

CREATE TABLE ai_kb_doc (
    id           BIGINT       NOT NULL PRIMARY KEY,
    kb_id        BIGINT       NOT NULL,
    folder       VARCHAR(200) NOT NULL DEFAULT '/',
    file_name    VARCHAR(200) NOT NULL,
    storage_key  VARCHAR(255),
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'PARSING',
    parse_error  VARCHAR(300),
    chunk_count  INT          NOT NULL DEFAULT 0,
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME
);
CREATE INDEX idx_kb_doc_kb ON ai_kb_doc (kb_id);

CREATE TABLE ai_kb_chunk (
    id         BIGINT NOT NULL PRIMARY KEY,
    kb_id      BIGINT NOT NULL,
    doc_id     BIGINT NOT NULL,
    chunk_no   INT    NOT NULL,
    content    TEXT   NOT NULL,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);
CREATE INDEX idx_kb_chunk_kb ON ai_kb_chunk (kb_id);

-- 作文批改档案：按「学生」建档（PRD 7.6 ➕P0），支撑进步追踪
CREATE TABLE ai_essay_record (
    id           BIGINT       NOT NULL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    student_id   BIGINT,
    student_name VARCHAR(50),
    task_id      VARCHAR(64)  NOT NULL,
    item_no      INT          NOT NULL,
    subject      VARCHAR(20)  NOT NULL,
    genre        VARCHAR(50),
    essay_title  VARCHAR(200),
    full_score   INT          NOT NULL,
    essay_text   TEXT,
    score        DECIMAL(6, 2),
    report       TEXT,
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME
);
CREATE INDEX idx_essay_student ON ai_essay_record (student_id, created_at);

CREATE TABLE ai_answer_feedback (
    id          BIGINT       NOT NULL PRIMARY KEY,
    message_id  BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    liked       TINYINT      NOT NULL,
    comment     VARCHAR(500),
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_answer_feedback UNIQUE (message_id, user_id)
);

-- 智能体与计费规则（PRD 2.4 ➕P0 计费公示）。price_mode：PER_CALL 按次 / PER_KCHAR 每千字 / PER_PAGE 每页 / PER_ITEM 每项 / FREE 免费
INSERT INTO ai_agent (id, agent_code, agent_name, description, category, price_mode, unit_price, default_units, max_units, allowed_types, agent_status, sort_no) VALUES
(1, 'ask', '智能问答', '教学问题问答，可检索题库、资源、学情和知识库', 'TEACH', 'PER_KCHAR', 3, 4, 20, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,REGION,PLATFORM', 'ONLINE', 1),
(2, 'question', '智能生成题目', '按学段学科难度生成题目，可一键加入题库', 'TEACH', 'PER_ITEM', 2, 5, 30, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,REGION,PLATFORM', 'ONLINE', 2),
(3, 'ppt', '智能生成PPT', '大纲生成 → 编辑 → 选模板 → 生成课件，自动同步到独立备课', 'TEACH', 'PER_PAGE', 2, 12, 40, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,REGION,PLATFORM', 'ONLINE', 3),
(4, 'paper_correct', '智能批改试卷', '按顺序上传试卷图片，可上传标准答案，逐题批改', 'TEACH', 'PER_PAGE', 5, 2, 20, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,PLATFORM', 'ONLINE', 4),
(5, 'essay', '智能批改作文', '语文/英语作文批改，按学生建档追踪进步', 'TEACH', 'PER_ITEM', 8, 1, 60, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,PLATFORM', 'ONLINE', 5),
(6, 'lesson_plan', '教案/学案生成', '按课生成教案、学案、导学案', 'TEACH', 'PER_CALL', 10, 1, 1, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,REGION,PLATFORM', 'ONLINE', 6),
(7, 'learning', '学情分析助手', '读取本人权限范围内的考试和作业数据生成分析', 'TEACH', 'PER_CALL', 5, 1, 1, 'TEACHER,SCHOOL_ADMIN,PLATFORM', 'ONLINE', 7),
(8, 'home_school', '家校沟通', '家长通知、家长会发言稿、学生评语', 'TEACH', 'PER_CALL', 3, 1, 1, 'TEACHER,SCHOOL_ADMIN,PLATFORM', 'ONLINE', 8),
(9, 'service', '智能客服', '操作、教务、学习问题解答，可转人工', 'SERVICE', 'FREE', 0, 1, 1, 'VISITOR_TEACHER,TEACHER,SCHOOL_ADMIN,STUDENT,PARENT,REGION,PLATFORM', 'ONLINE', 9),
(10, 'study_buddy', 'AI 学伴', '学生安全模式问答：只讲思路不直接给答案', 'STUDENT', 'FREE', 0, 1, 1, 'STUDENT', 'ONLINE', 10),
(11, 'parent_helper', '家长助手', '解读孩子的学情报告、家庭教育建议', 'PARENT', 'FREE', 0, 1, 1, 'PARENT', 'ONLINE', 11),
(12, 'image', 'AI 绘图', '教学插图生成', 'TEACH', 'PER_CALL', 10, 1, 1, 'VISITOR_TEACHER,TEACHER', 'COMING_SOON', 20),
(13, 'video', '微课视频生成', '微课视频生成', 'TEACH', 'PER_CALL', 50, 1, 1, 'VISITOR_TEACHER,TEACHER', 'COMING_SOON', 21);

INSERT INTO ai_prompt (id, agent_code, version_no, content, prompt_status) VALUES
(1, 'ask', 1, '你是世纪金榜的教学助手，面向中小学教师。回答要准确、分点、可直接用于教学；涉及公式用 LaTeX。若提供了【参考资料】，优先依据资料回答并标注来源编号。', 'ACTIVE'),
(2, 'question', 1, '你是命题专家。严格按要求生成题目，只输出 JSON 数组，每个元素包含 type(SINGLE/MULTIPLE/JUDGE/BLANK/ANSWER)、stem、options(数组，非选择题为空)、answer、analysis、knowledge。', 'ACTIVE'),
(3, 'ppt', 1, '你是课件设计师。为给定课题生成课件大纲，只输出 JSON：{"title":"","chapters":[{"title":"","sections":[{"title":"","points":[""]}]}]}。', 'ACTIVE'),
(4, 'paper_correct', 1, '你是阅卷老师。根据试卷识别文本和标准答案逐题批改，只输出 JSON：{"items":[{"no":"","correct":true,"score":0,"fullScore":0,"comment":""}],"total":0}。', 'ACTIVE'),
(5, 'essay', 1, '你是作文阅卷专家。按满分和文体评分，只输出 JSON：{"score":0,"overall":"","dimensions":{"content":0,"expression":0,"structure":0,"development":0},"highlights":[""],"problems":[""],"suggestions":[""],"sample":""}。', 'ACTIVE'),
(6, 'lesson_plan', 1, '你是资深教研员。按课生成规范教案：教学目标、重难点、学情分析、教学过程（环节-时间-师生活动-设计意图）、板书设计、作业布置、教学反思。', 'ACTIVE'),
(7, 'learning', 1, '你是学情分析师。根据提供的统计数据（不要编造数据）给出：总体表现、薄弱知识点、需要关注的学生、教学建议。', 'ACTIVE'),
(8, 'home_school', 1, '你是经验丰富的班主任，语气亲切专业，生成家校沟通文本。', 'ACTIVE'),
(9, 'service', 1, '你是世纪金榜平台客服。只回答平台使用、教务流程、学习相关问题；无法解决时建议用户点击「转人工」。', 'ACTIVE'),
(10, 'study_buddy', 1, '你是学生的学习伙伴。启发式引导：只给思路和提示，不直接给出作业答案；拒绝与学习无关或不适宜未成年人的话题。', 'ACTIVE'),
(11, 'parent_helper', 1, '你是家庭教育顾问，帮助家长理解孩子的学习情况，给出温和、可执行的建议。', 'ACTIVE');

-- 推荐问题按身份类型配置（PRD 7.2 ⚠️：游客不推荐学情类问题）
INSERT INTO ai_recommend_question (id, agent_code, identity_type, question, sort_no) VALUES
(1, 'ask', NULL, '高中化学用到的公式有哪些', 1),
(2, 'ask', NULL, '生成五道高三数学题', 2),
(3, 'ask', 'TEACHER', '帮我分析我所教班级近期学情', 3),
(4, 'ask', 'TEACHER', '我所教班级近期高频错题有哪些', 4),
(5, 'ask', 'VISITOR_TEACHER', '如何设计一节高效的复习课', 3),
(6, 'service', NULL, '世纪金榜AI有哪些角色', 1),
(7, 'service', NULL, '怎么加入学校', 2);
