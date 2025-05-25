// src/main/java/me/kwakinsung/smresume/app/controller/MainController.java
package me.kwakinsung.smresume.app.controller;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kwakinsung.smresume.app.dto.UserDto;
import me.kwakinsung.smresume.app.service.UserService;
import me.kwakinsung.smresume.app.service.ResumeStorageService; // ResumeStorageService 임포트
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map; // Map을 사용하기 위해 임포트

@Controller
@Slf4j
@RequiredArgsConstructor // final 필드들을 위한 생성자를 자동으로 생성하여 의존성 주입 (lombok)
public class MainController {

    private final UserService userService;
    private final ResumeStorageService resumeStorageService; // ResumeStorageService 주입

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
        // 비밀번호 해싱 등 실제 보안 강화 로직이 필요합니다.
        // 현재는 간단한 예시입니다.

        userService.add(userDto);
        log.info("회원가입 성공: {}", userDto.getUserid());
        log.info("회원가입 성공: {}", userDto.getUserpassword()); // 실제 서비스에서는 비밀번호 로깅 금지!

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
        model.addAttribute("analysisHistory", history); // JSP로 기록 리스트 전달
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
        // DynamoDB에 userId와 analysisId를 함께 조회하는 효율적인 방법이 있지만,
        // 현재는 getUserAnalysisResults로 가져온 기록에서 필터링하는 방식 사용
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
            return "errorPage"; // 적절한 에러 페이지 (예: error.jsp 또는 에러 메시지를 포함한 myAnalysisHistory.jsp)
        }

        // S3에서 상세 분석 결과 JSON 로드
        Map<String, Map<String, String>> detailedResult = resumeStorageService.getAnalysisResultFromS3(s3Path);
        model.addAttribute("analysisResult", detailedResult); // 상세 분석 결과 데이터
        model.addAttribute("originalFileName", originalFileName); // JSP에 표시할 파일명
        model.addAttribute("targetJob", targetJob); // JSP에 표시할 목표 직무

        return "resumeResult"; // 기존 resumeResult.jsp 템플릿 재사용 (상세 내용 표시)
    }
}