package com.jinbang.ai.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.ai.domain.AiAgent;
import com.jinbang.ai.domain.AiPrompt;
import com.jinbang.ai.mapper.AiAgentMapper;
import com.jinbang.ai.mapper.AiPromptMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/** 智能体配置、计费规则与 Prompt 版本（灰度、回滚）。 */
@Service
@RequiredArgsConstructor
public class AgentService {
    private final AiAgentMapper agentMapper;
    private final AiPromptMapper promptMapper;

    public List<AiAgent> list() {
        return agentMapper.selectList(Wrappers.<AiAgent>lambdaQuery().orderByAsc(AiAgent::getSortNo));
    }

    public List<AiAgent> listFor(CurrentIdentity ci) {
        return list().stream().filter(a -> allowed(a, ci)).toList();
    }

    public AiAgent get(String code) {
        return BizException.notNull(agentMapper.selectOne(Wrappers.<AiAgent>lambdaQuery().eq(AiAgent::getAgentCode, code)),
                "智能体");
    }

    /** 上架状态 + 适用身份校验。学生只能用学生安全模式的智能体（PRD 8.4）。 */
    public AiAgent require(String code, CurrentIdentity ci) {
        AiAgent a = get(code);
        if ("COMING_SOON".equals(a.getAgentStatus())) {
            throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "该智能体即将上线，敬请期待");
        }
        if (!"ONLINE".equals(a.getAgentStatus())) {
            throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "该智能体已下线");
        }
        if (!allowed(a, ci)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return a;
    }

    private boolean allowed(AiAgent a, CurrentIdentity ci) {
        return ci.isPlatform() || Arrays.asList(a.getAllowedTypes().split(",")).contains(ci.type().name());
    }

    /** 预估金额 = 单价 × 预估数量（PER_KCHAR 的数量单位是千字）。 */
    public long estimate(AiAgent a, int units) {
        if ("FREE".equals(a.getPriceMode())) {
            return 0;
        }
        int u = Math.max(1, Math.min(units, a.getMaxUnits()));
        return a.getUnitPrice() * ("PER_CALL".equals(a.getPriceMode()) ? 1 : u);
    }

    public long actual(AiAgent a, int units) {
        if ("FREE".equals(a.getPriceMode()) || units <= 0) {
            return 0;
        }
        return a.getUnitPrice() * ("PER_CALL".equals(a.getPriceMode()) ? 1 : units);
    }

    /** 实际用量换算：PER_KCHAR 按输出字数向上取整到千字。 */
    public int unitsOfText(AiAgent a, String output) {
        if ("PER_KCHAR".equals(a.getPriceMode())) {
            return output == null || output.isEmpty() ? 0 : (output.length() + 999) / 1000;
        }
        return 1;
    }

    // ---------------- Prompt ----------------

    /** 灰度：同一用户稳定落在同一版本（userId 取模）。 */
    public AiPrompt promptFor(String agentCode, Long userId) {
        List<AiPrompt> ps = promptMapper.selectList(Wrappers.<AiPrompt>lambdaQuery().eq(AiPrompt::getAgentCode, agentCode)
                .in(AiPrompt::getPromptStatus, "ACTIVE", "GRAY"));
        AiPrompt active = ps.stream().filter(p -> "ACTIVE".equals(p.getPromptStatus())).findFirst().orElse(null);
        AiPrompt gray = ps.stream().filter(p -> "GRAY".equals(p.getPromptStatus())).findFirst().orElse(null);
        if (gray != null && userId != null && Math.floorMod(userId, 100) < gray.getGrayPercent()) {
            return gray;
        }
        if (active == null) {
            AiPrompt empty = new AiPrompt();
            empty.setContent("你是世纪金榜的教学助手。");
            empty.setVersionNo(0);
            return empty;
        }
        return active;
    }

    public List<AiPrompt> prompts(String agentCode) {
        return promptMapper.selectList(Wrappers.<AiPrompt>lambdaQuery().eq(AiPrompt::getAgentCode, agentCode)
                .orderByDesc(AiPrompt::getVersionNo));
    }

    @Transactional
    public AiPrompt newPrompt(String agentCode, String content, String remark) {
        get(agentCode);
        int next = prompts(agentCode).stream().mapToInt(AiPrompt::getVersionNo).max().orElse(0) + 1;
        AiPrompt p = new AiPrompt();
        p.setAgentCode(agentCode);
        p.setVersionNo(next);
        p.setContent(content);
        p.setRemark(remark);
        p.setPromptStatus("DRAFT");
        p.setGrayPercent(0);
        promptMapper.insert(p);
        return p;
    }

    /** 发布（percent=100）或灰度（1-99）。发布即把原 ACTIVE 版本退役；回滚 = 重新发布旧版本。 */
    @Transactional
    public void release(String agentCode, int versionNo, int percent) {
        AiPrompt p = BizException.notNull(promptMapper.selectOne(Wrappers.<AiPrompt>lambdaQuery()
                .eq(AiPrompt::getAgentCode, agentCode).eq(AiPrompt::getVersionNo, versionNo)), "Prompt 版本");
        if (percent >= 100) {
            promptMapper.update(Wrappers.<AiPrompt>lambdaUpdate().set(AiPrompt::getPromptStatus, "RETIRED")
                    .eq(AiPrompt::getAgentCode, agentCode).in(AiPrompt::getPromptStatus, "ACTIVE", "GRAY"));
            p.setPromptStatus("ACTIVE");
            p.setGrayPercent(100);
        } else {
            promptMapper.update(Wrappers.<AiPrompt>lambdaUpdate().set(AiPrompt::getPromptStatus, "RETIRED")
                    .eq(AiPrompt::getAgentCode, agentCode).eq(AiPrompt::getPromptStatus, "GRAY"));
            p.setPromptStatus("GRAY");
            p.setGrayPercent(Math.max(1, percent));
        }
        promptMapper.updateById(p);
    }

    public AiAgent update(AiAgent patch) {
        AiAgent a = get(patch.getAgentCode());
        if (patch.getAgentStatus() != null) {
            a.setAgentStatus(patch.getAgentStatus());
        }
        if (patch.getUnitPrice() != null) {
            a.setUnitPrice(patch.getUnitPrice());
        }
        if (patch.getPriceMode() != null) {
            a.setPriceMode(patch.getPriceMode());
        }
        if (patch.getModelCode() != null) {
            a.setModelCode(patch.getModelCode());
        }
        if (patch.getAllowedTypes() != null) {
            a.setAllowedTypes(patch.getAllowedTypes());
        }
        if (patch.getSortNo() != null) {
            a.setSortNo(patch.getSortNo());
        }
        agentMapper.updateById(a);
        return a;
    }
}
