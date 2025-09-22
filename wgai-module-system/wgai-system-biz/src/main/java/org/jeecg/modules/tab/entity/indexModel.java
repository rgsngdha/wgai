package org.jeecg.modules.tab.entity;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author wggg
 * @date 2025/9/17 16:07
 */
@Data
public class indexModel {


    @ApiModelProperty(value = "接入模型数量")
    public Integer modelNmber;

    @ApiModelProperty(value = "接入模型数量")
    public Integer cameraNumber;

    @ApiModelProperty(value = "累计识别数量")
    public Integer idfNumber;


    @ApiModelProperty(value = "累计推送数量")
    public Integer pushNumber;

    @ApiModelProperty(value = "模型识别率 key=name key=idf")
    List<Map<String,Object>> list;


    @ApiModelProperty(value = "模型识别率 key=name key=idf")
    List<TabAiModel> tabAiModel;
}
