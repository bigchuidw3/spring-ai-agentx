package com.agentx.ai.samples;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TestTools {

    @Tool(description = "获取当前系统信息")
    public String getSystemInfo() {
        log.info("EXECUTE Tool: 获取当前系统信息");

        return "windows 10,CPU I7,内存32GB，固定硬盘1TB，机械硬盘2TB";
    }

    @Tool(description = "根据token获取项目详细信息")
    public String getPrjInfo(String xdrToken) {
        log.info("EXECUTE Tool: 根据token获取项目详细信息");

        return "这是一个测试项目，用于测试agentx的。当前的token= "+xdrToken;
    }
}
