-- 账户中心：金币账户、流水、冻结单、经验、签到、充值订单、人工调账
-- 账户按自然人唯一（PRD 8.2），不按租户隔离

CREATE TABLE coin_account (
    id           BIGINT  NOT NULL PRIMARY KEY,
    user_id      BIGINT  NOT NULL,
    balance      BIGINT  NOT NULL DEFAULT 0,
    frozen       BIGINT  NOT NULL DEFAULT 0,
    total_income BIGINT  NOT NULL DEFAULT 0,
    total_spent  BIGINT  NOT NULL DEFAULT 0,
    exp          BIGINT  NOT NULL DEFAULT 0,
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME,
    CONSTRAINT uk_coin_account_user UNIQUE (user_id),
    CONSTRAINT ck_coin_balance CHECK (balance >= 0 AND frozen >= 0)
);

-- 流水：业务类型 + 业务ID + 方向 唯一，保证幂等（AC-11）
CREATE TABLE coin_ledger (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    biz_type      VARCHAR(30)  NOT NULL,
    biz_id        VARCHAR(64)  NOT NULL,
    direction     VARCHAR(10)  NOT NULL,
    amount        BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    frozen_after  BIGINT       NOT NULL,
    remark        VARCHAR(200),
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME,
    CONSTRAINT uk_coin_ledger UNIQUE (biz_type, biz_id, direction)
);
CREATE INDEX idx_coin_ledger_user ON coin_ledger (user_id, created_at);
CREATE INDEX idx_coin_ledger_biz ON coin_ledger (biz_id);

-- 冻结单：一个 AI 任务一条（PRD 11.1）
CREATE TABLE coin_freeze (
    id            BIGINT      NOT NULL PRIMARY KEY,
    task_id       VARCHAR(64) NOT NULL,
    user_id       BIGINT      NOT NULL,
    biz_type      VARCHAR(30) NOT NULL,
    amount        BIGINT      NOT NULL,
    charged       BIGINT,
    freeze_status VARCHAR(20) NOT NULL DEFAULT 'FROZEN',
    remark        VARCHAR(200),
    settled_at    DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME,
    CONSTRAINT uk_coin_freeze_task UNIQUE (task_id)
);

CREATE TABLE exp_ledger (
    id         BIGINT      NOT NULL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    biz_type   VARCHAR(30) NOT NULL,
    biz_id     VARCHAR(64) NOT NULL,
    amount     BIGINT      NOT NULL,
    exp_after  BIGINT      NOT NULL,
    remark     VARCHAR(200),
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    CONSTRAINT uk_exp_ledger UNIQUE (biz_type, biz_id)
);
CREATE INDEX idx_exp_ledger_user ON exp_ledger (user_id, created_at);

CREATE TABLE sign_in_record (
    id          BIGINT NOT NULL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    sign_date   DATE   NOT NULL,
    continuous  INT    NOT NULL DEFAULT 1,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_sign_in UNIQUE (user_id, sign_date)
);

-- 充值订单（PRD 11.6）：待支付 → 已支付 → 退款中 → 已退款；待支付超时 30 分钟自动取消
CREATE TABLE pay_order (
    id           BIGINT      NOT NULL PRIMARY KEY,
    order_no     VARCHAR(40) NOT NULL,
    user_id      BIGINT      NOT NULL,
    order_type   VARCHAR(20) NOT NULL DEFAULT 'RECHARGE',
    subject      VARCHAR(100),
    coins        BIGINT      NOT NULL,
    amount_fen   BIGINT      NOT NULL,
    order_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pay_channel  VARCHAR(20),
    channel_txn  VARCHAR(64),
    expire_at    DATETIME    NOT NULL,
    paid_at      DATETIME,
    cancelled_at DATETIME,
    refund_reason VARCHAR(200),
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME,
    CONSTRAINT uk_pay_order_no UNIQUE (order_no)
);
CREATE INDEX idx_pay_order_user ON pay_order (user_id, created_at);

-- 人工调账：必须由另一名财务审批（D4）
CREATE TABLE coin_adjust (
    id            BIGINT       NOT NULL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    amount        BIGINT       NOT NULL,
    reason        VARCHAR(200) NOT NULL,
    ref_task_id   VARCHAR(64),
    adjust_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    approved_by   BIGINT,
    approved_at   DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
