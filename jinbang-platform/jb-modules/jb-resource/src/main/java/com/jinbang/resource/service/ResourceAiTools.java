package com.jinbang.resource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.ai.AiToolProvider;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.resource.domain.QQuestion;
import com.jinbang.resource.domain.ResResource;
import com.jinbang.resource.mapper.QQuestionMapper;
import com.jinbang.resource.mapper.ResResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 智能问答的题库检索、教学资源查询工具（PRD 7.2 意图路由）。 */
@Configuration
@RequiredArgsConstructor
public class ResourceAiTools {
    private static final Pattern QUESTION_INTENT = Pattern.compile("(题|练习|试卷|习题)");
    private static final Pattern RESOURCE_INTENT = Pattern.compile("(资源|课件|教案|学案|微课|素材)");
    private static final Pattern STOP = Pattern.compile("(帮我|请|生成|找|一些|几道|五道|道|高[一二三]|初[一二三]|的|有哪些|推荐|相关|题目?|资源|课件|教案)");

    private final QQuestionMapper questionMapper;
    private final ResResourceMapper resourceMapper;

    static String keyword(String q) {
        String k = STOP.matcher(q).replaceAll(" ").trim();
        String[] parts = k.split("\\s+");
        String longest = "";
        for (String p : parts) {
            if (p.length() > longest.length()) {
                longest = p;
            }
        }
        return longest.length() > 12 ? longest.substring(0, 12) : longest;
    }

    @Bean
    AiToolProvider questionBankTool() {
        return new AiToolProvider() {
            @Override
            public String name() {
                return "题库检索";
            }

            @Override
            public boolean matches(String agentCode, String question) {
                return "ask".equals(agentCode) && QUESTION_INTENT.matcher(question).find();
            }

            @Override
            public String run(CurrentIdentity identity, String question) {
                String kw = keyword(question);
                List<QQuestion> qs = questionMapper.selectList(Wrappers.<QQuestion>lambdaQuery()
                        .eq(QQuestion::getQScope, "PLATFORM").eq(QQuestion::getDeleted, false)
                        .and(!kw.isEmpty(), w -> w.like(QQuestion::getKnowledge, kw).or().like(QQuestion::getStem, kw))
                        .orderByDesc(QQuestion::getUsedCount).last("LIMIT 5"));
                return qs.stream().map(q -> "题目#" + q.getId() + "（" + q.getQType() + "）" + q.getStem() + " 答案：" + q.getAnswer())
                        .collect(Collectors.joining("\n"));
            }
        };
    }

    @Bean
    AiToolProvider resourceTool() {
        return new AiToolProvider() {
            @Override
            public String name() {
                return "教学资源查询";
            }

            @Override
            public boolean matches(String agentCode, String question) {
                return "ask".equals(agentCode) && RESOURCE_INTENT.matcher(question).find();
            }

            @Override
            public String run(CurrentIdentity identity, String question) {
                String kw = keyword(question);
                List<ResResource> rs = resourceMapper.selectList(Wrappers.<ResResource>lambdaQuery()
                        .eq(ResResource::getResScope, "PLATFORM").eq(ResResource::getResStatus, "ONLINE")
                        .like(!kw.isEmpty(), ResResource::getTitle, kw).orderByDesc(ResResource::getDownloadCount)
                        .last("LIMIT 5"));
                return rs.stream().map(r -> "资源#" + r.getId() + " 《" + r.getTitle() + "》（" + r.getResType() + "）")
                        .collect(Collectors.joining("\n"));
            }
        };
    }
}
