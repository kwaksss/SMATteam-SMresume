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
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest; // S3 삭제를 위해 추가
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator; // 이 import는 현재 코드에서 사용되지 않으므로 제거해도 됩니다.
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors; // 이 import는 현재 코드에서 사용되지 않으므로 제거해도 됩니다.

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

    /**
     * 사용자의 이력서 파일과 분석 결과를 S3에 저장하고, 관련 메타데이터를 DynamoDB에 저장합니다.
     * @param userId 사용자 ID
     * @param resumeFile 업로드된 이력서 파일 (null일 수 있음)
     * @param analysisResultMap 분석 결과 Map
     * @param targetJob 목표 직무
     * @return 저장 성공 여부
     */
    public boolean saveAnalysisResult(String userId, MultipartFile resumeFile,
                                      Map<String, Map<String, String>> analysisResultMap, String targetJob) {
        if (userId == null || userId.isEmpty()) {
            log.warn("사용자 ID가 없어 이력서 분석 결과를 저장할 수 없습니다.");
            return false;
        }

        String analysisId = UUID.randomUUID().toString(); // 고유한 분석 ID 생성
        // 현재 시간을 UTC Epoch 초로 저장 (DynamoDB 정렬 키용)
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String resumeFileKey = null; // S3에 저장될 원본 이력서 파일의 경로 (resumes/...)
        String analysisResultS3Key = null; // S3에 저장될 분석 결과 JSON 파일의 경로 (analysis-results/...)

        if (resumeFile != null && !resumeFile.isEmpty()) {
            // 이력서 파일 키 (경로) 생성: resumes/{userId}/{analysisId}/{originalFileName}
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
            // 원본 파일이 없을 경우 (텍스트 직접 입력 등) "N/A" 등으로 표시 (DynamoDB에 저장될 값)
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

        // DynamoDB에 저장할 메타데이터 구성
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("analysisId", AttributeValue.builder().s(analysisId).build());
        item.put("analysisTimestamp", AttributeValue.builder().n(timestamp).build());
        item.put("originalFileName", AttributeValue.builder().s(resumeFile != null ? resumeFile.getOriginalFilename() : "텍스트 직접 입력").build());
        item.put("s3ResumePath", AttributeValue.builder().s(resumeFileKey).build()); // 원본 이력서 S3 경로
        item.put("s3AnalysisResultPath", AttributeValue.builder().s(analysisResultS3Key).build()); // 분석 결과 S3 경로
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
                simplifiedItem.put("s3ResumePath", item.get("s3ResumePath") != null ? item.get("s3ResumePath").s() : ""); // DynamoDB에서 조회한 원본 이력서 S3 경로 추가

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

    /**
     * 특정 사용자의 이력서 분석 기록을 삭제합니다.
     * DynamoDB에서 메타데이터를 삭제하고, S3의 원본 파일과 분석 결과 JSON 파일도 삭제합니다.
     * @param userId 삭제를 요청한 사용자 ID (보안 검증용)

     * @return 삭제 성공 여부
     */
    // 매개변수 이름을 다시 analysisId로 변경합니다. (JSP에서 UUID가 넘어오므로)
    public boolean deleteAnalysisResult(String userId, String analysisId) {
        if (userId == null || userId.isEmpty() || analysisId == null || analysisId.isEmpty()) {
            log.warn("삭제 요청에 필요한 사용자 ID 또는 분석 ID가 누락되었습니다. userId={}, analysisId={}", userId, analysisId);
            return false;
        }

        // --- 수정 시작: analysisId (UUID)로 analysisTimestamp (Number)를 찾아야 합니다. ---
        // DynamoDB 테이블 스키마: 파티션 키(userId), 정렬 키(analysisTimestamp)
        // analysisId는 DynamoDB 항목의 일반 속성으로 저장되어 있을 것입니다.

        String actualAnalysisTimestamp = null;
        Map<String, AttributeValue> itemToDelete = null;

        try {
            // 1. Query를 사용하여 userId와 analysisId로 항목을 찾아 analysisTimestamp를 얻습니다.
            //    이때 analysisId가 어떤 속성 이름으로 DynamoDB에 저장되어 있는지 알아야 합니다.
            //    예를 들어, DynamoDB 항목에 'analysisId'라는 속성이 있다고 가정합니다.
            //    만약 'analysisId'가 GSI(글로벌 보조 인덱스)로 설정되어 있다면 Query를 더 효율적으로 할 수 있지만,
            //    여기서는 파티션 키 'userId'를 사용하여 해당 사용자의 모든 항목을 조회한 후 필터링하는 방식으로 진행합니다.
            //    **주의: 이 방법은 해당 userId의 항목 수가 많으면 비효율적일 수 있습니다.
            //    이상적인 경우, analysisId에 대한 GSI가 있거나, analysisTimestamp 자체가 UUID여야 합니다.**

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(dynamoDbTableName)
                    .keyConditionExpression("userId = :userIdVal") // 파티션 키 조건
                    .filterExpression("analysisId = :analysisIdVal") // analysisId로 필터링
                    .expressionAttributeValues(Map.of(
                            ":userIdVal", AttributeValue.builder().s(userId).build(),
                            ":analysisIdVal", AttributeValue.builder().s(analysisId).build()
                    ))
                    .build();

            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
            List<Map<String, AttributeValue>> items = queryResponse.items();

            if (items.isEmpty()) {
                log.warn("DynamoDB에서 삭제할 항목을 찾을 수 없습니다. userId={}, analysisId={}", userId, analysisId);
                return false;
            }

            // 정확히 하나의 항목이 발견되었다고 가정합니다.
            itemToDelete = items.get(0);
            if (itemToDelete.containsKey("analysisTimestamp")) {
                actualAnalysisTimestamp = itemToDelete.get("analysisTimestamp").n(); // Number 타입이므로 .n()으로 가져옵니다.
            } else {
                log.warn("조회된 항목에 analysisTimestamp 속성이 없습니다. userId={}, analysisId={}", userId, analysisId);
                return false;
            }

            if (actualAnalysisTimestamp == null || actualAnalysisTimestamp.isEmpty()) {
                log.warn("조회된 analysisTimestamp 값이 유효하지 않습니다. userId={}, analysisId={}", userId, analysisId);
                return false;
            }

            // --- 수정 끝 ---


            // S3 경로 추출 - 기존 코드와 동일
            String s3ResumePath = itemToDelete.get("s3ResumePath") != null ? itemToDelete.get("s3ResumePath").s() : null;
            String s3AnalysisResultPath = itemToDelete.get("s3AnalysisResultPath") != null ? itemToDelete.get("s3AnalysisResultPath").s() : null;

            // 2. S3 객체 삭제 (원본 이력서 파일) - 기존 코드와 동일
            if (s3ResumePath != null && !"N/A".equals(s3ResumePath)) {
                try {
                    DeleteObjectRequest deleteResumeFileRequest = DeleteObjectRequest.builder()
                            .bucket(s3BucketName)
                            .key(s3ResumePath)
                            .build();
                    s3Client.deleteObject(deleteResumeFileRequest);
                    log.info("S3 원본 이력서 파일 삭제 완료: {}", s3ResumePath);
                } catch (Exception e) {
                    log.error("S3 원본 이력서 파일 삭제 실패 (경로: {}): {}", s3ResumePath, e.getMessage());
                }
            }

            // 3. S3 객체 삭제 (분석 결과 JSON 파일) - 기존 코드와 동일
            if (s3AnalysisResultPath != null) {
                try {
                    DeleteObjectRequest deleteAnalysisResultRequest = DeleteObjectRequest.builder()
                            .bucket(s3BucketName)
                            .key(s3AnalysisResultPath)
                            .build();
                    s3Client.deleteObject(deleteAnalysisResultRequest);
                    log.info("S3 분석 결과 JSON 파일 삭제 완료: {}", s3AnalysisResultPath);
                } catch (Exception e) {
                    log.error("S3 분석 결과 JSON 파일 삭제 실패 (경로: {}): {}", s3AnalysisResultPath, e.getMessage());
                }
            }

            // 4. DynamoDB 항목 삭제
            Map<String, AttributeValue> deleteKey = new HashMap<>();
            deleteKey.put("userId", AttributeValue.builder().s(userId).build()); // 파티션 키
            deleteKey.put("analysisTimestamp", AttributeValue.builder().n(actualAnalysisTimestamp).build()); // **조회된 실제 타임스탬프 사용 (Number 타입)**

            DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                    .tableName(dynamoDbTableName)
                    .key(deleteKey)
                    .build();

            dynamoDbClient.deleteItem(deleteItemRequest);
            log.info("DynamoDB 항목 삭제 완료: userId={}, analysisId={}, actualAnalysisTimestamp={}", userId, analysisId, actualAnalysisTimestamp);
            return true;

        } catch (DynamoDbException e) {
            log.error("이력서 분석 기록 삭제 중 DynamoDB 오류 발생: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("이력서 분석 기록 삭제 중 일반 오류 발생: {}", e.getMessage(), e);
            return false;
        }
    }
}