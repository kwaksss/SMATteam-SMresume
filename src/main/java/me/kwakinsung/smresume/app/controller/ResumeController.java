package me.kwakinsung.smresume.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import me.kwakinsung.smresume.app.dto.UserDto;
import me.kwakinsung.smresume.app.service.ResumeStorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/resume")
@Slf4j // Slf4j 어노테이션 추가
public class ResumeController {

    private final RestTemplate openaiRestTemplate;
    private final ObjectMapper objectMapper;
    private final ResumeStorageService resumeStorageService;

    @Value("${openai.api-key}")
    private String openaiApiKey;

    @Autowired // 생성자 주입
    public ResumeController(RestTemplate openaiRestTemplate, ObjectMapper objectMapper, ResumeStorageService resumeStorageService) {
        this.openaiRestTemplate = openaiRestTemplate;
        this.objectMapper = objectMapper;
        this.resumeStorageService = resumeStorageService;
    }

    @GetMapping("/input")
    public String showResumeInputForm() {
        return "resumeInput"; // 이력서 입력 폼 페이지
    }

    @GetMapping("/result")
    public String showResultPage() {
        // 그냥 JSP만 보여주고, model 값은 없음
        return "resumeResult";
    }


    @PostMapping("/result")
    public String analyzeResumeAndShowResult(
            @RequestParam(value = "resumeFile", required = false) MultipartFile resumeFile,
            @RequestParam(value = "resumeText", required = false) String resumeText,
            @RequestParam("targetJob") String targetJob,
            HttpSession session,
            Model model) {

        String loggedInUsername = null;
        UserDto loggedInUser = (UserDto) session.getAttribute("loginid");
        if (loggedInUser != null && loggedInUser.getUsername() != null) {
            loggedInUsername = loggedInUser.getUsername();
        }

        if (loggedInUsername == null) {
            model.addAttribute("errorMessage", "로그인해야 이력서 분석 결과를 저장하고 볼 수 있습니다.");
            return "login";
        }

        String contentToAnalyze = "";
        MultipartFile fileToStore = null;

        // 파일 또는 텍스트 내용 추출
        try {
            if (resumeFile != null && !resumeFile.isEmpty()) {
                fileToStore = resumeFile;
                try (InputStream inputStream = resumeFile.getInputStream()) {
                    String type = resumeFile.getContentType();
                    if (type != null) {
                        if (type.startsWith("text/plain")) {
                            contentToAnalyze = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                        } else if (type.equals("application/pdf")) {
                            PDDocument doc = PDDocument.load(inputStream.readAllBytes());
                            PDFTextStripper stripper = new PDFTextStripper();
                            contentToAnalyze = stripper.getText(doc);
                            doc.close();
                        } else if (type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                            XWPFDocument doc = new XWPFDocument(inputStream);
                            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
                            contentToAnalyze = extractor.getText();
                            extractor.close();
                            doc.close();
                        } else {
                            model.addAttribute("errorMessage", "지원하지 않는 파일 형식입니다.");
                            return "resumeInput";
                        }
                    }
                }
            } else if (resumeText != null && !resumeText.isEmpty()) {
                contentToAnalyze = resumeText;
            } else {
                model.addAttribute("errorMessage", "이력서 파일 또는 텍스트를 입력해주세요.");
                return "resumeInput";
            }
        } catch (IOException e) {
            log.error("파일 읽기 오류: {}", e.getMessage(), e);
            model.addAttribute("errorMessage", "파일을 읽는 동안 오류가 발생했습니다.");
            return "resumeInput";
        }

        // OpenAI API 호출
        if (!contentToAnalyze.isEmpty()) {
            try {
                Map<String, Map<String, String>> analysisResultMap = analyzeResumeWithSTAR(contentToAnalyze);

                boolean saveSuccess = resumeStorageService.saveAnalysisResult(
                        loggedInUsername,
                        fileToStore,
                        analysisResultMap,
                        targetJob
                );

                if (!saveSuccess) {
                    model.addAttribute("warningMessage", "분석 결과 저장에 실패했습니다.");
                }

                model.addAttribute("analysisResult", analysisResultMap);
                model.addAttribute("originalFileName", fileToStore != null ? fileToStore.getOriginalFilename() : "텍스트 직접 입력");
                model.addAttribute("targetJob", targetJob);

                return "resumeResult";
            } catch (IOException e) {
                log.error("OpenAI API 호출 오류: {}", e.getMessage(), e);
                model.addAttribute("errorMessage", "이력서 분석 중 오류가 발생했습니다: " + e.getMessage());
                return "resumeInput";
            }
        } else {
            model.addAttribute("errorMessage", "분석할 이력서 내용이 없습니다.");
            return "resumeInput";
        }
    }

    // STAR 분석 + 프로젝트 제안 + JSON 구조 맞춤
    private Map<String, Map<String, String>> analyzeResumeWithSTAR(String resumeContent) throws IOException {
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        String model = "gpt-3.5-turbo";

        List<String> chunks = splitText(resumeContent, 1000);
        List<String> partialSummaries = new ArrayList<>();

        for (String chunk : chunks) {
            String chunkPrompt = String.format("""
                너는 신입 개발자 이력서 평가 전문가야.
                다음 이력서 내용을 STAR 기법 중심으로 요약해줘.
                내용:
                %s
                """, chunk);

            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", chunkPrompt)),
                    "temperature", 0.7
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = openaiRestTemplate.postForEntity(apiUrl, requestEntity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    partialSummaries.add(content);
                }
            }
        }

        // 최종 STAR 분석 + 프로젝트 확장 제안
        String finalPrompt = String.format("""
            너는 신입 개발자 이력서 평가 전문가야.
            아래 요약들을 기반으로 STAR 분석 + 각 항목별 개선점 + 실무 확장 프로젝트 아이디어 제시.
            JSON 형식으로 반환:
            {
              "Situation": {"평가": "", "개선점": ""},
              "Task": {"평가": "", "개선점": ""},
              "Action": {"평가": "", "개선점": ""},
              "Result": {"평가": "", "개선점": ""},
              "총평": "",
              "확장 제안": {"STAR 사례 예시": "", "추가 프로젝트 제안": ""}
            }
            이력서 요약:
            %s
            """, String.join("\n", partialSummaries));

        Map<String, Object> finalRequest = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", finalPrompt)),
                "temperature", 0.7
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.set("Content-Type", "application/json");

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(finalRequest, headers);
        ResponseEntity<Map> finalResponse = openaiRestTemplate.postForEntity(apiUrl, requestEntity, Map.class);

        if (finalResponse.getStatusCode().is2xxSuccessful() && finalResponse.getBody() != null) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) finalResponse.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");

                // JSON -> Map<String, Map<String, String>> 변환
                return objectMapper.readValue(content,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>(){});
            }
        }

        throw new IOException("OpenAI API 최종 분석 실패");
    }

    // 문자열 쪼개기
    private List<String> splitText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i < length; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(length, i + chunkSize)));
        }
        return chunks;
    }

    //긴 이력서 → 잘라서 요약
    //
    //요약 결과 → 다시 합쳐서 최종 분석
    //
    //gpt-3.5-turbo에서도 안정적으로 동작
    //2025.09.03



}