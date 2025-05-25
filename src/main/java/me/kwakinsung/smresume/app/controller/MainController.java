// src/main/java/me/kwakinsung/smresume/app/controller/MainController.java
package me.kwakinsung.smresume.app.controller;

import jakarta.servlet.http.HttpSession; // Servlet API에서 직접 HttpSession 사용
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kwakinsung.smresume.app.dto.UserDto;
import me.kwakinsung.smresume.app.service.UserService;
import me.kwakinsung.smresume.app.service.ResumeStorageService;
import org.springframework.beans.factory.annotation.Value; // @Value 어노테이션을 위해 추가
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList; // 필요시 추가
import java.util.HashMap;   // 필요시 추가
import java.util.List;
import java.util.Map;

@Controller
@Slf4j
@RequiredArgsConstructor
public class MainController {

    private final UserService userService;
    private final ResumeStorageService resumeStorageService;

    // application.properties 또는 application.yml 파일에 정의된 값들을 주입받습니다.
    @Value("${aws.s3.bucketName}")
    private String s3BucketName;
    @Value("${aws.region}")
    private String s3Region; // 예: ap-northeast-2

    // 메인 페이지
    @RequestMapping("/")
    public String main(HttpSession session) {
        Object loginIdInSession = session.getAttribute("loginid");
        log.info("[MainController.main] 세션의 'loginid' 값: {}", loginIdInSession);
        if (loginIdInSession instanceof UserDto) {
            UserDto userDto = (UserDto) loginIdInSession;
            log.info("[MainController.main] 세션 UserDto.username: {}", userDto.getUsername());
        }
        return "index";
    }

    // 회원가입 페이지
    @GetMapping("/register")
    public String register(Model model) {
        return "register";
    }

    // 회원가입 폼 제출 처리 (POST 요청)
    @PostMapping("/signup")
    public String processSignUp(@ModelAttribute UserDto userDto,
                                RedirectAttributes redirectAttributes,
                                Model model) throws Exception {
        userService.add(userDto);
        log.info("회원가입 성공: {}", userDto.getUserid());
        // log.info("회원가입 성공: {}", userDto.getUserpassword()); // 실제 서비스에서는 비밀번호 로깅 금지!

        redirectAttributes.addFlashAttribute("successMessage", "회원가입이 성공적으로 완료되었습니다. 로그인해주세요.");
        return "redirect:/login"; // 로그인 페이지로 리다이렉트
    }

    // 로그인 페이지
    @GetMapping("/login")
    public String loginPage() {
        log.info("로그인 페이지 요청");
        return "login"; // login.jsp 반환
    }

    // 로그인 처리
    @PostMapping("/loginimpl")
    public String loginimpl(Model model,
                            @RequestParam("userid") String userid,
                            @RequestParam("userpassword") String password,
                            HttpSession session) throws Exception {

        log.info("로그인 시도 - ID: {}", userid);

        UserDto userDto = userService.get(userid);

        if (userDto != null) {
            // 로그인 성공 조건 (실제로는 비밀번호 해싱 후 비교해야 합니다)
            if (userDto.getUserpassword().equals(password)) {
                session.setAttribute("loginid", userDto); // 로그인 성공한 UserDto 객체를 세션에 저장
                log.info("로그인 성공: 사용자 {}", userDto.getUsername());
                return "redirect:/"; // 메인 페이지로 리다이렉트 (PRG 패턴)
            } else {
                model.addAttribute("loginError", "비밀번호가 일치하지 않습니다.");
                log.warn("로그인 실패: 비밀번호 불일치 (ID: {})", userid);
            }
        } else {
            model.addAttribute("loginError", "존재하지 않는 아이디입니다.");
            log.warn("로그인 실패: 아이디 존재하지 않음 (ID: {})", userid);
        }

        return "login"; // 실패 시 다시 로그인 페이지로 (모델에 에러 메시지 포함)
    }

