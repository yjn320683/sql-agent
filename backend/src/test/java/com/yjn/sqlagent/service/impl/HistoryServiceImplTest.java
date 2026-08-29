package com.yjn.sqlagent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.yjn.sqlagent.config.AgentProperties;
import com.yjn.sqlagent.model.vo.MessageVO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void loadMessagesSkipsSkillMetaMessages() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path historyDir = tempDir.resolve("configured-history");
        properties.setHistoryDir(historyDir.toString());
        String sessionId = "11111111-1111-4111-8111-111111111111";
        Path jsonl = historyDir.resolve(sessionId + ".jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.write(
                jsonl,
                Arrays.asList(
                        "{\"type\":\"user\",\"message\":{\"content\":\"/sql优化 select * from orders\"}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_skill\",\"name\":\"Skill\",\"input\":{\"skill\":\"sql-agent-rule\"}}]}}",
                        "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_skill\",\"content\":\"Launching skill: sql-agent-rule\"}]}}",
                        "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Base directory for this skill: /tmp/skill\\n\\n# SQL Agent Skill\"}]}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"我会继续帮你分析。\"}]}}"
                ),
                StandardCharsets.UTF_8
        );

        List<MessageVO> messages = new HistoryServiceImpl(properties).loadMessages(sessionId);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("/sql优化 select * from orders", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("我会继续帮你分析。", messages.get(1).getContent());
        assertFalse(messages.stream().anyMatch(message ->
                message.getContent() != null && message.getContent().contains("Base directory for this skill")
        ));
    }

    @Test
    void loadMessagesRestoresOriginalRequirementFromAgentPrompt() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path historyDir = tempDir.resolve("task-history");
        properties.setHistoryDir(historyDir.toString());
        String sessionId = "33333333-3333-4333-8333-333333333333";
        Path jsonl = historyDir.resolve(sessionId + ".jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.write(
                jsonl,
                Arrays.asList(
                        "{\"type\":\"user\",\"message\":{\"content\":\"当前 command：sql_optimize\\n当前 taskId：1\\n当前 executionId：未指定\\n必须先调用 sql_task_get 查询任务和 SQL，不得要求用户重新粘贴 SQL。\\n\\n用户需求：\\n优化一下\"}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"正在分析。\"}]}}"
                ),
                StandardCharsets.UTF_8
        );

        List<MessageVO> messages = new HistoryServiceImpl(properties).loadMessages(sessionId);

        assertEquals(2, messages.size());
        assertEquals("优化一下", messages.get(0).getContent());
        assertEquals("正在分析。", messages.get(1).getContent());
    }

    @Test
    void loadMessagesRestoresAskUserQuestionAsQuestionStep() throws Exception {
        AgentProperties properties = new AgentProperties();
        Path historyDir = tempDir.resolve("question-history");
        properties.setHistoryDir(historyDir.toString());
        String sessionId = "22222222-2222-4222-8222-222222222222";
        Path jsonl = historyDir.resolve(sessionId + ".jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.write(
                jsonl,
                Arrays.asList(
                        "{\"type\":\"user\",\"message\":{\"content\":\"询问我获取更多信息\"}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_question\",\"name\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"header\":\"分析场景\",\"multiSelect\":false,\"options\":[{\"label\":\"订单/转化分析\",\"description\":\"分析订单转化\"}],\"question\":\"你想要分析什么业务场景？\"}]}}]}}",
                        "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_question\",\"content\":\"用户已通过前端回答澄清问题，请基于以下选择继续分析：\\n1. 你想要分析什么业务场景？：订单/转化分析\\n原始结构化答案：\\n{\\\"answers\\\":[{\\\"question\\\":\\\"你想要分析什么业务场景？\\\",\\\"header\\\":\\\"分析场景\\\",\\\"selectedLabels\\\":[\\\"订单/转化分析\\\"],\\\"selectedOptions\\\":[{\\\"label\\\":\\\"订单/转化分析\\\",\\\"description\\\":\\\"分析订单转化\\\"}],\\\"customAnswer\\\":\\\"重点关注渠道维度\\\"}]}\",\"is_error\":true}]}}",
                        "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"好的，我继续分析。\"}]}}"
                ),
                StandardCharsets.UTF_8
        );

        List<MessageVO> messages = new HistoryServiceImpl(properties).loadMessages(sessionId);

        assertEquals(2, messages.size());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("tool", messages.get(1).getSteps().get(0).getKind());
        assertEquals("AskUserQuestion", messages.get(1).getSteps().get(0).getName());
        assertEquals("user_question_prompt", messages.get(1).getSteps().get(0).getSemanticType());
        assertEquals("user_question", messages.get(1).getSteps().get(1).getKind());
        assertEquals("toolu_question", messages.get(1).getSteps().get(1).getRequestId());
        assertEquals("answered", messages.get(1).getSteps().get(1).getStatus());
        assertEquals("订单/转化分析", messages.get(1).getSteps().get(1).getAnswers().get(0).path("selectedLabels").get(0).asText());
        assertEquals("重点关注渠道维度", messages.get(1).getSteps().get(1).getAnswers().get(0).path("customAnswer").asText());
        assertEquals("好的，我继续分析。", messages.get(1).getContent());
    }
}
