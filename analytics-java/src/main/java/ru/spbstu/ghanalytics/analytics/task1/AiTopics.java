package ru.spbstu.ghanalytics.analytics.task1;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GitHub topics that mark a repository as AI-related, split into four groups by when they appear and what they
 * mean. The lists are the AI topics present in Innovation Graph data (2020Q1 - 2026Q2); topics absent from the
 * data simply count zero.
 */
public final class AiTopics {

    public enum Group {
        /** Machine learning before LLMs. */
        CLASSIC_ML("classic_ml"),
        /** "ai" itself: grows with every wave, so kept apart. */
        GENERAL_AI("general_ai"),
        /** LLMs, their APIs and tooling: the ChatGPT wave (from 2023). */
        LLM("llm"),
        /** AI agents and AI tools that write code: the agent wave (from 2025). */
        AGENTS("agents");

        private final String column;

        Group(String column) {
            this.column = column;
        }

        /** Column name in the result tables. */
        public String column() {
            return column;
        }
    }

    private static final Map<String, Group> GROUPS = Stream.of(
            topics(Group.CLASSIC_ML, "machine-learning", "deep-learning", "neural-network", "neural-networks",
                    "pytorch", "tensorflow", "keras", "scikit-learn", "computer-vision", "nlp",
                    "natural-language-processing", "reinforcement-learning"),
            topics(Group.GENERAL_AI, "ai", "artificial-intelligence"),
            topics(Group.LLM, "llm", "llms", "large-language-models", "openai", "openai-api", "chatgpt",
                    "chatgpt-api", "gpt", "gpt-4", "langchain", "rag", "generative-ai", "genai", "prompt-engineering",
                    "huggingface", "transformers", "ollama", "llama", "gemini", "gemini-api", "claude", "anthropic"),
            topics(Group.AGENTS, "ai-agents", "ai-agent", "agent", "agents", "agentic-ai", "multi-agent",
                    "autonomous-agents", "mcp", "mcp-server", "model-context-protocol", "claude-code",
                    "claude-code-plugin", "claude-skills", "agent-skills", "cursor", "copilot", "github-copilot",
                    "vibe-coding", "coding-agent", "ai-coding"))
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private AiTopics() {
    }

    public static Optional<Group> group(String topic) {
        return Optional.ofNullable(GROUPS.get(topic));
    }

    public static Set<String> topics(Group group) {
        return GROUPS.entrySet().stream()
                .filter(e -> e.getValue() == group)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static Map<String, Group> topics(Group group, String... names) {
        return Stream.of(names).collect(Collectors.toMap(n -> n, n -> group));
    }
}
