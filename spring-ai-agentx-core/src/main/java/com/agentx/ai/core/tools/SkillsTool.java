package com.agentx.ai.core.tools;

import com.agentx.ai.core.utils.MarkdownParser;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 技能加载工具 — 从本地目录或 Classpath 资源加载 SKILL.md 技能文件，
 * 注册为 Spring AI 的 ToolCallback，供 Agent 按需调用。
 *
 * @author bigchui
 */
public class SkillsTool {

	private static final String TOOL_DESCRIPTION_TEMPLATE = """
			在当前会话中加载一个技能（Skill）。本工具的唯一作用是：传入技能名称，获取该技能的完整提示词和工作目录。

			<什么是技能>
			技能是一段专业的提示词，包含特定领域的知识、工作流程和操作指令。
			每个技能通常还附带参考文件、模板、脚本等资源，存放在技能工作目录中。
			</什么是技能>

			<技能的完整使用流程>
			第一步 — 判断是否需要技能：
			  当用户要求完成某项任务时，先检查下方 <可用技能列表> 中是否有匹配的技能。
			  如果有匹配的技能，进入第二步；如果没有，直接用你自身能力回答即可。

			第二步 — 通过本工具加载技能：
			  调用本工具，传入技能的 name 字段值（仅传名称，不含任何参数）。
			  调用后你会收到：技能工作目录路径 + 技能的完整提示词内容。

			第三步 — 阅读并理解技能提示词：
			  仔细阅读技能返回的完整提示词，理解该技能的工作流程和要求。

			第四步 — 按技能提示词执行任务：
			  严格按照技能提示词中的指令和流程来完成任务。
			  你需要像"演员进入角色"一样，完全遵照技能提示词的指引行动。
			  如果技能工作目录中有参考文件、模板、脚本，根据需要读取和使用它们。
			  使用其他工具来完成技能要求的具体操作。
			</技能的完整使用流程>

			<关键概念：技能不是工具>
			技能（Skill）和工具（Tool）是两个不同的概念：
			- 工具：你可以直接调用的能力，如搜索、文件读取等
			- 技能：是一段提示词/指令，通过本工具加载后，你按照其中的指引去行动
			技能本身不是工具，不能被当作工具调用。技能的正确使用方式是：
			用本工具加载 → 阅读提示词 → 按提示词中的指令，使用真正的工具来完成任务
			</关键概念：技能不是工具>

			<严格禁止>
			- 禁止将技能名称当作独立的工具来调用
			- 禁止在未通过本工具加载的情况下，假装已经知道技能的内容
			- 禁止编造或猜测 <可用技能列表> 中不存在的技能名称
			- 禁止重复加载同一个技能（同一技能在一次对话中只需加载一次）
			- 禁止加载技能后忽略其提示词内容，自行发挥
			</严格禁止>

			<可用技能列表>
			%s
			</可用技能列表>
			""";

	public static record SkillsInput(
			@ToolParam(description = "要加载的技能名称（仅传名称，不含任何参数），例如 \"pptx\"") String command) {
	}

	public static class SkillsFunction implements Function<SkillsInput, String> {

		private final Map<String, Skill> skillsMap;

		public SkillsFunction(Map<String, Skill> skillsMap) {
			this.skillsMap = skillsMap;
		}

