<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>내 이력서 분석 기록</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css" rel="stylesheet">
    <style>
        body {
            font-family: 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #dfefff, #f0f5ff);
            color: #333;
            margin: 0;
            padding: 0;
        }
        .container {
            max-width: 960px;
            margin: 40px auto;
            background-color: #fff;
            padding: 30px;
            border-radius: 12px;
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.1);
        }
        h1 {
            color: #2c3e50;
            margin-bottom: 30px;
            text-align: center;
        }
        .list-group-item {
            border: 1px solid #e0e0e0;
            border-radius: 8px;
            margin-bottom: 15px;
            padding: 15px 20px;
            box-shadow: 0 2px 5px rgba(0,0,0,0.03);
            background-color: #fcfdff;
            display: flex; /* flexbox 사용 */
            justify-content: space-between; /* 양쪽 정렬 */
            align-items: center; /* 세로 중앙 정렬 */
            flex-wrap: wrap; /* 작은 화면에서 줄바꿈 */
        }
        .list-group-item:hover {
            background-color: #f0f5ff;
            border-color: #b3d9ff;
            /* cursor: pointer; */ /* 상세 보기 버튼에만 커서 적용, 전체 hover 효과는 제거 */
        }
        .list-group-item strong {
            color: #0d6efd;
            font-weight: 600;
        }
        .list-group-item .actions {
            display: flex;
            gap: 10px; /* 버튼 사이 간격 */
            margin-top: 10px; /* 작은 화면에서 정보와 버튼 사이 간격 */
        }
        .list-group-item .info {
            flex-grow: 1; /* 정보가 공간을 최대한 차지하도록 */
            margin-bottom: 10px; /* 작은 화면에서 버튼과의 간격 */
        }
        @media (min-width: 768px) {
            .list-group-item .info {
                margin-bottom: 0;
            }
            .list-group-item .actions {
                margin-top: 0;
            }
        }
        .btn-primary, .btn-info { /* btn-info 추가 */
            background-color: #0d6efd;
            border-color: #0d6efd;
        }
        .btn-primary:hover, .btn-info:hover {
            background-color: #0b5ed7;
            border-color: #0a58ca;
        }
        .btn-success {
            background-color: #28a745;
            border-color: #28a745;
        }
        .btn-success:hover {
            background-color: #218838;
            border-color: #1e7e34;
        }
        .btn-secondary {
            background-color: #6c757d;
            border-color: #6c757d;
        }
        .btn-secondary:hover {
            background-color: #5a6268;
            border-color: #545b62;
        }
        .btn-danger { /* 삭제 버튼 스타일 추가 */
            background-color: #dc3545;
            border-color: #dc3545;
        }
        .btn-danger:hover {
            background-color: #c82333;
            border-color: #bd2130;
        }
    </style>
</head>
<body>
<div class="container">
    <h1>내 이력서 분석 기록</h1>

    <c:if test="${not empty errorMessage}">
        <div class="alert alert-danger" role="alert">
                ${errorMessage}
        </div>
    </c:if>
    <c:if test="${not empty successMessage}">
        <div class="alert alert-success" role="alert">
                ${successMessage}
        </div>
    </c:if>

    <c:if test="${empty analysisHistory}">
        <div class="alert alert-info" role="alert">
            아직 분석 기록이 없습니다. 이력서를 분석해 보세요!
            <a href="/resume/input" class="btn btn-sm btn-info ms-3">새 이력서 분석하기</a>
        </div>
    </c:if>
    <c:if test="${not empty analysisHistory}">
        <ul class="list-group">
            <c:forEach var="item" items="${analysisHistory}">
                <li class="list-group-item" id="analysis-${item.analysisId}"> <%-- 삭제를 위해 ID 추가 --%>
                    <div class="info">
                        <strong>파일명:</strong> ${item.originalFileName}<br>
                        <strong>목표 직무:</strong> ${item.targetJob}<br>
                        <strong>분석 날짜:</strong> ${item.analysisDate}
                    </div>
                    <div class="actions">
                            <%-- 원본 파일이 S3에 저장되어 있고 경로가 'N/A'가 아니라면 다운로드 링크 제공 --%>
                        <c:if test="${not empty item.s3ResumePath && item.s3ResumePath != 'N/A'}">
                            <%-- MainController에서 s3ResumeFileUrl을 생성하여 전달하는 경우 --%>
                            <a href="${item.s3ResumeFileUrl}"
                               target="_blank" class="btn btn-info btn-sm">
                                <i class="fas fa-download me-1"></i>원본 파일 보기
                            </a>
                            <%-- 또는 MainController에서 직접 S3 URL을 구성했다면 (보안 취약) --%>
                            <%-- <a href="https://smresumebucket.s3.ap-northeast-2.amazonaws.com/${item.s3ResumePath}"
                               target="_blank" class="btn btn-info btn-sm">
                                <i class="fas fa-download me-1"></i>원본 파일 보기
                            </a> --%>
                        </c:if>
                            <%-- analysisId를 이용해 상세 보기 페이지로 링크 --%>
                        <a href="/my-analysis/${item.analysisId}" class="btn btn-primary btn-sm">
                            <i class="fas fa-search me-1"></i>분석 결과 보기
                        </a>
                            <%-- 삭제 버튼 추가 --%>
                            <button type="button" class="btn btn-danger btn-sm"
                                    onclick="confirmDelete('${item.analysisId}', '${item.originalFileName}')">
                                <i class="fas fa-trash-alt me-1"></i>삭제하기
                            </button>
                    </div>
                </li>
            </c:forEach>
        </ul>
    </c:if>

    <div class="mt-4 text-center">
        <a href="/resume/input" class="btn btn-success me-2"><i class="fas fa-magic me-1"></i>새 이력서 분석하기</a>
        <a href="/" class="btn btn-secondary"><i class="fas fa-home me-1"></i>홈으로 돌아가기</a>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
<script>
    function confirmDelete(analysisId, fileName) {
        // !!! 이 두 개의 console.log를 추가하고 콘솔 창을 확인하세요 !!!
        console.log("1. confirmDelete 함수에 전달된 analysisId:", analysisId);
        console.log("1. confirmDelete 함수에 전달된 fileName:", fileName);

        if (confirm('\'' + fileName + '\' 분석 기록을 정말 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.')) {
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = '/my-analysis/delete/' + analysisId;

            // !!! 이 console.log를 추가하여 최종 생성된 URL을 확인하세요 !!!
            console.log("2. 최종 form.action URL:", form.action);

            document.body.appendChild(form);
            form.submit();
        }
    }
</script>
</body>
</html>