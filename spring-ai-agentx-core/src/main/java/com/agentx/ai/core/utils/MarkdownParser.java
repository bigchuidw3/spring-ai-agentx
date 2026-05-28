/*
* Copyright 2026 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.agentx.ai.core.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Parser for Markdown documents with optional YAML front matter.
 *
 * <p>Extracts YAML front matter (metadata) and content from Markdown documents.
 * Front matter is delimited by triple dashes (---) at the beginning of the document.
 *
 * <p>Source: spring-ai-agent-utils (org.springaicommunity.agent.utils)
 *
 * @author bigchui
 */
public class MarkdownParser {

	private Map<String, Object> frontMatter;
	private String content;

	public MarkdownParser(String markdown) {

		frontMatter = new HashMap<>();
		content = "";

		if (markdown == null || markdown.isEmpty()) {
			return;
		}

		if (markdown.startsWith("---")) {
			int endIndex = markdown.indexOf("---", 3);

			if (endIndex != -1) {
				String frontMatterSection = markdown.substring(3, endIndex).trim();
				parseFrontMatter(frontMatterSection);
				content = markdown.substring(endIndex + 3).trim();
			}
			else {
				content = markdown;
			}
		}
		else {
			content = markdown;
		}
	}

	private void parseFrontMatter(String frontMatterSection) {
		String[] lines = frontMatterSection.split("\n");

		for (String line : lines) {
			line = line.trim();

			if (line.isEmpty()) {
				continue;
			}

			int colonIndex = line.indexOf(':');
			if (colonIndex > 0) {
				String key = line.substring(0, colonIndex).trim();
				String value = line.substring(colonIndex + 1).trim();
				value = removeQuotes(value);
				frontMatter.put(key, value);
			}
		}
	}

	private String removeQuotes(String value) {
		if (value.length() >= 2) {
			if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}

	public Map<String, Object> getFrontMatter() {
		return new HashMap<>(frontMatter);
	}

	public String getContent() {
		return content;
	}
}