		@Override
		public String apply(SkillsInput input) {
			Skill skill = this.skillsMap.get(input.command());

			if (skill != null) {
				return "技能工作目录: %s\n\n%s".formatted(skill.basePath(), skill.content());
			}

			String availableNames = String.join(", ", this.skillsMap.keySet());
			return "未找到技能: " + input.command() + "。当前可用技能: " + availableNames;
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final List<Skill> skills = new ArrayList<>();

		private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

		protected Builder() {
		}

		public Builder toolDescriptionTemplate(String template) {
			this.toolDescriptionTemplate = template;
			return this;
		}

		public Builder addSkillsResources(List<Resource> skillsResources) {
			for (Resource resource : skillsResources) {
				try {
					String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
					MarkdownParser parser = new MarkdownParser(markdown);
					this.skills.add(new Skill(deriveBasePathFromUrl(resource.getURL()),
							parser.getFrontMatter(), parser.getContent()));
				}
				catch (IOException e) {
					throw new RuntimeException("加载技能资源失败: " + resource, e);
				}
			}
			return this;
		}

		public Builder addSkillsResource(Resource skillsResource) {
			try {
				String markdown = skillsResource.getContentAsString(StandardCharsets.UTF_8);
				MarkdownParser parser = new MarkdownParser(markdown);
				this.skills.add(new Skill(deriveBasePathFromUrl(skillsResource.getURL()),
						parser.getFrontMatter(), parser.getContent()));
			}
			catch (IOException e) {
				throw new RuntimeException("加载技能资源失败: " + skillsResource, e);
			}
			return this;
		}

		public Builder addSkillsDirectory(String skillsRootDirectory) {
			return this.addSkillsDirectories(List.of(skillsRootDirectory));
		}

		public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
			for (String dir : skillsRootDirectories) {
				this.skills.addAll(loadDirectory(dir));
			}
			return this;
		}

		public ToolCallback build() {
			Assert.notEmpty(this.skills, "至少需要配置一个技能目录或资源");

			String skillsXml = this.skills.stream().map(Skill::toXml).collect(Collectors.joining("\n"));

			return FunctionToolCallback.builder("Skill", new SkillsFunction(toSkillsMap(this.skills)))
				.description(this.toolDescriptionTemplate.formatted(skillsXml))
				.inputType(SkillsInput.class)
				.build();
		}

	}

	// ========== 技能数据模型 ==========

	public static record Skill(String basePath, Map<String, Object> frontMatter, String content) {

		public String name() {
			Object name = this.frontMatter().get("name");
			return name != null ? name.toString() : "";
		}

		public String toXml() {
			String frontMatterXml = this.frontMatter()
				.entrySet()
				.stream()
				.map(e -> "  <%s>%s</%s>".formatted(e.getKey(), e.getValue(), e.getKey()))
				.collect(Collectors.joining("\n"));

			return "<skill>\n%s\n</skill>".formatted(frontMatterXml);
		}

	}

	// ========== 技能加载 ==========

	private static List<Skill> loadDirectory(String skillsRootDirectory) {
		List<Skill> skills = new ArrayList<>();
		Path root = Path.of(skillsRootDirectory);

		if (!Files.isDirectory(root)) {
			return skills;
		}

		try {
			Files.walk(root, FileVisitOption.FOLLOW_LINKS)
				.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
				.forEach(skillFile -> {
					try {
						String markdown = Files.readString(skillFile, StandardCharsets.UTF_8);
						MarkdownParser parser = new MarkdownParser(markdown);
						skills.add(new Skill(skillFile.getParent().toAbsolutePath().toString(),
								parser.getFrontMatter(), parser.getContent()));
					}
					catch (IOException e) {
						throw new RuntimeException("解析技能文件失败: " + skillFile, e);
					}
				});
		}
		catch (IOException e) {
			throw new RuntimeException("扫描技能目录失败: " + skillsRootDirectory, e);
		}

		return skills;
	}

	private static String deriveBasePathFromUrl(URL url) {
		String externalForm = url.toExternalForm();
		if (externalForm.contains("!")) {
			int bangIndex = externalForm.indexOf("!");
			String jarPath = externalForm.substring(0, bangIndex);
			if (jarPath.startsWith("jar:file:")) {
				return jarPath.substring("jar:file:".length());
			}
			return jarPath;
		}
		String path = url.getPath();
		int lastSlash = path.lastIndexOf('/');
		return lastSlash > 0 ? path.substring(0, lastSlash) : path;
	}

	private static Map<String, Skill> toSkillsMap(List<Skill> skills) {
		Map<String, Skill> skillsMap = new HashMap<>();
		for (Skill skill : skills) {
			skillsMap.put(skill.name(), skill);
		}
		return skillsMap;
	}

}
