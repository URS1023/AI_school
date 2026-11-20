package com.jinbang.ai.model;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发 / 测试用模拟模型：按系统提示词判断要输出的格式，返回确定性结果，并按小块流式输出。
 * 输入里含 {@code [[fail]]} 模拟模型故障，含 {@code [[empty]]} 模拟空结果。
 */
@Component
public class MockModelProvider implements ModelProvider {
    private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*道");

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public ChatResult chat(ModelSpec spec, List<ChatMessage> messages, Consumer<String> onDelta, BooleanSupplier cancelled) {
        String system = messages.stream().filter(m -> "system".equals(m.role())).map(ChatMessage::content)
                .findFirst().orElse("");
        String user = messages.getLast().content();
        if (user.contains("[[fail]]")) {
            throw new IllegalStateException("mock model failure (simulated upstream timeout)");
        }
        String out = user.contains("[[empty]]") ? "" : answer(system, user);
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < out.length(); i += 8) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return new ChatResult(sent.toString(), user.length(), sent.length(), true);
            }
            String piece = out.substring(i, Math.min(out.length(), i + 8));
            sent.append(piece);
            if (onDelta != null) {
                onDelta.accept(piece);
            }
        }
        return new ChatResult(out, (system.length() + user.length()) / 2, out.length() / 2, false);
    }

    private String answer(String system, String user) {
        if (system.contains("命题专家")) {
            Matcher m = COUNT.matcher(user);
            int n = m.find() ? Math.min(Integer.parseInt(m.group(1)), 30) : 3;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 1; i <= n; i++) {
                if (i > 1) {
                    sb.append(',');
                }
                sb.append("{\"type\":\"SINGLE\",\"stem\":\"第").append(i)
                        .append("题：函数 f(x)=x^2 在 x=").append(i).append(" 处的导数是？\",\"options\":[\"A. ")
                        .append(i).append("\",\"B. ").append(2 * i).append("\",\"C. ").append(i * i)
                        .append("\",\"D. 0\"],\"answer\":\"B\",\"analysis\":\"f'(x)=2x，代入得 ").append(2 * i)
                        .append("\",\"knowledge\":\"导数的计算\"}");
            }
            return sb.append("]").toString();
        }
        if (system.contains("课件设计师")) {
            String topic = user.length() > 30 ? user.substring(0, 30) : user;
            return "{\"title\":\"" + topic.replace("\"", "") + "\",\"chapters\":["
                    + "{\"title\":\"导入\",\"sections\":[{\"title\":\"情境引入\",\"points\":[\"生活实例\",\"提出问题\"]}]},"
                    + "{\"title\":\"新知探究\",\"sections\":[{\"title\":\"核心概念\",\"points\":[\"定义\",\"性质\"]},"
                    + "{\"title\":\"例题精讲\",\"points\":[\"例 1\",\"例 2\"]}]},"
                    + "{\"title\":\"课堂小结\",\"sections\":[{\"title\":\"知识梳理\",\"points\":[\"要点回顾\"]}]}]}";
        }
        if (system.contains("作文阅卷专家")) {
            int len = user.length();
            int score = Math.min(55, 30 + len / 40);
            return "{\"score\":" + score + ",\"overall\":\"立意明确，结构较完整。\",\"dimensions\":{\"content\":"
                    + (score / 3) + ",\"expression\":" + (score / 3) + ",\"structure\":8,\"development\":5},"
                    + "\"highlights\":[\"开头点题\"],\"problems\":[\"个别语句不通顺\"],\"suggestions\":[\"增加细节描写\"],"
                    + "\"sample\":\"（参考范文略）\"}";
        }
        if (system.contains("阅卷老师")) {
            return "{\"items\":[{\"no\":\"1\",\"correct\":true,\"score\":5,\"fullScore\":5,\"comment\":\"正确\"},"
                    + "{\"no\":\"2\",\"correct\":false,\"score\":2,\"fullScore\":10,\"comment\":\"步骤不完整\"}],\"total\":7}";
        }
        String ref = user.contains("【参考资料】") ? "（已参考资料 [1]）" : "";
        return "关于「" + abbreviate(user) + "」：\n\n1. 先明确概念和适用条件；\n2. 结合例题讲解方法；\n3. 设计分层练习巩固。"
                + ref;
    }

    private static String abbreviate(String s) {
        String line = s.lines().reduce((a, b) -> b).orElse(s);
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }
}
