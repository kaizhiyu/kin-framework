package org.kin.framework.statemachine;

/**
 * @author 健勤
 * @date 2017/8/9
 * 一对多状态转换逻辑处理
 */
@FunctionalInterface
public interface MultipleArcTransition<OPERAND, EVENT, STATE extends Enum<STATE>> {
    /**
     * @param operand 操作
     * @param event   事件
     * @return 操作后的状态
     */
    STATE transition(OPERAND operand, EVENT event);
}
