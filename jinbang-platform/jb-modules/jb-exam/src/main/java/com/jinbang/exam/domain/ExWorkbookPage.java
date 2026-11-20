package com.jinbang.exam.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jinbang.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 教辅页面模板（平台内容）。layoutJson 与答题卡版面同结构：{"blocks":[{no,type,objective,fullScore,answer,region}]}。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ex_workbook_page")
public class ExWorkbookPage extends BaseEntity {
    private String bookCode;
    private String bookName;
    private String subject;
    private Integer pageNo;
    private String layoutJson;
}
