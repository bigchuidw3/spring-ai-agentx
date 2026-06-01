package com.agentx.ai.core.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 任务列表管理工具，灵感来源于 Claude Code 的 TodoWrite。
 * <p>
 * 使 AI 智能体能创建、追踪和更新任务列表，将隐式规划转为显式、可观测的工作流。
 * 校验规则：同一时间只能有一个 in_progress 任务，所有任务必须有有效的 content 和 activeForm。
 * <p>
 * 进度推送由框架自动处理：ToolCallExecutor 在执行完 TodoWrite 后，
 * 自动解析参数并发射 {@link com.agentx.ai.core.model.AgentStreamEvent.TodoProgress} 事件。
 *
 * @author Christian Tzolov (original), bigchui (adapted)
 */
public class TodoWriteTool {

	@Tool(name = "TodoWrite", description = """
		创建和管理结构化任务列表，用于跟踪多步骤任务的进度。

		## 强制执行规则
		1. 收到多步骤任务时，必须先调用此工具创建任务列表（全部 pending），然后再执行任何实际操作
		2. 每开始一个任务前，必须先调用此工具将其标记为 in_progress
		3. 每完成一个任务后，必须立即调用此工具将其标记为 completed，禁止批量更新
		4. 执行过程中发现新步骤时，立即添加到列表

		正确流程：创建列表 → 标记任务1 in_progress → 执行任务1 → 标记任务1 completed → 标记任务2 in_progress → 执行任务2 → ...

		## 使用场景
		- 复杂的多步骤任务（3步以上）
		- 用户提供了多个任务（编号或逗号分隔）
		- 用户明确要求使用任务列表

		## 不使用场景
		- 单一简单任务、信息性问答、3步以内的简单操作

		## 校验规则
		- 同一时间只能有一个 in_progress 任务
		- content 和 activeForm 不能为空
		- 状态值必须是 pending、in_progress 或 completed

		## 参数说明
		每个任务项包含：
		- content：祈使形式，描述需要做什么（如"运行测试"）
		- activeForm：现在进行时形式，执行时显示（如"正在运行测试"）
		- status：pending（未开始）、in_progress（执行中）、completed（已完成）

		## 示例
		<example>
		用户：帮我添加深色模式开关，完成后运行测试
		助手：
		1. 调用 TodoWrite 创建列表：[添加深色模式开关(pending), 实现主题切换(pending), 运行测试(pending)]
		2. 调用 TodoWrite 标记：[添加深色模式开关(in_progress), 实现主题切换(pending), 运行测试(pending)]
		3. 执行添加开关的操作
		4. 调用 TodoWrite 更新：[添加深色模式开关(completed), 实现主题切换(in_progress), 运行测试(pending)]
		5. 执行主题切换操作
		6. 以此类推，逐步完成所有任务
		</example>
		""")
	public String todoWrite(
			@ToolParam(description = "任务列表，包含所有任务的当前状态") List<TodoItem> todos) { // @formatter:on
		validateTodos(todos);
		return "任务列表已成功更新。请使用任务列表跟踪你的进度，并继续执行当前的任务。";
	}

	private void validateTodos(List<TodoItem> todos) {
		if (todos == null || todos.isEmpty()) {
			throw new IllegalArgumentException("任务列表不能为空");
		}

		for (int i = 0; i < todos.size(); i++) {
			TodoItem item = todos.get(i);

			if (item == null) {
				throw new IllegalArgumentException("索引 " + i + " 处的任务为 null");
			}
			if (item.content() == null || item.content().isBlank()) {
				throw new IllegalArgumentException(
						"索引 " + i + " 处的任务 content 为空或空白，所有任务必须有有意义的内容。");
			}
			if (item.activeForm() == null || item.activeForm().isBlank()) {
				throw new IllegalArgumentException("索引 " + i + " 处的任务 activeForm 为空或空白，"
						+ "所有任务必须提供 activeForm（现在进行时形式）。");
			}
			if (item.status() == null) {
				throw new IllegalArgumentException("索引 " + i
						+ " 处的任务 status 为 null，状态必须是 pending、in_progress 或 completed 之一。");
			}
		}

		long inProgressCount = todos.stream()
				.filter(item -> item.status() == Status.in_progress)
				.count();

		if (inProgressCount > 1) {
			throw new IllegalArgumentException("同一时间只能有一个任务处于 in_progress 状态。当前有 " + inProgressCount
					+ " 个 in_progress 任务。请先将当前任务标记为 completed 再开始新任务。");
		}
	}

	// ==================== 数据模型 ====================

	public record TodoItem(
			@ToolParam(description = "任务内容，祈使形式（如\"运行测试\"）") String content,
			@ToolParam(description = "任务状态：pending（未开始）、in_progress（执行中）、completed（已完成）") Status status,
			@ToolParam(description = "执行时显示的现在进行时形式（如\"正在运行测试\"）") String activeForm) {
	}

	public enum Status {
		pending, in_progress, completed
	}

	// ==================== 工厂方法 ====================

	/**
	 * 创建 TodoWriteTool。
	 * <p>
	 * 框架在流式执行时自动发射 {@link com.agentx.ai.core.model.AgentStreamEvent.TodoProgress} 事件，
	 * 无需手动处理进度回调。
	 */
	public static ToolCallback[] create() {
		return ToolCallbacks.from(new TodoWriteTool());
	}
}
