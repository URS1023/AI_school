package com.jinbang.common.datascope;

import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;

/** 由系统模块实现：根据当前身份的角色和任教关系计算可见范围。 */
public interface DataScopeProvider {

    VisibleScope current();

    /** 接口层二次校验（R8）：看不到就直接 403，而不是返回空列表让前端误以为没数据。 */
    default void checkClass(Long classId, String subject) {
        if (!current().canSee(classId, subject)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }
}
