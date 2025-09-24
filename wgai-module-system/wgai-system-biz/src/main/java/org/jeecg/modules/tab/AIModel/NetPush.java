package org.jeecg.modules.tab.AIModel;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecgframework.poi.excel.annotation.Excel;
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

    /**是否跟随前置坐标 0 是 1 否*/
    @Dict(dicCode = "push_static")
    @Excel(name = "是否跟随前置坐标", width = 15)
    @ApiModelProperty(value = "是否跟随前置坐标")
    private Integer isFollow;



    @Excel(name = "跟随最大距离", width = 15)
    @ApiModelProperty(value = "跟随最大距离")
    private Integer followPosition;


    @Dict(dicCode = "push_static")
    @Excel(name = "是否识别预警 默认0 1否", width = 15)
    @ApiModelProperty(value = "是否识别预警 默认0 1否")
    private Integer warinngMethod;


    @Excel(name = "未识别到预警文本", width = 15)
    @ApiModelProperty(value = "未识别到预警文本")
    private String noDifText;

}
