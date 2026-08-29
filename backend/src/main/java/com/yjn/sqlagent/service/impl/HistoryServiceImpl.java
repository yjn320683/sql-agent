package com.yjn.sqlagent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.config.AgentProperties;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.model.vo.MessageVO;
import com.yjn.sqlagent.model.vo.StepVO;
import com.yjn.sqlagent.service.HistoryService;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HistoryServiceImpl implements HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryServiceImpl.class);
    private static final String AGENT_PROMPT_PREFIX = "当前 command：";
    private static final String USER_REQUIREMENT_MARKER = "\n用户需求：\n";

    private final AgentProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoryServiceImpl(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<MessageVO> loadMessages(String sessionId) {
        Path file = resolveJsonlPath(sessionId);
        List<MessageVO> messages = new ArrayList<>();
        if (!Files.exists(file)) {
            return messages;
        }

        MessageVO pendingAssistant = null;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException malformedLine) {
                    log.warn("忽略损坏的历史记录 sessionId={}", sessionId);
                    continue;
                }
                String type = node.path("type").asText("");
                if (node.path("isMeta").asBoolean(false)) {
                    continue;
                }
                if ("user".equals(type)) {
                    JsonNode contentNode = node.path("message").path("content");
                    if (containsToolResult(contentNode)) {
                        if (pendingAssistant != null) {
                            appendToolResults(pendingAssistant, contentNode);
                        }
                        continue;
                    }
                    if (hasAssistantContent(pendingAssistant)) {
                        messages.add(pendingAssistant);
                        pendingAssistant = null;
                    }
                    String content = extractUserContent(contentNode);
                    if (!content.isEmpty()) {
                        MessageVO vo = new MessageVO();
                        vo.setRole("user");
                        vo.setContent(content);
                        messages.add(vo);
                    }
                } else if ("assistant".equals(type)) {
                    if (pendingAssistant == null) {
                        pendingAssistant = new MessageVO();
                        pendingAssistant.setRole("assistant");
                        pendingAssistant.setContent("");
                    }
                    appendAssistant(pendingAssistant, node.path("message").path("content"));
                }
            }
            if (hasAssistantContent(pendingAssistant)) {
                messages.add(pendingAssistant);
            }
        } catch (IOException e) {
            log.error("读取历史失败 sessionId={}", sessionId, e);
        }
        return messages;
    }

    private Path resolveJsonlPath(String sessionId) {
        validateSessionId(sessionId);
        Path root = Paths.get(properties.getHistoryDir()).toAbsolutePath().normalize();
        Path candidate = root.resolve(sessionId + ".jsonl").normalize();
        if (!candidate.startsWith(root)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "sessionId 非法");
        }
        return candidate;
    }

    private void validateSessionId(String sessionId) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            if (!uuid.toString().equalsIgnoreCase(sessionId)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "sessionId 必须为标准 UUID");
        }
    }

    private String extractUserContent(JsonNode content) {
        if (content.isTextual()) {
            return restoreUserRequirement(content.asText());
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            return restoreUserRequirement(sb.toString());
        }
        return "";
    }

    private String restoreUserRequirement(String content) {
        if (!content.startsWith(AGENT_PROMPT_PREFIX)
                || !content.contains("\n当前 taskId：")
                || !content.contains("\n当前 executionId：")) {
            return content;
        }
        int markerIndex = content.indexOf(USER_REQUIREMENT_MARKER);
        return markerIndex < 0
                ? content
                : content.substring(markerIndex + USER_REQUIREMENT_MARKER.length());
    }

    private boolean containsToolResult(JsonNode content) {
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode block : content) {
            if ("tool_result".equals(block.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private void appendAssistant(MessageVO target, JsonNode content) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            String blockType = block.path("type").asText();
            if ("text".equals(blockType)) {
                String text = block.path("text").asText();
                target.setContent((target.getContent() == null ? "" : target.getContent()) + text);
                appendTextStep(target, "text", text);
            } else if ("thinking".equals(blockType)) {
                String thinking = block.path("thinking").asText();
                if (!thinking.isEmpty()) {
                    String prev = target.getThinking() == null ? "" : target.getThinking();
                    target.setThinking(prev + thinking);
                    appendTextStep(target, "thinking", thinking);
                }
            } else if ("tool_use".equals(blockType)) {
                String toolName = block.path("name").asText();
                if ("AskUserQuestion".equals(toolName)) {
                    StepVO toolStep = new StepVO();
                    toolStep.setKind("tool");
                    toolStep.setId(block.path("id").asText());
                    toolStep.setName(toolName);
                    toolStep.setInput(block.has("input") ? block.get("input") : null);
                    toolStep.setSemanticType("user_question_prompt");
                    ensureSteps(target).add(toolStep);

                    StepVO step = new StepVO();
                    step.setKind("user_question");
                    step.setId(block.path("id").asText());
                    step.setRequestId(block.path("id").asText());
                    JsonNode input = block.path("input");
                    step.setQuestions(input.path("questions"));
                    step.setRawInput(input);
                    step.setStatus("pending");
                    ensureSteps(target).add(step);
                    continue;
                }
                StepVO step = new StepVO();
                step.setKind("tool");
                step.setId(block.path("id").asText());
                step.setName(toolName);
                step.setInput(block.has("input") ? block.get("input") : null);
                ensureSteps(target).add(step);
            }
        }
    }

    private void appendToolResults(MessageVO target, JsonNode content) {
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) {
                continue;
            }
            String toolUseId = block.path("tool_use_id").asText();
            StepVO step = findToolStep(target, toolUseId);
            if (step != null && "user_question".equals(step.getKind())) {
                String result = stringify(block.path("content"));
                step.setStatus(result.startsWith("用户未确认澄清问题") ? "cancelled" : "answered");
                JsonNode answers = extractStructuredAnswers(result);
                if (answers != null) {
                    step.setAnswers(answers);
                }
                continue;
            }
            if (step == null) {
                step = new StepVO();
                step.setKind("tool");
                step.setId(toolUseId);
                ensureSteps(target).add(step);
            }
            step.setResult(stringify(block.path("content")));
            step.setIsError(block.path("is_error").asBoolean(false));
        }
    }

    private List<StepVO> ensureSteps(MessageVO target) {
        if (target.getSteps() == null) {
            target.setSteps(new ArrayList<StepVO>());
        }
        return target.getSteps();
    }

    private void appendTextStep(MessageVO target, String kind, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StepVO step = new StepVO();
        step.setKind(kind);
        step.setText(text);
        ensureSteps(target).add(step);
    }

    private StepVO findToolStep(MessageVO target, String toolUseId) {
        if (target.getSteps() == null || toolUseId == null || toolUseId.isEmpty()) {
            return null;
        }
        for (int i = target.getSteps().size() - 1; i >= 0; i--) {
            StepVO step = target.getSteps().get(i);
            if (("tool".equals(step.getKind()) || "user_question".equals(step.getKind()))
                    && toolUseId.equals(step.getId())) {
                return step;
            }
        }
        return null;
    }

    private String stringify(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : value) {
                String text = item.has("text") ? item.path("text").asText() : stringify(item);
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return value.toString();
        }
    }

    private JsonNode extractStructuredAnswers(String result) {
        String marker = "原始结构化答案：";
        int index = result.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String json = result.substring(index + marker.length()).trim();
        if (json.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode answers = root.path("answers");
            return answers.isArray() ? answers : null;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean hasAssistantContent(MessageVO message) {
        if (message == null) {
            return false;
        }
        if (message.getContent() != null && !message.getContent().isEmpty()) {
            return true;
        }
        return message.getSteps() != null && !message.getSteps().isEmpty();
    }
}
