package com.jinbang.common.statemachine;

import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 轻量「枚举 + 转移表」状态机（技术路线 2：COLA StateMachine 或自研）。
 * 作业、考试、订单、资源审核都用它；非法转移统一抛 {@link ErrorCode#STATE_TRANSITION_DENIED}。
 */
public final class StateMachine<S extends Enum<S>, E extends Enum<E>> {
    private final String name;
    private final Map<S, Map<E, S>> table;

    private StateMachine(String name, Map<S, Map<E, S>> table) {
        this.name = name;
        this.table = table;
    }

    public static <S extends Enum<S>, E extends Enum<E>> Builder<S, E> builder(String name, Class<S> stateType) {
        return new Builder<>(name, stateType);
    }

    public Optional<S> next(S from, E event) {
        return Optional.ofNullable(table.getOrDefault(from, Collections.emptyMap()).get(event));
    }

    public S fire(S from, E event) {
        return next(from, event).orElseThrow(() -> new BizException(ErrorCode.STATE_TRANSITION_DENIED,
                "当前状态「" + label(from) + "」不允许执行该操作"));
    }

    public boolean can(S from, E event) {
        return next(from, event).isPresent();
    }

    public Set<E> allowedEvents(S from) {
        return table.getOrDefault(from, Collections.emptyMap()).keySet();
    }

    public String name() {
        return name;
    }

    private String label(S s) {
        if (s instanceof Labeled l) {
            return l.label();
        }
        return s.name();
    }

    /** 状态枚举实现该接口后，报错提示里显示中文名。 */
    public interface Labeled {
        String label();
    }

    public static final class Builder<S extends Enum<S>, E extends Enum<E>> {
        private final String name;
        private final Map<S, Map<E, S>> table;

        private Builder(String name, Class<S> stateType) {
            this.name = name;
            this.table = new EnumMap<>(stateType);
        }

        public Builder<S, E> on(S from, E event, S to) {
            table.computeIfAbsent(from, k -> new HashMap<>()).put(event, to);
            return this;
        }

        @SafeVarargs
        public final Builder<S, E> onAny(E event, S to, S... froms) {
            for (S from : froms) {
                on(from, event, to);
            }
            return this;
        }

        public StateMachine<S, E> build() {
            return new StateMachine<>(name, table);
        }
    }
}
