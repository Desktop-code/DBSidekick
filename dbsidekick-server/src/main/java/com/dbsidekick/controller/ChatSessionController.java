package com.dbsidekick.controller;

import com.dbsidekick.asset.ChatSessionService;
import com.dbsidekick.asset.SessionDetail;
import com.dbsidekick.asset.SessionSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/session")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @PostMapping("/new")
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        try {
            String datasourceId = body == null ? null : body.get("datasourceId");
            String dbName = body == null ? null : body.get("dbName");
            String sessionId = chatSessionService.createSession(datasourceId, dbName);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("sessionId", sessionId);
            return resp;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "创建会话失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public List<SessionSummary> list(@RequestParam(value = "keyword", required = false) String keyword) {
        try {
            return chatSessionService.listSessions(keyword);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "查询会话失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/frequent-questions")
    public Map<String, Object> frequentQuestions(
            @RequestParam(value = "datasourceId", required = false) String datasourceId,
            @RequestParam(value = "limit", required = false, defaultValue = "5") int limit) {
        try {
            List<String> questions = chatSessionService.getFrequentQuestions(datasourceId, limit);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("questions", questions);
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "查询高频问题失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/{id}")
    public SessionDetail get(@PathVariable("id") String id) {
        try {
            return chatSessionService.getSession(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "获取会话失败" : ex.getMessage(), ex);
        }
    }

    @PutMapping("/{id}/title")
    public Map<String, Object> rename(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            String title = body == null ? null : body.get("title");
            chatSessionService.renameSession(id, title);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("title", title == null ? null : title.trim());
            return resp;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "重命名失败" : ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        try {
            chatSessionService.deleteSession(id);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            return resp;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "删除会话失败" : ex.getMessage(), ex);
        }
    }
}
