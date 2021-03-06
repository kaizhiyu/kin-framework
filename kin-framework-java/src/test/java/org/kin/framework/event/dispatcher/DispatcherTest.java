package org.kin.framework.event.dispatcher;


import org.kin.framework.event.EventCallback;
import org.kin.framework.event.ParallelEventDispatcher;
import org.kin.framework.utils.SysUtils;

/**
 * Created by 健勤 on 2017/8/10.
 */
public class DispatcherTest {
    public static void main(String[] args) throws InterruptedException {
        ParallelEventDispatcher dispatcher = new ParallelEventDispatcher(SysUtils.getSuitableThreadNum(), true);
//        dispatcher.register(FirstEvent.class, new FirstEventHandler(), FirstEventHandler.class.getMethods()[0]);
        dispatcher.register(SecondEvent.class, new SecondEventHandler(), SecondEventHandler.class.getMethods()[0]);
        dispatcher.register(ThirdEvent.class, new ThirdEventHandler(), ThirdEventHandler.class.getMethods()[0]);

        dispatcher.dispatch(new SecondEvent(SecondEventType.S), new EventCallback() {
            @Override
            public void finish(Object result) {
                System.out.println(result);
            }

            @Override
            public void failure(Throwable throwable) {
                throwable.printStackTrace();
            }
        });
        dispatcher.dispatch(new SecondEvent(SecondEventType.C));
        dispatcher.dispatch(new SecondEvent(SecondEventType.D));

        Thread.sleep(2000);

        dispatcher.shutdown();
    }
}
