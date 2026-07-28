package com.agentx.ai.samples.v1_1;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 压缩测试专用工具集 — 提供可控大小的工具响应，配合 CompressionLayerTest 触发各层压缩。
 *
 * @author bigchui
 */
@Slf4j
public class CompressionTestTools {

    /**
     * 小响应（约 30-50 字符）— 用于累积触发 L1/L4/L6，但不触发 L2/L3/L5。
     */
    @Tool(description = "查询指定城市的当前天气，返回简短信息")
    public String getWeather(@ToolParam(description = "城市名称") String city) {
        log.info("EXECUTE Tool: getWeather({})", city);
        return switch (city == null ? "" : city) {
            case "北京" -> "北京：晴，26°C，湿度 45%，北风 3 级";
            case "上海" -> "上海：多云，28°C，湿度 65%，东南风 2 级";
            case "广州" -> "广州：雷阵雨，31°C，湿度 80%，南风 4 级";
            case "深圳" -> "深圳：晴间多云，30°C，湿度 70%，东风 3 级";
            case "杭州" -> "杭州：阴，25°C，湿度 60%，北风 2 级";
            case "南京" -> "南京：小雨，23°C，湿度 75%，东北风 3 级";
            case "成都" -> "成都：多云，24°C，湿度 70%，南风 2 级";
            default -> city + "：晴间多云，25°C，湿度 50%";
        };
    }

    /**
     * 大响应（约 3000 字符）— 用于触发 L2/L3/L5 单条大消息 offload。
     */
    @Tool(description = "读取一份长文档的完整内容（用于测试大消息压缩）")
    public String readLargeDocument(
            @ToolParam(description = "文档名称，例如：project_plan、tech_spec、meeting_notes") String name) {
        log.info("EXECUTE Tool: readLargeDocument({})", name);
        StringBuilder sb = new StringBuilder();
        sb.append("【文档：").append(name).append("】\n\n");
        sb.append("本文档为测试用长文本，用于验证大消息压缩策略。\n\n");
        for (int i = 1; i <= 30; i++) {
            sb.append(String.format("第 %d 章 项目第 %d 阶段的工作内容与里程碑\n", i, i));
            sb.append(String.format(
                    "本阶段主要完成以下任务：需求调研、方案设计、技术选型、原型开发、性能测试、用户验收。" +
                    "在每个环节都需要相关人员的密切配合，并按既定时间节点交付成果物。" +
                    "如遇重大问题需要及时升级决策，避免影响整体进度。\n\n", i));
            sb.append(String.format(
                    "关键产出物：需求文档 v%d、设计文档 v%d、测试报告 v%d、用户手册 v%d。" +
                    "所有文档需提交至文档库并通知所有相关方。" +
                    "代码需要通过代码评审并合并到主分支，部署到测试环境供验证。\n\n", i, i, i, i));
        }
        sb.append("【文档结束】");
        return sb.toString();
    }

    /**
     * 中等响应（约 200-400 字符）— 用于在 L5/L6 测试中累积。
     */
    @Tool(description = "查询城市的详细介绍（景点、美食、文化）")
    public String getCityDetail(@ToolParam(description = "城市名称") String city) {
        log.info("EXECUTE Tool: getCityDetail({})", city);
        return switch (city == null ? "" : city) {
            case "北京" -> "北京是中国的首都，拥有故宫、长城、颐和园等著名景点。" +
                    "美食包括北京烤鸭、炸酱面、豆汁焦圈。" +
                    "文化上融合了明清皇家文化与胡同市井文化，是政治文化中心。";
            case "上海" -> "上海是中国最大的经济中心，外滩、东方明珠、迪士尼是标志性景点。" +
                    "美食包括小笼包、生煎、本帮菜。" +
                    "文化上融合了江南传统文化与近代海派文化，是国际金融与航运中心。";
            default -> city + "：本地特色景点、美食与文化介绍。详细内容暂略。";
        };
    }

    @Tool(description = "根据城市名称查询天气站编码，返回短字符串")
    public String getCityCode(@ToolParam(description = "城市名称") String city) {
        log.info("EXECUTE Tool: getCityCode({})", city);
        return switch (city == null ? "" : city) {
            case "北京" -> "BJ-101";
            case "上海" -> "SH-201";
            case "广州" -> "GZ-301";
            case "深圳" -> "SZ-401";
            default -> "UNKNOWN-000";
        };
    }

    @Tool(description = "根据天气站编码查询天空状况，返回短字符串")
    public String getCurrentSky(@ToolParam(description = "天气站编码") String cityCode) {
        log.info("EXECUTE Tool: getCurrentSky({})", cityCode);
        return switch (cityCode == null ? "" : cityCode) {
            case "BJ-101" -> "晴";
            case "SH-201" -> "多云";
            case "GZ-301" -> "雷阵雨";
            case "SZ-401" -> "晴间多云";
            default -> "未知";
        };
    }

    @Tool(description = "根据天气站编码查询湿度，返回短字符串")
    public String getCurrentHumidity(@ToolParam(description = "天气站编码") String cityCode) {
        log.info("EXECUTE Tool: getCurrentHumidity({})", cityCode);
        return switch (cityCode == null ? "" : cityCode) {
            case "BJ-101" -> "湿度45%";
            case "SH-201" -> "湿度65%";
            case "GZ-301" -> "湿度80%";
            case "SZ-401" -> "湿度70%";
            default -> "湿度未知";
        };
    }

    @Tool(description = "根据天气站编码查询风力，返回短字符串")
    public String getCurrentWind(@ToolParam(description = "天气站编码") String cityCode) {
        log.info("EXECUTE Tool: getCurrentWind({})", cityCode);
        return switch (cityCode == null ? "" : cityCode) {
            case "BJ-101" -> "北风3级";
            case "SH-201" -> "东南风2级";
            case "GZ-301" -> "南风4级";
            case "SZ-401" -> "东风3级";
            default -> "风力未知";
        };
    }
}
