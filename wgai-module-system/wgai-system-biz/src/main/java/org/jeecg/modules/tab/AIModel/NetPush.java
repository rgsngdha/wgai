package org.jeecg.modules.tab.AIModel;

import lombok.Data;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.opencv.dnn.Net;

import java.util.List;

/**
 * @author wggg
 * @date 2025/2/24 11:26
 */
@Data
public class NetPush {

    String id;

    Integer index;
    Net net;
    String modelType;

    List<String> claseeNames;

    Integer isBefor;

    String beforText;

    List<NetPush> listNetPush;

    TabAiModel tabAiModel;

    String uploadPath;
}
