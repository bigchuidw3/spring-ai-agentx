package com.agentx.ai.samples.v1_1;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 会话测试工具集 — 模拟天气与时间查询，用于验证工具调用链路落库。
 *
 * @author bigchui
 */
@Slf4j
public class SessionTools {

    @Tool(description = "查询指定城市的当前天气")
    public String getWeather(@ToolParam(description = "城市名称，例如：北京、上海、广州") String city) {
        log.info("EXECUTE Tool: getWeather({})", city);
        return switch (city == null ? "" : city) {
            case "北京" -> "北京：晴，气温 26°C，湿度 45%，北风 3 级";
            case "上海" -> "上海：多云，气温 28°C，湿度 65%，东南风 2 级";
            case "广州" -> "广州：雷阵雨，气温 31°C，湿度 80%，南风 4 级";
            default -> city + "：晴间多云，气温 25°C，湿度 50%";
        };
    }
}
