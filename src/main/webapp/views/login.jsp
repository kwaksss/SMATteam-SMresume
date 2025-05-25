<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>로그인</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body>
<div class="container d-flex justify-content-center align-items-center vh-100">
    <div class="card p-4 shadow-lg" style="width: 400px;">
        <h3 class="text-center mb-4">로그인</h3>

        <form action="/loginimpl" method="POST" class="mb-3">
            <div class="mb-3">
                <label for="userid" class="form-label">아이디</label>
                <input type="text" class="form-control" id="userid" name="userid" required>
            </div>
            <div class="mb-3">
                <label for="userpassword" class="form-label">비밀번호</label>
                <input type="password" class="form-control" id="userpassword" name="userpassword" required>
            </div>
            <button type="submit" class="btn btn-primary w-100">로그인</button>
            <c:if test="${param.error != null}">
                <div class="alert alert-danger mt-3">
                    아이디 또는 비밀번호가 올바르지 않습니다.
                </div>
            </c:if>
            <c:if test="${param.logout != null}">
                <div class="alert alert-success mt-3">
                    로그아웃되었습니다.
                </div>
            </c:if>
        </form>
        <a href="/oauth2/authorization/kakao" class="btn btn-warning w-100">Kakao 로그인</a>

        <p class="text-center mt-3">
            계정이 없나요? <a href="/register">회원가입</a>
        </p>
    </div>
</div>
</body>
</html>