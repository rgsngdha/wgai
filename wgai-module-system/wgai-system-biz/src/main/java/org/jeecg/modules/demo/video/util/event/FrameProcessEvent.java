package org.jeecg.modules.demo.video.util.event;

import lombok.Data;
import org.bytedeco.javacv.Frame;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.identifyTypeNew;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * @author wggg
 * @date 2025/7/31 17:13
 */
@Data
public class FrameProcessEvent {
    // 原始数据
    // 原始数据
    private Frame frame;
    private BufferedImage image;
    private Mat mat;
    private List<NetPush> netPushList;
    private TabAiSubscriptionNew subscription;
    private identifyTypeNew identifyType;

    // 处理状态和元数据
    private long timestamp;
    private String streamName;
    private boolean processed = false;
    private Exception error;

    /**
     * 安全重置方法 - 只重置状态，不清空数据
     * 数据清理应该在处理完成后进行
     */
    public void resetStatus() {
        this.timestamp = 0;
        this.streamName = null;
        this.processed = false;
        this.error = null;
    }

    /**
     * 完全重置方法 - 谨慎使用，确保没有其他线程在使用数据
     * 通常只在事件完全处理完成后调用
     */
    public void reset() {
        // 先安全释放资源
        cleanupResources();

        // 然后清空引用
        this.frame = null;
        this.image = null;
        this.mat = null;
        this.netPushList = null;
        this.subscription = null;
        this.identifyType = null;
        this.timestamp = 0;
        this.streamName = null;
        this.processed = false;
        this.error = null;
    }

    /**
     * 清理资源但不清空引用 - 用于处理链的最后阶段
     */
    public void cleanupResources() {
        // 释放Frame资源
        if (this.frame != null) {
            try {
                this.frame.close();
            } catch (Exception e) {
                // 忽略释放异常
            }
        }

        // 释放Mat资源
        if (this.mat != null) {
            try {
                this.mat.release();
            } catch (Exception e) {
                // 忽略释放异常
            }
        }

        // BufferedImage通常由GC处理，但可以主动清理
        if (this.image != null) {
            try {
                this.image.flush();
            } catch (Exception e) {
                // 忽略清理异常
            }
        }
    }

    /**
     * 检查事件是否有有效数据
     */
    public boolean hasValidData() {
        return frame != null || image != null || mat != null;
    }

    /**
     * 检查是否有错误
     */
    public boolean hasError() {
        return error != null;
    }
}
