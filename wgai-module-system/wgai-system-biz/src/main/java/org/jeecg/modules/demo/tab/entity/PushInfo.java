package org.jeecg.modules.demo.tab.entity;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.tab.entity.TabAiModel;

import java.util.List;

/**
 * @author Administrator
 * @date 2024/4/9 9:24
 */
@Data
public class PushInfo {
        public String name;
        public String pushId;
        public String videoURL;
        public String pushUrl;
        public List<TabAiModel> tabAiModelList;

        public int time;

        public String indexCode;

        Integer audioStatic;

        Integer pushStatic;

        TabAudioDevice audioId;



}
