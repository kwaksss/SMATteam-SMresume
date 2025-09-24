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


    @PostMapping("/result") // 이력서 분석 및 결과 표시
    public String analyzeResumeAndShowResult(
            @RequestParam(value = "resumeFile", required = false) MultipartFile resumeFile,
            @RequestParam(value = "resumeText", required = false) String resumeText,
            @RequestParam("targetJob") String targetJob,
            HttpSession session,
            Model model) {

        // 1. 로그인된 사용자 ID 가져오기
        String loggedInUsername = null;
        UserDto loggedInUser = (UserDto) session.getAttribute("loginid");
        if (loggedInUser != null && loggedInUser.getUsername() != null) {
            loggedInUsername = loggedInUser.getUsername();
        }

        // 로그인되지 않은 사용자는 이력서 분석 결과를 저장할 수 없도록 처리
        if (loggedInUsername == null) {
            model.addAttribute("errorMessage", "로그인해야 이력서 분석 결과를 저장하고 볼 수 있습니다.");
            return "login"; // 로그인 페이지로 리다이렉트
        }

        String contentToAnalyze = "";
        MultipartFile fileToStore = null; // S3에 저장할 원본 파일 (텍스트 입력 시 null)

        // 파일 또는 텍스트 내용 추출
        if (resumeFile != null && !resumeFile.isEmpty()) {
            fileToStore = resumeFile; // 원본 파일 지정
            try (InputStream inputStream = resumeFile.getInputStream()) {
                String fileContentType = resumeFile.getContentType();
                if (fileContentType != null) {
                    if (fileContentType.startsWith("text/plain")) {
                        contentToAnalyze = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    } else if (fileContentType.equals("application/pdf")) {
                        byte[] bytes = inputStream.readAllBytes();
                        PDDocument document = PDDocument.load(bytes);
                        PDFTextStripper stripper = new PDFTextStripper();
                        contentToAnalyze = stripper.getText(document);
                        document.close();
                    } else if (fileContentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                        XWPFDocument document = new XWPFDocument(inputStream);
                        XWPFWordExtractor extractor = new XWPFWordExtractor(document);
                        contentToAnalyze = extractor.getText();
                        extractor.close(); // Extractor 닫기
                        document.close(); // Document 닫기
                    } else {
                        model.addAttribute("errorMessage", "지원하지 않는 파일 형식입니다 (텍스트, PDF, DOCX만 지원).");
                        return "resumeInput";
                    }
                } else {
                    model.addAttribute("errorMessage", "파일 형식을 확인할 수 없습니다.");
                    return "resumeInput";
                }
            } catch (IOException e) {
                log.error("파일 읽기 오류: {}", e.getMessage(), e);
                model.addAttribute("errorMessage", "파일을 읽는 동안 오류가 발생했습니다.");
                return "resumeInput";
            }
        } else if (resumeText != null && !resumeText.isEmpty()) {
            contentToAnalyze = resumeText;
            // 텍스트 직접 입력 시, S3에 저장할 원본 파일은 null
        } else {
            model.addAttribute("errorMessage", "이력서 파일 또는 텍스트를 입력해주세요.");
            return "resumeInput";
        }

        // OpenAI API 호출
        if (!contentToAnalyze.isEmpty()) {
            try {
                Map<String, Map<String, String>> analysisResultMap = callOpenAiApi(contentToAnalyze, targetJob);

                // 2. 분석 결과를 AWS에 저장
                boolean saveSuccess = resumeStorageService.saveAnalysisResult(
                        loggedInUsername,
                        fileToStore, // 업로드된 파일 객체 전달 (텍스트 입력 시 null)
                        analysisResultMap,
                        targetJob
                );

                if (!saveSuccess) {
                    log.error("이력서 분석 결과 AWS 저장 실패: 사용자 ID {}", loggedInUsername);
                    model.addAttribute("warningMessage", "분석 결과 저장에 실패했습니다. 관리자에게 문의하세요.");
                }

                // 3. 분석 결과를 JSP로 전달
                model.addAttribute("analysisResult", analysisResultMap);
                // resumeResult.jsp에서 사용할 수 있도록 원본 파일명과 목표 직무도 전달
                model.addAttribute("originalFileName", fileToStore != null ? fileToStore.getOriginalFilename() : "텍스트 직접 입력");
                model.addAttribute("targetJob", targetJob);

                return "resumeResult"; // 결과 페이지로 이동
            } catch (IOException e) {
                log.error("OpenAI API 호출 또는 응답 처리 오류: {}", e.getMessage(), e);
                model.addAttribute("errorMessage", "이력서 분석 중 오류가 발생했습니다: " + e.getMessage()); // 에러 메시지 상세화
                return "resumeInput";
            }
        } else {
            model.addAttribute("errorMessage", "분석할 이력서 내용이 없습니다.");
            return "resumeInput";
        }
    }

    // 텍스트 분할 함수 (문자 수 기준)
    private List<String> splitText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i < length; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(length, i + chunkSize)));
        }
        return chunks;
    }


    /*private Map<String, Map<String, String>> callOpenAiApi(String resumeContent, String targetJob) throws IOException {
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        String model = "gpt-3.5-turbo"; // 사용할 모델

        if (targetJob == null || targetJob.trim().isEmpty()) {
            targetJob = "일반"; // 목표 직무가 입력되지 않았을 경우 기본값 설정
        }

        // 프롬프트 구성 시 String.format 인자 순서에 주의
        // %1$s: targetJob.split(" ")[0] (예: 소프트웨어)
        // %2$s: targetJob (예: 소프트웨어 엔지니어 (백엔드))
        // %3$s: resumeContent (이력서 내용)
        String prompt = String.format(
                """
                당신은 %1$s 분야의 채용 전문가입니다. 다음 이력서를 %2$s 직무에 지원하는 지원자의 관점에서 상세히 분석하고, 채용 담당자의 입장에서 매력적인 이력서가 되기 위한 구체적인 개선 제안을 제공해주세요.

                **주요 평가 항목:**
                - **경력 사항:** %2$s 직무와 관련된 프로젝트 경험 및 성과를 명확하고 구체적인 수치와 함께 제시했는지 평가하고, 성과를 더 효과적으로 어필할 수 있는 방법을 제안해주세요. 특히 기술 스택 사용 경험을 강조해야 합니다.
                - **기술 스택:** 제시된 기술 스택이 %2$s 직무에서 요구하는 핵심 기술과 부합하는지 평가하고, 부족하거나 보완해야 할 기술, 추가하면 좋을 기술 등을 제안해주세요.
                - **학력 및 기타:** 학력 사항이 지원 직무와 관련성이 있는지, 기타 활동 (수상 경력, 프로젝트 경험 등)이 강점을 부각하는 데 도움이 되는지 평가하고 개선 방안을 제시해주세요.
                - **문법 및 가독성:** 문법 오류, 오탈자, 비문, 어색하거나 불필요한 표현은 없는지 꼼꼼히 확인하고, 간결하고 명확하며 읽기 쉬운 문장으로 작성되었는지 평가해주세요.
                - **전반적인 경쟁력:** 이 이력서가 %2$s 직무에 대한 전반적인 경쟁력이 어느 정도인지 평가하고, 서류 통과율을 높이기 위한 가장 중요한 개선 사항들을 3가지 이상 구체적으로 제시해주세요. **이 항목의 '개선 제안'은 구체적인 내용을 포함하는 한 문단으로 된 단일 문자열로 작성해주세요.**

                **답변 형식:**
                분석 결과 및 개선 제안을 다음 JSON 형식으로 답변해주세요. JSON 형식 외에 다른 내용은 일체 포함하지 마세요.
                {
                  "경력 사항": {
                    "분석": "[분석 내용]",
                    "개선 제안": "[개선 제안]"
                  },
                  "기술 스택": {
                    "분석": "[분석 내용]",
                    "개선 제안": "[개선 제안]"
                  },
                  "학력 및 기타": {
                    "분석": "[분석 내용]",
                    "개선 제안": "[개선 제안]"
                  },
                  "문법 및 가독성": {
                    "분석": "[분석 내용]",
                    "개선 제안": "[개선 제안]"
                  },
                  "전반적인 경쟁력": {
                    "분석": "[분석 내용]",
                    "개선 제안": "[한 문단으로 된 단일 문자열 개선 제안]"
                  }
                }

                **분석 대상 이력서:**
                %3$s
                """,
                targetJob.split(" ")[0], targetJob, resumeContent // 인자 순서 맞춤
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = openaiRestTemplate.postForEntity(apiUrl, requestEntity, Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");
                try {
                    // JSON String을 Map<String, Map<String, String>>으로 파싱
                    // TypeReference는 복잡한 제네릭 타입을 정확히 지정하기 위해 사용
                    return objectMapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    log.error("JSON 파싱 오류 (응답 내용: {}): {}", content, e.getMessage(), e);
                    throw new IOException("OpenAI API 응답 JSON 파싱 오류", e);
                }
            }
        } else {
            log.error("OpenAI API 호출 실패: 상태코드={}, 응답바디={}", response.getStatusCode(), response.getBody());
            throw new IOException("OpenAI API 호출 실패: " + response.getStatusCode());
        }
        // 정상적인 경우 이곳에 도달하지 않아야 하므로 null 반환 대신 예외를 던지는 것이 더 명확
        throw new IOException("OpenAI API 응답이 유효하지 않습니다.");
    }*/
    private Map<String, Map<String, String>> callOpenAiApi(String resumeContent, String targetJob) throws IOException {
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        String model = "gpt-3.5-turbo";

        if (targetJob == null || targetJob.trim().isEmpty()) {
            targetJob = "일반";
        }

        // 1) 긴 이력서 텍스트를 쪼개기
        List<String> chunks = splitText(resumeContent, 1000); // 2000자 단위
        List<String> partialSummaries = new ArrayList<>();

        // 2) 각 chunk 요약
        for (String chunk : chunks) {
            String chunkPrompt = String.format(
                    "다음은 이력서 일부입니다. %s 직무 관점에서 핵심 요약을 해주세요:\n\n%s",
                    targetJob, chunk
            );

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", chunkPrompt)));

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

        // 3) 부분 요약을 합쳐서 최종 분석 프롬프트 구성
        String finalPrompt = String.format(
                """
                당신은 %1$s 분야의 채용 전문가입니다. 다음은 이력서 부분 요약들입니다.
                이를 종합해서 %2$s 직무 지원자의 관점에서 분석해 주세요.
                STAR 관점에서도 부족한 부분을 말씀해주시면 좋겠습니다.
    
                이력서 부분 요약:
                %3$s
                """,
                targetJob.split(" ")[0], targetJob, String.join("\n", partialSummaries)
        );

        Map<String, Object> finalRequest = new HashMap<>();
        finalRequest.put("model", model);
        finalRequest.put("messages", List.of(Map.of("role", "user", "content", finalPrompt)));
        finalRequest.put("temperature", 0.7);

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
                return objectMapper.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
            }
        }
        throw new IOException("OpenAI API 최종 분석 실패");
    }
    //긴 이력서 → 잘라서 요약
    //
    //요약 결과 → 다시 합쳐서 최종 분석
    //
    //gpt-3.5-turbo에서도 안정적으로 동작
    //2025.09.03

}