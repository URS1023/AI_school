package com.jinbang.resource.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量录题解析（手册第 7 章）：用标记切分
 * 【题型】【难度】【题文】【选项】【答案】【解析】【题组】【题组小题】【题组结束】。
 * 先解析成「检查区」预览，有错误就逐题指出，教师改完再保存。
 */
public final class QuestionImportParser {
    private static final Pattern TAG = Pattern.compile("【(题型|难度|题文|选项|答案|解析|题组|题组小题|题组结束)】");
    private static final Pattern OPTION = Pattern.compile("^\\s*([A-H])\\s*[.．、:：]\\s*(.*)$");

    public static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("单选题", "SINGLE"), Map.entry("单项选择", "SINGLE"), Map.entry("单项选择题", "SINGLE"),
            Map.entry("单选", "SINGLE"), Map.entry("多选题", "MULTIPLE"), Map.entry("多项选择", "MULTIPLE"),
            Map.entry("多项选择题", "MULTIPLE"), Map.entry("双项选择", "MULTIPLE"), Map.entry("判断题", "JUDGE"),
            Map.entry("判断", "JUDGE"), Map.entry("填空题", "BLANK"), Map.entry("填空", "BLANK"),
            Map.entry("解答题", "ANSWER"), Map.entry("解答", "ANSWER"), Map.entry("计算题", "ANSWER"),
            Map.entry("简答题", "SHORT"), Map.entry("实验题", "EXPERIMENT"), Map.entry("作文", "ESSAY"),
            Map.entry("写作", "ESSAY"), Map.entry("书面表达", "ESSAY"), Map.entry("完形填空", "CLOZE"),
            Map.entry("阅读理解", "READING"), Map.entry("文言文阅读", "CLASSICAL"), Map.entry("名篇名句默写", "DICTATION"),
            Map.entry("古代诗歌阅读", "POETRY"), Map.entry("语言文字运用", "EXPRESSION"), Map.entry("非选择题", "NON_CHOICE"));
    public static final Map<String, String> DIFFICULTY = Map.of("容易", "1", "较易", "2", "中等", "3", "一般", "3",
            "较难", "4", "困难", "5");

    private QuestionImportParser() {
    }

    public record ParsedQuestion(int index, String type, String typeLabel, String difficulty, String stem,
                                 List<String> options, String answer, String analysis, Integer groupIndex,
                                 String groupStem) {
    }

    public record ParseError(int index, String message) {
    }

    public record Result(List<ParsedQuestion> questions, List<ParseError> errors) {
    }

    private static final class Draft {
        String typeLabel;
        String difficulty;
        String stem;
        String options;
        String answer;
        String analysis;

        boolean isEmpty() {
            return typeLabel == null && stem == null && options == null && answer == null && analysis == null;
        }
    }

    public static Result parse(String text) {
        List<ParsedQuestion> out = new ArrayList<>();
        List<ParseError> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add(new ParseError(0, "输入区为空"));
            return new Result(out, errors);
        }
        Matcher m = TAG.matcher(text);
        List<String[]> segs = new ArrayList<>();
        int lastEnd = -1;
        String lastTag = null;
        while (m.find()) {
            if (lastTag != null) {
                segs.add(new String[]{lastTag, text.substring(lastEnd, m.start()).trim()});
            }
            lastTag = m.group(1);
            lastEnd = m.end();
        }
        if (lastTag != null) {
            segs.add(new String[]{lastTag, text.substring(lastEnd).trim()});
        }
        if (segs.isEmpty()) {
            errors.add(new ParseError(0, "没有找到任何标记，请按【题型】【题文】【答案】等标记录入"));
            return new Result(out, errors);
        }
        Draft d = new Draft();
        Integer groupIndex = null;
        String groupStem = null;
        int groupSeq = 0;
        for (String[] seg : segs) {
            String tag = seg[0];
            String val = seg[1];
            switch (tag) {
                case "题型" -> {
                    if (!d.isEmpty()) {
                        finish(d, out, errors, groupIndex, groupStem);
                        d = new Draft();
                    }
                    d.typeLabel = val;
                }
                case "难度" -> d.difficulty = val;
                case "题文" -> {
                    if (d.stem != null) {
                        finish(d, out, errors, groupIndex, groupStem);
                        String keepType = d.typeLabel;
                        d = new Draft();
                        d.typeLabel = keepType;
                    }
                    d.stem = val;
                }
                case "选项" -> d.options = val;
                case "答案" -> d.answer = val;
                case "解析" -> d.analysis = val;
                case "题组" -> {
                    if (!d.isEmpty()) {
                        finish(d, out, errors, groupIndex, groupStem);
                        d = new Draft();
                    }
                    groupIndex = ++groupSeq;
                    groupStem = val;
                }
                case "题组小题" -> {
                    if (!d.isEmpty()) {
                        finish(d, out, errors, groupIndex, groupStem);
                        d = new Draft();
                    }
                }
                case "题组结束" -> {
                    if (!d.isEmpty()) {
                        finish(d, out, errors, groupIndex, groupStem);
                        d = new Draft();
                    }
                    groupIndex = null;
                    groupStem = null;
                }
                default -> {
                }
            }
        }
        if (!d.isEmpty()) {
            finish(d, out, errors, groupIndex, groupStem);
        }
        return new Result(out, errors);
    }

    private static void finish(Draft d, List<ParsedQuestion> out, List<ParseError> errors, Integer groupIndex,
                               String groupStem) {
        // 出错的题也用占位占一个序号，所以题号 = 已处理题数 + 1，和输入区的顺序一致
        int idx = out.size() + 1;
        String typeLabel = d.typeLabel == null ? "" : d.typeLabel.trim();
        String type = TYPES.get(typeLabel);
        List<String> msgs = new ArrayList<>();
        if (typeLabel.isEmpty()) {
            msgs.add("缺少【题型】");
        } else if (type == null) {
            type = "OTHER";
        }
        String diff = null;
        if (d.difficulty != null && !d.difficulty.isBlank()) {
            diff = DIFFICULTY.get(d.difficulty.trim());
            if (diff == null) {
                msgs.add("难度只能是：容易 / 较易 / 中等 / 较难 / 困难");
            }
        }
        if (d.stem == null || d.stem.isBlank()) {
            msgs.add("缺少【题文】");
        }
        List<String> options = new ArrayList<>();
        if (d.options != null) {
            for (String line : d.options.split("\\R")) {
                Matcher om = OPTION.matcher(line);
                if (om.matches()) {
                    options.add(om.group(1) + ". " + om.group(2).trim());
                } else if (!line.isBlank() && !options.isEmpty()) {
                    int last = options.size() - 1;
                    options.set(last, options.get(last) + " " + line.trim());
                }
            }
        }
        boolean choice = "SINGLE".equals(type) || "MULTIPLE".equals(type);
        if (choice && options.size() < 2) {
            msgs.add("选择题至少需要 2 个选项（每行以 A. B. 开头）");
        }
        if (choice && d.answer != null && !d.answer.isBlank()) {
            for (char c : d.answer.replaceAll("[\\s,，、]", "").toCharArray()) {
                if (c - 'A' < 0 || c - 'A' >= options.size()) {
                    msgs.add("答案「" + d.answer.trim() + "」不在选项范围内");
                    break;
                }
            }
        }
        if (!msgs.isEmpty()) {
            msgs.forEach(msg -> errors.add(new ParseError(idx, "第 " + idx + " 题：" + msg)));
            placeholder(out, idx);
            return;
        }
        out.add(new ParsedQuestion(idx, type, typeLabel, diff == null ? "3" : diff, d.stem.trim(), options,
                d.answer == null ? null : d.answer.trim(), d.analysis == null ? null : d.analysis.trim(), groupIndex,
                groupStem));
    }

    private static void placeholder(List<ParsedQuestion> out, int n) {
        // 用 null 占位保证后续题号连续；保存前会过滤掉
        out.add(new ParsedQuestion(n, null, null, null, null, List.of(), null, null, null, null));
    }

    /** 去掉占位后的有效题目。 */
    public static List<ParsedQuestion> valid(Result r) {
        return r.questions().stream().filter(q -> q.stem() != null).toList();
    }
}
