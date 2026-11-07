# 비회원 추천 API

로그인하지 않은 방문자도 추천을 받을 수 있게 하는 공개 API입니다. 회원과 같은 추천 생성기와 점수 계산을 쓰지만 **서버에 아무것도 저장하지 않습니다.** 비회원의 "이미 봤어요"도 서버에 기록하지 않고, 브라우저가 가진 제외 목록을 요청마다 함께 보냅니다. 전체 스키마는 [OpenAPI](openapi.yaml)의 `createGuestRecommendations`를 따릅니다.

## 결정 사항

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 경로 | `POST /api/guest/recommendations` | 회원 세션 경로(`/api/sessions`)는 JWT `sub`로 소유자를 확인합니다. 비회원을 섞지 않아 회원 기능의 소유자 검사를 그대로 둡니다. |
| 저장 | 하지 않음 | 모르는 사람의 데이터가 쌓이지 않고 만료·삭제 처리도 필요 없습니다. 추천 기록은 로그인한 회원에게만 남습니다. |
| "이미 봤어요" | 요청의 `excluded`로 처리, 서버 기록 없음 | 사용자 데이터에 기록되지 않으므로 비회원도 사용할 수 있습니다. 회원은 기존처럼 세션에 기록되는 [제외 API](exclusion-api.md)를 씁니다. |
| 장르 목록 | `GET /api/genres` 공개 | 비회원도 장르를 골라야 합니다. 목록은 고정 값이라 사용자 데이터가 없습니다. |
| 남용 방지 | API Gateway Stage에서 이 메서드만 초당 2회, 순간 5회로 제한 | 누구나 부를 수 있고 호출마다 TMDB를 부르므로 회원 경로(초당 5회, 순간 10회)보다 낮게 둡니다. 초과하면 429입니다. |

## 요청

```http
POST /api/guest/recommendations
Content-Type: application/json

{
  "participantCount": 2,
  "participants": [
    { "index": 1, "genres": ["SF", "ACTION"] },
    { "index": 2, "genres": ["COMEDY"] }
  ],
  "excluded": [
    { "tmdbId": 603, "mediaType": "movie" }
  ]
}
```

- `participantCount`, `participants`는 회원 세션 생성(`POST /api/sessions`)과 같은 규칙으로 검사합니다. 인원 2~6명, index 1부터 중복 없이, 장르 마스터 코드 1개 이상.
- `excluded`는 생략할 수 있습니다. 최대 20개이고 `tmdbId`와 `mediaType`을 함께 비교합니다. 영화와 TV는 ID가 겹칠 수 있습니다.
- `Authorization` 헤더는 보내지 않습니다. 잘못된 토큰을 보내면 다른 경로와 같이 401입니다.

## 응답

```json
{
  "recommendations": [
    { "rank": 1, "tmdbId": 1399, "mediaType": "tv", "title": "...", "posterPath": "/...", "score": 87.5,
      "releaseYear": 2011, "voteAverage": 8.4, "genres": ["ACTION", "DRAMA", "FANTASY"], "matchedParticipants": [1] }
  ],
  "analysis": {
    "genreScores": [{ "genre": "SF", "count": 1 }],
    "participantMatch": [{ "index": 1, "matchRate": 0.67 }]
  }
}
```

- 회원 추천 생성 응답(`{sessionId, recommendations, analysis}`)에서 `sessionId`만 없는 같은 모양입니다. 결과 화면의 렌더링 코드를 그대로 쓸 수 있습니다.
- `excluded`가 있으면 그 작품을 빼고 남은 작품은 순서를 유지하며, 점수순 다음 후보가 빈자리를 채웁니다. 회원 제외 API와 같은 방식입니다. `participantMatch`는 보여주는 작품 기준으로 다시 계산합니다.
- 회원은 생성 때 저장한 후보를 쓰지만 비회원은 요청마다 TMDB를 다시 조회합니다. 그 사이 TMDB 인기 목록이 바뀌면 빼지 않은 작품도 달라질 수 있습니다. 화면은 응답을 그대로 다시 그리면 됩니다.
- 후보가 모자라면 3개보다 적게(0개 포함) 반환합니다.
- 응답은 `Cache-Control: no-store`입니다.

## 오류

| 조건 | 상태 코드 | 오류 코드 |
| --- | --- | --- |
| JSON 형식 오류, 인원·참여자 수 범위, `excluded` 21개 이상, 잘못된 `tmdbId`·`mediaType` | 400 | VALIDATION_ERROR |
| 인원과 참여자 수 불일치, index 중복, 없는 장르 코드 | 400 | INVALID_REQUEST |
| 잘못된 토큰을 보냄 | 401 | UNAUTHORIZED |
| API Gateway 호출 제한 초과 | 429 | THROTTLED |
| TMDB 호출 실패 | 502 | UPSTREAM_ERROR |

입력 오류는 TMDB를 부르기 전에 거절합니다.

## 프론트 연결

- 비회원은 장르 선택 후 이 API를 한 번 호출해 결과 화면을 그립니다.
- 비회원이 "이미 봤어요"를 누르면 그 작품을 브라우저의 제외 목록(예: `sessionStorage`)에 더하고, 같은 참여자와 `excluded`로 다시 호출합니다.
- 결과는 저장되지 않으므로 "로그인하면 추천 기록이 남아요" 안내를 함께 보여줍니다.

## 배포 순서

1. **백엔드 먼저:** 새 이미지와 `infra/user-api.yaml` 스택을 배포합니다. 기존 프론트는 회원 흐름만 쓰므로 영향이 없습니다.
2. **프론트 다음:** 비회원 흐름이 들어간 프론트를 배포합니다. 순서가 바뀌면 배포 전 백엔드에 비회원 경로가 없어 비회원 추천이 실패합니다(장르 목록은 고정 목록으로 대체되어 화면은 열립니다).
3. [배포 환경 자동 점검](live-check.md)으로 비회원 항목까지 확인합니다.

## 인프라와 검증

- `infra/user-api.yaml`: `/api/guest/recommendations` 리소스와 POST·OPTIONS(인증 없음), 경로를 제한한 Lambda 호출 권한, 이 메서드의 Stage 호출 제한을 추가했습니다. `GET /api/genres`는 Cognito Authorizer를 뺐습니다. 새 경로를 Stage에 반영하도록 배포 스냅샷을 `ApiDeploymentGuestRecommendationsV1`로 교체했습니다. IAM 실행 역할·테이블·Cognito·CORS·TMDB 토큰은 바뀌지 않습니다.
- 자동 테스트: `GuestRecommendationApiTests`가 로그인 없는 성공, 저장소 미사용, 제외 처리, 입력 오류, 회원 경로 401 유지, CORS를 확인합니다.
- 컨테이너 점검: `scripts/container-smoke.sh`(CI·CodeBuild)가 토큰 없는 장르 목록 200, 비회원 경로의 입력 검증 400과 사전 요청 200을 확인합니다. 컨테이너에는 TMDB 토큰이 없어 실제 추천은 배포 후 점검에서 확인합니다.
- 배포 후 점검: [배포 환경 자동 점검](live-check.md)에 비회원 항목을 추가했습니다.
