package org.jeecg.modules.demo.train.util;

import lombok.Data;

import java.util.List;

/**
 * @author wggg
 * @date 2024/12/20 17:39
 */
@Data
public class picXml {
    public  String name; //图片名称
    public  String xmin; //x最小值
    public String xmax;//x最大值
    public String ymin;//y最小值
    public String ymax;//y最大值
    public Double ywidth; // 图片原始宽度

    public Double yheight;// 图片原始高度
    public Double canvaswidth;//  canvas原始宽度
    public Double  canvasheight;//  canvas原始高度

    public String modelId;

    public String picId;

    public Integer isMark;

    public String aiModel;

    public  String type; //标记类型

    public List<points> points;
}
