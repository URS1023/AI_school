package com.jinbang.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jinbang.account.domain.SignInRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SignInRecordMapper extends BaseMapper<SignInRecord> {
}
