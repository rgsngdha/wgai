package org.jeecg.modules.tab.AIModel;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * @author wggg
 * @date 2025/9/30 11:02
 */
public class OnnxModelWrapper {
    private final OrtEnvironment env;
    private final OrtSession session;

    public OnnxModelWrapper(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
    }

    public OrtEnvironment getEnv() {
        return env;
    }

    public OrtSession getSession() {
        return session;
    }
}
