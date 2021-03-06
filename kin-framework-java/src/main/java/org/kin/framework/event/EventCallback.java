package org.kin.framework.event;

/**
 * 事件处理回调
 *
 * @author huangjianqin
 * @date 2020-01-14
 */
public interface EventCallback {
    EventCallback EMPTY = new EventCallback() {
        @Override
        public void finish(Object result) {
            //empty
        }

        @Override
        public void failure(Throwable throwable) {
            //empty
        }
    };

    /**
     * 事件处理完成时触发
     *
     * @param result 事件处理结果
     */
    void finish(Object result);

    /**
     * 事件处理发生异常时触发
     *
     * @param throwable 事件处理时抛出的异常
     */
    void failure(Throwable throwable);
}
