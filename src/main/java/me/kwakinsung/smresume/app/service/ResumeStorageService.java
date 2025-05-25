// src/main/java/me/kwakinsung/smresume/app/service/ResumeStorageService.java
package me.kwakinsung.smresume.app.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest; // 추가
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest; // 추가
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;  // 추가

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime; // LocalDateTime 추가
import java.time.ZoneOffset; // ZoneOffset 추가
import java.time.format.DateTimeFormatter; // DateTimeFormatter 추가
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeStorageService {

    private final S3Client s3Client;
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.bucketName}")
    private String s3BucketName;

    @Value("${aws.dynamodb.tableName}")
    private String dynamoDbTableName;

    // saveAnalysisResult 메서드는 변경 없음 (이전 답변에서 제공된 최신 버전 사용)
    // ... (saveAnalysisResult 메서드 내용) ...
    public boolean saveAnalysisResult(String userId, MultipartFile resumeFile,
                                      Map<String, Map<String, String>> analysisResultMap, String targetJob) {
        if (userId == null || userId.isEmpty()) {
            log.warn("사용자 ID가 없어 이력서 분석 결과를 저장할 수 없습니다.");
            return false;
        }

        String analysisId = UUID.randomUUID().toString(); // 고유한 분석 ID 생성
        // 현재 시간을 UTC Epoch 초로 저장 (DynamoDB 정렬 키용)
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String resumeFileKey = null;
        String analysisResultS3Key = null;

        if (resumeFile != null && !resumeFile.isEmpty()) {
            resumeFileKey = "resumes/" + userId + "/" + analysisId + "/" + resumeFile.getOriginalFilename();
            try {
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucket(s3BucketName)
                        .key(resumeFileKey)
                        .build();
                s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(resumeFile.getInputStream(), resumeFile.getSize()));
                log.info("S3에 이력서 파일 저장 완료: {}", resumeFileKey);
            } catch (IOException e) {
                log.error("S3에 이력서 파일 저장 실패: {}", e.getMessage(), e);
                return false;
            }
        } else {
            resumeFileKey = "N/A";
            log.info("원본 이력서 파일이 없어 S3에 저장되지 않았습니다. 사용자: {}", userId);
        }

        String analysisResultJson;
        try {
            analysisResultJson = objectMapper.writeValueAsString(analysisResultMap);
            analysisResultS3Key = "analysis-results/" + userId + "/" + analysisId + "/result.json";
            PutObjectRequest putResultRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(analysisResultS3Key)
                    .contentType("application/json")
                    .build();
            s3Client.putObject(putResultRequest, RequestBody.fromString(analysisResultJson));
            log.info("S3에 분석 결과 JSON 저장 완료: {}", analysisResultS3Key);
        } catch (JsonProcessingException e) {
            log.error("분석 결과 JSON 변환 실패 또는 S3 저장 실패: {}", e.getMessage(), e);
            return false;
        }

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("analysisId", AttributeValue.builder().s(analysisId).build());
        item.put("analysisTimestamp", AttributeValue.builder().n(timestamp).build()); // Long 타입으로 저장
        item.put("originalFileName", AttributeValue.builder().s(resumeFile != null ? resumeFile.getOriginalFilename() : "텍스트 직접 입력").build());
        item.put("s3ResumePath", AttributeValue.builder().s(resumeFileKey).build());
        item.put("s3AnalysisResultPath", AttributeValue.builder().s(analysisResultS3Key).build());
        item.put("targetJob", AttributeValue.builder().s(targetJob).build());


        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(dynamoDbTableName)
                .item(item)
                .build();

        try {
            dynamoDbClient.putItem(putItemRequest);
            log.info("DynamoDB에 분석 결과 메타데이터 저장 완료: userId={}, analysisId={}", userId, analysisId);
            return true;
        } catch (Exception e) {
            log.error("DynamoDB에 분석 결과 메타데이터 저장 실패: {}", e.getMessage(), e);
            return false;
        }
    }


    /**
     * 특정 사용자의 모든 분석 결과를 조회합니다.
     * DynamoDB Query를 사용하며, 최신순으로 정렬됩니다.
     * @param userId 사용자 ID
     * @return 분석 결과 리스트 (Map 형태로, 필요한 정보만 추출)
     */
    public List<Map<String, String>> getUserAnalysisResults(String userId) {
        if (userId == null || userId.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":userId", AttributeValue.builder().s(userId).build());

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(dynamoDbTableName)
                .keyConditionExpression("userId = :userId")
                .expressionAttributeValues(expressionValues)
                .scanIndexForward(false) // analysisTimestamp를 정렬 키로 사용하여 최신순 정렬 (내림차순)
                .build();

        List<Map<String, String>> results = new ArrayList<>();
        try {
            QueryResponse response = dynamoDbClient.query(queryRequest);
            for (Map<String, AttributeValue> item : response.items()) {
                Map<String, String> simplifiedItem = new HashMap<>();
                simplifiedItem.put("userId", item.get("userId") != null ? item.get("userId").s() : "");
                simplifiedItem.put("analysisId", item.get("analysisId") != null ? item.get("analysisId").s() : "");
                simplifiedItem.put("originalFileName", item.get("originalFileName") != null ? item.get("originalFileName").s() : "");
                simplifiedItem.put("targetJob", item.get("targetJob") != null ? item.get("targetJob").s() : "");
                simplifiedItem.put("s3AnalysisResultPath", item.get("s3AnalysisResultPath") != null ? item.get("s3AnalysisResultPath").s() : "");

                // 타임스탬프를 읽기 쉬운 형식으로 변환
                if (item.get("analysisTimestamp") != null && item.get("analysisTimestamp").n() != null) {
                    long epochSecond = Long.parseLong(item.get("analysisTimestamp").n());
                    LocalDateTime dateTime = LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC);
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    simplifiedItem.put("analysisDate", dateTime.format(formatter));
                } else {
                    simplifiedItem.put("analysisDate", "날짜 정보 없음");
                }
                results.add(simplifiedItem);
            }
            log.info("사용자 {}의 분석 결과 {}개 조회 완료", userId, results.size());
            return results;
        } catch (Exception e) {
            log.error("DynamoDB에서 사용자 분석 결과 조회 실패: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * S3에 저장된 특정 분석 결과 JSON 파일을 가져옵니다.
     * @param s3AnalysisResultPath S3 객체 키 (DynamoDB에서 조회한 경로)
     * @return 분석 결과 Map
     */
    public Map<String, Map<String, String>> getAnalysisResultFromS3(String s3AnalysisResultPath) {
        if (s3AnalysisResultPath == null || s3AnalysisResultPath.isEmpty() || "N/A".equals(s3AnalysisResultPath)) {
            log.warn("S3 분석 결과 경로가 유효하지 않습니다: {}", s3AnalysisResultPath);
            return new HashMap<>();
        }
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3AnalysisResultPath)
                    .build();
            String jsonString = s3Client.getObjectAsBytes(getObjectRequest).asUtf8String();
            // ObjectMapper를 사용하여 JSON String을 Map<String, Map<String, String>>으로 변환
            return objectMapper.readValue(jsonString, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
        } catch (Exception e) {
            log.error("S3에서 분석 결과 JSON 파일 로드 실패 (경로: {}): {}", s3AnalysisResultPath, e.getMessage(), e);
            return new HashMap<>();
        }
    }
}