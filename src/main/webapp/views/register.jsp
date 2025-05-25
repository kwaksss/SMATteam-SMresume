<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Sign Up</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body>
<div class="container d-flex justify-content-center align-items-center vh-100">
    <div class="card p-4 shadow-lg" style="width: 400px;">
        <h3 class="text-center mb-4">Sign Up</h3>
        <form action="/signup" method="POST">

            <div class="mb-3">
                <label for="userid" class="form-label">ID</label>
                <input type="text" class="form-control" id="userid" name="userid" required>
            </div>
            <div class="mb-3">
                <label for="username" class="form-label">Name</label>
                <input type="text" class="form-control" id="username" name="username" required>
            </div>
            <div class="mb-3">
                <label for="userpassword" class="form-label">Password</label>
                <input type="password" class="form-control" id="userpassword" name="userpassword" required>
            </div>

            <button type="submit" class="btn btn-primary w-100">Sign Up</button>
        </form>
        <p class="text-center mt-3">
            Already have an account? <a href="/login">Login here</a>
        </p>
    </div>
</div>
</body>
</html>