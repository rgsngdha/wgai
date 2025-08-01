package org.jeecg.modules.demo.video.util.event;

/**
 * @author wggg
 * @date 2025/8/1 9:31
 */

import com.lmax.disruptor.EventFactory;

/**
 * 事件工厂 - 预分配事件对象
 */
public  class FrameEventFactory implements EventFactory<FrameProcessEvent> {
    @Override
    public FrameProcessEvent newInstance() {
        return new FrameProcessEvent();
    }
}
