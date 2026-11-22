package ru.spbstu.ghanalytics.analytics.task1;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which GitHub topics count as AI, and which topics are not about a subject at all.
 * <p>
 * How the AI list is built: a topic whose name has an AI word ({@link #CANDIDATE}: ai, llm, agent, neural, ...) counts
 * as AI unless it is in {@link #REJECTED_CANDIDATES} (reviewed by hand: state-machine, LoRa radio, statistical
 * inference, ...). Its group follows from the words of the name, checked in order: agents and coding tools, then
 * LLM / generative AI, then classic ML, otherwise generic AI. {@link #GROUPS} adds AI topics without such a word
 * (chatgpt, artificial-intelligence) and fixes a few groups by hand. Task1Main writes every candidate of the data
 * with its decision to {@code ai_topic_review.csv}, so the list can be checked after each data update.
 */
public final class AiTopics {

    public enum Group {
        /** Machine learning before LLMs: models, libraries, classic tasks. */
        CLASSIC_ML("classic_ml"),
        /** Generic AI labels ("ai", "chatbot", "ai-tools"): grow with every wave, so kept apart. */
        GENERAL_AI("general_ai"),
        /** LLMs and generative AI, their APIs and tooling: the ChatGPT wave (from 2023). */
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

    /** Topic names that may be about AI; each match is reviewed (included in a group or rejected with a reason). */
    public static final Pattern CANDIDATE = Pattern.compile("(^|-)(ai|ml|llm|llms|gpt|nlp|rag|agent|agents|agentic"
            + "|mcp|neural|deep|machine|learning|transformers?|diffusion|vision|chatbot|copilot|codex|claude|gemini"
            + "|openai|anthropic|deepseek|qwen|mistral|llama|ollama|langchain|langgraph|llamaindex|huggingface"
            + "|embeddings?|vector|prompt|genai|generative|whisper|yolo|speech|cursor|vibe|autogen|crewai|lora"
            + "|fine-tuning|inference|onnx|tensor|tensorflow|pytorch|keras|sklearn|scikit)($|-)");

    /** Candidates that are not counted as AI, with the reason. */
    public static final Map<String, String> REJECTED_CANDIDATES = Map.ofEntries(
            Map.entry("learning", "generic: study and course repositories"),
            Map.entry("learning-by-doing", "generic: study repositories"),
            Map.entry("language-learning", "learning human languages"),
            Map.entry("tensor", "generic math (tensors), not specific to ML"),
            Map.entry("inference", "ambiguous: statistical and model inference"),
            Map.entry("bayesian-inference", "statistics"),
            Map.entry("causal-inference", "statistics"),
            Map.entry("vector", "generic math and graphics"),
            Map.entry("vision", "ambiguous: product or company vision"),
            Map.entry("prompt", "ambiguous: shell and CLI prompts"),
            Map.entry("virtual-machine", "virtualization"),
            Map.entry("state-machine", "finite-state machines"),
            Map.entry("lora", "ambiguous: LoRa radio (IoT) and LoRA fine-tuning"),
            Map.entry("apm-agent", "application performance monitoring agent"),
            Map.entry("generative-art", "algorithmic art, mostly without AI"),
            Map.entry("open-vibe-anything-cool-creative", "unclear meaning"));

    private static final Set<String> AGENT_WORDS = Set.of("agent", "agents", "agentic", "mcp", "codex", "cursor",
            "copilot", "vibe", "crewai", "autogen", "langgraph", "coding");
    /** "claude-code", "gemini-cli", "codex-skill": a model name with a tool word is a coding tool, not the model. */
    private static final Set<String> MODEL_WORDS = Set.of("claude", "codex", "gemini");
    private static final Set<String> TOOL_WORDS = Set.of("code", "skill", "skills", "plugin", "cli", "desktop");
    private static final Set<String> LLM_WORDS = Set.of("llm", "llms", "gpt", "rag", "openai", "claude", "anthropic",
            "gemini", "mistral", "qwen", "deepseek", "llama", "ollama", "langchain", "llamaindex", "huggingface",
            "diffusion", "generative", "genai", "prompt", "embedding", "embeddings", "vector", "whisper",
            "transformer", "transformers", "multimodal");
    private static final Set<String> ML_WORDS = Set.of("ml", "machine", "learning", "deep", "neural", "nlp", "vision",
            "tensorflow", "pytorch", "keras", "sklearn", "scikit", "onnx", "yolo", "speech", "inference");

    /**
     * Topics that label the role of a repository or an event instead of its subject. They are left out of every
     * share: config/github-config alone grew from 0% of all mentions in 2020Q1 to 47% in 2021-2022 and would
     * otherwise drive the denominator; Hacktoberfest adds an October spike every year.
     */
    public static final List<Pattern> NON_THEMATIC = Stream.of(
                    "^(config|github-config)$",          // GitHub configuration repositories
                    "hacktober",                         // Hacktoberfest campaigns, yearly October spike
                    "^challengeone|^alurachallenge|^challenge$",  // course and bootcamp assignments
                    "^(good-first-issue|help-wanted|beginner|beginner-friendly|beginner-project)$",
                    "portfolio",                         // personal portfolio sites
                    "readme|profile",                    // GitHub profile repositories
                    "^templates?$|-template$",           // project templates
                    "^learning(-by-doing)?$")            // study repositories
            .map(Pattern::compile)
            .toList();

    private static final Map<String, Group> GROUPS = Stream.of(
            topics(Group.CLASSIC_ML, "machine-learning", "ml", "machine-learning-algorithms", "deep-learning",
                    "neural-network", "neural-networks", "deep-neural-networks", "convolutional-neural-networks",
                    "pytorch", "tensorflow", "keras", "scikit-learn", "sklearn", "onnx", "computer-vision", "nlp",
                    "natural-language-processing", "nlp-machine-learning", "reinforcement-learning",
                    "speech-recognition", "speech-to-text", "text-to-speech"),
            topics(Group.GENERAL_AI, "ai", "artificial-intelligence", "chatbot", "ai-tools", "ai-assistant"),
            topics(Group.LLM, "llm", "llms", "large-language-models", "openai", "openai-api", "chatgpt",
                    "chatgpt-api", "gpt", "gpt-4", "langchain", "rag", "generative-ai", "genai", "prompt-engineering",
                    "huggingface", "transformers", "transformer", "ollama", "llama", "gemini", "gemini-api", "claude",
                    "anthropic", "deepseek", "embeddings", "vector-database", "vector-search", "llm-inference",
                    "fine-tuning", "stable-diffusion", "whisper", "vision-language-model"),
            topics(Group.AGENTS, "ai-agents", "ai-agent", "agent", "agents", "agentic-ai", "multi-agent",
                    "multi-agent-systems", "autonomous-agents", "langgraph", "mcp", "mcp-server",
                    "model-context-protocol", "claude-code", "claude-code-plugin", "claude-skills", "agent-skills",
                    "codex", "cursor", "copilot", "github-copilot", "vibe-coding", "coding-agent", "ai-coding"))
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private AiTopics() {
    }

    public static Optional<Group> group(String topic) {
        Group fixed = GROUPS.get(topic);
        if (fixed != null) {
            return Optional.of(fixed);
        }
        if (REJECTED_CANDIDATES.containsKey(topic) || isNonThematic(topic) || !CANDIDATE.matcher(topic).find()) {
            return Optional.empty();
        }
        Set<String> words = Set.of(topic.split("-"));
        if (anyOf(words, AGENT_WORDS) || anyOf(words, MODEL_WORDS) && anyOf(words, TOOL_WORDS)) {
            return Optional.of(Group.AGENTS);
        }
        if (anyOf(words, LLM_WORDS)) {
            return Optional.of(Group.LLM);
        }
        if (anyOf(words, ML_WORDS)) {
            return Optional.of(Group.CLASSIC_ML);
        }
        return Optional.of(Group.GENERAL_AI);
    }

    /** Topics placed in a group by hand ({@link #GROUPS}); the rest of the AI topics come from the name rules. */
    public static Set<String> fixedTopics(Group group) {
        return GROUPS.entrySet().stream()
                .filter(e -> e.getValue() == group)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean anyOf(Set<String> words, Set<String> set) {
        return words.stream().anyMatch(set::contains);
    }

    public static boolean isNonThematic(String topic) {
        return NON_THEMATIC.stream().anyMatch(p -> p.matcher(topic).find());
    }

    /** Decision on a topic for the review table: the AI group, "rejected: reason", or "not AI". */
    public static String decision(String topic) {
        if (REJECTED_CANDIDATES.containsKey(topic)) {
            return "rejected: " + REJECTED_CANDIDATES.get(topic);
        }
        return group(topic).map(Group::column).orElse("not AI");
    }

    private static Map<String, Group> topics(Group group, String... names) {
        return Stream.of(names).collect(Collectors.toMap(n -> n, n -> group));
    }
}
