package org.jeecg.modules.demo.train.service;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.demo.train.util.picXml;
import org.jeecg.modules.tab.entity.TabAiModel;

import java.util.List;

/**
 * @Description: 模型预训练
 * @Author: WGAI
 * @Date:   2024-12-17
 * @Version: V1.0
 */
public interface ITabModelTryService extends IService<TabModelTry> {

    public Result<String> savePatch(TabModelTry tabModelTry);

    //保存标注内容
    public Result<String> saveMake(List<picXml> picXml);
    //自动标注内容
    public Result<String> autoSaveMake(List<TabEasyPic> tabEasyPic, TabAiModel tabAiModel);
    //更新标记图片数量
    public Result<String> saveMakeNum();
}
