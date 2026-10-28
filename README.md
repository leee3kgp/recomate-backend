# recomate-backend
RecoMate 백엔드 프로젝트

## RecommendationSession DynamoDB 테이블

| 항목 | 값 |
|---|---|
| 테이블명 | `RecommendationSession` (환경변수 `SESSION_TABLE_NAME`) |
| PK | `sessionId` (String) |
| GSI | `userId-createdAt-index` — PK `userId`(String), SK `createdAt`(String, ISO-8601) |

```bash
aws dynamodb create-table --table-name RecommendationSession \
  --attribute-definitions AttributeName=sessionId,AttributeType=S AttributeName=userId,AttributeType=S AttributeName=createdAt,AttributeType=S \
  --key-schema AttributeName=sessionId,KeyType=HASH \
  --global-secondary-indexes 'IndexName=userId-createdAt-index,KeySchema=[{AttributeName=userId,KeyType=HASH},{AttributeName=createdAt,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
  --billing-mode PAY_PER_REQUEST
```

## POST /api/sessions

- 인증: `Authorization: Bearer <Cognito JWT>` (userId = JWT `sub`)
- Request: `{ "participantCount": 2, "participants": [ { "index": 1, "genres": ["ACTION","SF"] }, { "index": 2, "genres": ["DRAMA"] } ] }`
- Success: `201 Created` + `Location: /api/sessions/{sessionId}`, body = `{ sessionId, userId, participantCount, participants, recommendations: [], createdAt }`
- Error: `400` `{ "code": "VALIDATION_ERROR | INVALID_REQUEST | MALFORMED_JSON", "message": "..." }`
