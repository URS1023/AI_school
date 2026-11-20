package com.jinbang.operation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jinbang.operation.domain.MsgMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MsgMessageMapper extends BaseMapper<MsgMessage> {
}
