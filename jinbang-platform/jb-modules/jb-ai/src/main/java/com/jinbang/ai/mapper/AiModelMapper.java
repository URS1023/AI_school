package com.jinbang.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jinbang.ai.domain.AiModel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {
}