    // 로그아웃 처리
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate(); // 세션 무효화
        log.info("로그아웃 성공");
        return "redirect:/login"; // 로그인 페이지로 리다이렉트
    }

    // 사용자 분석 기록 목록 페이지
    @GetMapping("/my-analysis-history")
    public String myAnalysisHistory(HttpSession session, Model model) {
        String loggedInUsername = null;
        UserDto loggedInUser = (UserDto) session.getAttribute("loginid");
        if (loggedInUser != null && loggedInUser.getUsername() != null) {
            loggedInUsername = loggedInUser.getUsername();
        }

        // 로그인되지 않은 경우 처리
        if (loggedInUsername == null) {
            model.addAttribute("errorMessage", "로그인해야 분석 기록을 볼 수 있습니다.");
            return "login"; // 로그인 페이지로 리다이렉트하여 로그인 유도
        }

        List<Map<String, String>> history = resumeStorageService.getUserAnalysisResults(loggedInUsername);

        // Pre-signed URL 로직 (선택 사항 - 보안 강화를 위해 권장)
        // 만약 S3 버킷을 public-read로 설정했다면 아래 로직은 필수는 아님.
        // 하지만 보안상 Pre-signed URL을 쓰는 것이 훨씬 좋음.
        List<Map<String, String>> processedHistory = new ArrayList<>();
        for (Map<String, String> item : history) {
            Map<String, String> newItem = new HashMap<>(item);
            String s3ResumePath = item.get("s3ResumePath"); // DynamoDB에서 가져온 경로

            if (s3ResumePath != null && !s3ResumePath.isEmpty() && !s3ResumePath.equals("N/A")) {
                // Pre-signed URL을 ResumeStorageService에서 생성하여 가져오는 경우 (보안 권장)
                // String presignedUrl = resumeStorageService.generatePresignedUrlForDownload(s3ResumePath);
                // newItem.put("s3ResumeFileUrl", presignedUrl);

                // 현재처럼 S3 버킷이 public-read로 설정된 경우 직접 URL 구성 (테스트용, 보안 취약)
                newItem.put("s3ResumeFileUrl", "https://" + s3BucketName + ".s3." + s3Region + ".amazonaws.com/" + s3ResumePath);
            }
            processedHistory.add(newItem);
        }

        model.addAttribute("analysisHistory", processedHistory); // JSP로 기록 리스트 전달 (URL 포함)
        return "myAnalysisHistory"; // myAnalysisHistory.jsp 템플릿 반환
    }

    // 특정 분석 결과 상세 보기
    @GetMapping("/my-analysis/{analysisId}")
    public String viewDetailedAnalysis(@PathVariable String analysisId, HttpSession session, Model model) {
        String loggedInUsername = null;
        UserDto loggedInUser = (UserDto) session.getAttribute("loginid");
        if (loggedInUser != null && loggedInUser.getUsername() != null) {
            loggedInUsername = loggedInUser.getUsername();
        }

        // 로그인되지 않은 경우 처리
        if (loggedInUsername == null) {
            model.addAttribute("errorMessage", "로그인해야 분석 기록을 볼 수 있습니다.");
            return "login";
        }

        // 보안을 위해, 요청된 analysisId가 현재 로그인된 사용자의 것인지 확인하는 로직 (중요!)
        List<Map<String, String>> history = resumeStorageService.getUserAnalysisResults(loggedInUsername);
        String s3Path = null;
        String originalFileName = null;
        String targetJob = null;

        for (Map<String, String> item : history) {
            // 현재 로그인된 사용자의 기록 중에서 해당 analysisId를 가진 항목을 찾습니다.
            if (item.getOrDefault("analysisId", "").equals(analysisId)) {
                s3Path = item.get("s3AnalysisResultPath");
                originalFileName = item.get("originalFileName");
                targetJob = item.get("targetJob");
                break;
            }
        }

        // S3 경로를 찾지 못했거나 유효하지 않은 경우
        if (s3Path == null || s3Path.isEmpty()) {
            log.warn("사용자 {}의 analysisId {}에 대한 S3 경로를 찾을 수 없습니다. (혹은 권한 없음)", loggedInUsername, analysisId);
            model.addAttribute("errorMessage", "해당 분석 결과를 찾을 수 없거나 접근 권한이 없습니다.");
            return "redirect:/my-analysis-history"; // 에러 메시지와 함께 목록 페이지로 리다이렉트
        }

        // S3에서 상세 분석 결과 JSON 로드
        Map<String, Map<String, String>> detailedResult = resumeStorageService.getAnalysisResultFromS3(s3Path);
        model.addAttribute("analysisResult", detailedResult); // 상세 분석 결과 데이터
        model.addAttribute("originalFileName", originalFileName); // JSP에 표시할 파일명
        model.addAttribute("targetJob", targetJob); // JSP에 표시할 목표 직무

        return "resumeResult"; // 기존 resumeResult.jsp 템플릿 재사용 (상세 내용 표시)
    }

    // 삭제 엔드포인트 추가
    @PostMapping("/my-analysis/delete/{analysisId}")
    public String deleteAnalysisResult(@PathVariable("analysisId") String analysisId,
                                       HttpSession session,
                                       RedirectAttributes redirectAttributes) {
        String loggedInUsername = null;
        UserDto loggedInUser = (UserDto) session.getAttribute("loginid");
        if (loggedInUser != null && loggedInUser.getUsername() != null) {
            loggedInUsername = loggedInUser.getUsername();
        }

        if (loggedInUsername == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "로그인해야 이력서 분석 기록을 삭제할 수 있습니다.");
            return "redirect:/login";
        }

        boolean success = resumeStorageService.deleteAnalysisResult(loggedInUsername, analysisId);

        if (success) {
            redirectAttributes.addFlashAttribute("successMessage", "이력서 분석 기록이 성공적으로 삭제되었습니다.");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "이력서 분석 기록 삭제에 실패했습니다. 다시 시도해주세요.");
        }

        return "redirect:/my-analysis-history";
    }
}