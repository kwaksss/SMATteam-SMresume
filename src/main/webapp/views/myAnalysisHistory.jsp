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
        }
        .list-group-item:hover {
            background-color: #f0f5ff;
            border-color: #b3d9ff;
            cursor: pointer;
        }
        .list-group-item strong {
            color: #0d6efd;
            font-weight: 600;
        }
        .btn-primary {
            background-color: #0d6efd;
            border-color: #0d6efd;
        }
        .btn-primary:hover {
            background-color: #0b5ed7;
            border-color: #0a58ca;
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

    <c:if test="${empty analysisHistory}">
        <div class="alert alert-info" role="alert">
            아직 분석 기록이 없습니다. 이력서를 분석해 보세요!
            <a href="/resume/input" class="btn btn-sm btn-info ms-3">새 이력서 분석하기</a>
        </div>
    </c:if>
    <c:if test="${not empty analysisHistory}">
        <ul class="list-group">
            <c:forEach var="item" items="${analysisHistory}">
                <li class="list-group-item d-flex justify-content-between align-items-center">
                    <div>
                        <strong>파일명:</strong> ${item.originalFileName}<br>
                        <strong>목표 직무:</strong> ${item.targetJob}<br>
                        <strong>분석 날짜:</strong> ${item.analysisDate}
                    </div>
                        <%-- analysisId를 이용해 상세 보기 페이지로 링크 --%>
                    <a href="/my-analysis/${item.analysisId}" class="btn btn-primary btn-sm">상세 보기</a>
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
</body>
</html>