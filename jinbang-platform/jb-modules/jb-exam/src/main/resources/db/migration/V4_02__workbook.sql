-- 教辅页面模板库（PRD 6.2 ➕P0）：每本教辅的每一页登记题号、作答区 / 得分框坐标、标准答案。
-- 平台内容（运营后台维护），不按学校隔离。二维码格式：JBBOOK:{book_code}:{page_no}

CREATE TABLE ex_workbook_page (
    id          BIGINT       NOT NULL PRIMARY KEY,
    book_code   VARCHAR(50)  NOT NULL,
    book_name   VARCHAR(200) NOT NULL,
    subject     VARCHAR(30),
    page_no     INT          NOT NULL,
    layout_json TEXT         NOT NULL,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_workbook_page UNIQUE (book_code, page_no)
);
