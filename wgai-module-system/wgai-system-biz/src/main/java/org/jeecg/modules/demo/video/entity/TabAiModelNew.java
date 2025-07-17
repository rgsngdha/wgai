package org.jeecg.modules.demo.video.entity;

import lombok.Data;
import org.jeecg.modules.tab.entity.TabAiModel;

import java.util.List;

/**
 * @author wggg
 * @date 2025/5/22 19:58
 */
@Data
public class TabAiModelNew {


    /***
     * 是否是前置内容
     */
    Integer isBefore;

    List<TabAiModel> tabAiModelList;

    TabAiModel tabAiModel;
}
