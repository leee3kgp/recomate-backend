# 본 작품 API

로그인 사용자가 본 작품을 사용자 단위로 저장·조회·삭제하고, 새 추천에서 본 작품을 자동으로 뺄지 설정합니다. 프로필 화면의 본 작품 목록과 설정에 쓰며 **모든 경로에 로그인(Cognito ID Token)이 필요합니다.** 전체 스키마는 [OpenAPI](openapi.yaml)의 `listMyWatched`·`addMyWatched`·`removeMyWatched`·`getMySettings`·`updateMySettings`를 따릅니다. 구현 이슈는 [#36](https://github.com/leee3kgp/recomate-backend/issues/36)입니다.

## 경로

| 메서드·경로 | 설명 | 성공 |
| --- | --- | --- |
| `GET /api/users/me/watched?limit=20&nextToken=…` | 최근에 저장한 순서로 목록 조회 | 200 |
| `POST /api/users/me/watched` | 작품 하나 저장 (`{"tmdbId": 603, "mediaType": "movie"}`) | 200 |
| `DELETE /api/users/me/watched/{mediaType}/{tmdbId}` | 작품 하나 삭제 | 204 |
| `GET /api/users/me/settings` | 설정 조회 | 200 |
| `PATCH /api/users/me/settings` | 설정 변경 (`{"excludeWatched": false}`) | 200 |

## 동작

- **"이미 봤어요" 자동 저장:** `POST /api/sessions/{sessionId}/exclusions`가 성공하면 그 작품이 본 작품에 저장됩니다. 세션 갱신과 같은 DynamoDB 트랜잭션이라 둘 다 저장되거나 둘 다 저장되지 않습니다. 프론트가 따로 호출할 필요가 없습니다.
- **직접 저장:** `POST`는 `tmdbId`와 `mediaType`만 받고, 제목·포스터·개봉 연도는 서버가 TMDB에서 조회합니다. 클라이언트가 보낸 제목은 믿지 않습니다. 같은 작품을 다시 저장하면 중복 없이 저장 시각만 갱신되어 목록 맨 위로 올라갑니다.
- **자동 제외:** `excludeWatched`가 `true`(기본값)이면 `POST /api/sessions/{sessionId}/recommendations`에서 TMDB 후보 중 본 작품을 **점수 계산 전에** 뺍니다. 그래서 #35의 "모든 참여자 최소 한 편 일치" 고르기는 그대로 적용되고, TOP 3와 저장되는 예비 후보 모두에 본 작품이 나오지 않습니다. 본 작품이 아주 많으면 결과가 3개보다 적을 수 있습니다. 비회원 추천은 그대로입니다.
- **설정 기본값:** 설정을 바꾼 적이 없으면 `excludeWatched`는 `true`입니다. `GET`은 프로필을 만들지 않고, `PATCH`는 장르 저장과 같이 프로필을 먼저 초기화합니다. 프로필 응답(`GET /api/users/me`)은 바뀌지 않습니다.
- **탈퇴:** 탈퇴하면 세션 다음, Cognito 삭제 전에 본 작품을 모두 지웁니다. DELETING 상태가 먼저 저장되므로 그 뒤에 들어온 저장은 트랜잭션 조건으로 거절됩니다.

## 응답

```json
{
  "items": [
    { "tmdbId": 1399, "mediaType": "tv", "title": "왕좌의 게임", "posterPath": "/example.jpg", "releaseYear": 2011, "watchedAt": "2026-11-16T09:00:00.000Z" }
  ],
  "nextToken": null
}
```

- `POST`는 저장된 항목 하나를 같은 모양으로 돌려줍니다. `posterPath`·`releaseYear`는 TMDB에 없으면 빠집니다.
- `nextToken`이 `null`이 될 때까지 그대로 다시 보내면 다음 페이지입니다. 세션 목록과 같은 규칙으로, 다른 사용자의 토큰은 400입니다.

## 오류

| 조건 | 상태 코드 | 오류 코드 |
| --- | --- | --- |
| `limit` 범위 밖·알 수 없는 파라미터·잘못된 `nextToken`, 잘못된 본문·경로, `excludeWatched`가 boolean이 아님 | 400 | VALIDATION_ERROR |
| 토큰 문제, 탈퇴 중·탈퇴한 사용자 | 401 | UNAUTHORIZED |
| TMDB에 없는 작품(성인물 포함) | 404 | NOT_FOUND |
| 처음 프로필 초기화 시 닉네임이 없거나 잘못됨 (`POST`, `PATCH`) | 409 | NICKNAME_REQUIRED |
| TMDB 호출 실패 (`POST`) | 502 | UPSTREAM_ERROR |

삭제는 없는 작품이어도 204입니다.

## 저장 구조

새 DynamoDB 테이블 `<스택 이름>-watched`(dev: `recomate-user-api-dev-watched`)를 씁니다. 목록이 계속 늘어나므로 400KB 제한이 있는 사용자 항목에 넣지 않았습니다.

- 키: `userId`(JWT sub) + `contentKey`(`mediaType:tmdbId`)
- 로컬 보조 인덱스 `userId-watchedAt-index`: 최근 저장 순 목록을 강한 일관성으로 조회
- 설정 `excludeWatched`는 사용자 테이블 항목의 boolean 속성이며, 없으면 `true`로 봅니다.

## 인프라와 검증

- `infra/user-api.yaml`: 테이블(삭제 보호·PITR·삭제 시 보존), Lambda 권한(`Query`·`PutItem`·`DeleteItem`·`BatchWriteItem`, 테이블과 인덱스로 한정), 환경 변수 `WATCHED_TABLE`, `/api/users/me/watched`·`/{mediaType}/{tmdbId}`·`/api/users/me/settings` 경로(Cognito Authorizer, OPTIONS는 인증 없음)를 추가했습니다. 배포 스냅샷은 `ApiDeploymentWatchedV1`입니다. Lambda 호출 권한은 기존 `/api/users/me*` 권한이 새 경로까지 포함합니다.
- 자동 테스트: `WatchedApiTests`(인증, 목록·페이지 토큰, TMDB 데이터 저장, 404·502, 입력 검사, 삭제, 탈퇴 사용자), `UserApiTests`(설정 기본값·변경·입력 검사, 탈퇴 시 본 작품 삭제 순서), `SessionCommandApiTests`(설정에 따른 자동 제외, 제외 시 본 작품 저장), `TmdbRecommendationGeneratorTest`(점수 계산 전 제외). `DynamoDbLocalTests`는 실제 DynamoDB 프로토콜로 정렬·페이지·재저장, 탈퇴 차단·전체 삭제, 제외와 본 작품의 트랜잭션 원자성을 확인합니다.
- 로컬 검증(2026-11-16): DynamoDB Local 포함 Gradle 테스트 253개 실패·건너뜀 0개, cfn-lint 1.58.0으로 템플릿 4개 오류·경고 0개, 선택한 Guard 규칙 6개 통과(새 테이블 PITR 포함), OpenAPI 3.1 문서 검증 통과. IAM Policy Autopilot 0.3.0으로 소스에서 다시 만든 권한 초안의 DynamoDB 작업이 템플릿 권한과 일치함을 확인했습니다(`scripts/generate-runtime-policy.ps1`에 새 저장소 추가).
- dev 배포(2026-11-16, 이미지 `src-2bfaea9733bddde6`, 배포 ID `rl5w5l`): 배포 환경 자동 점검 113개를 모두 통과했습니다. 상세 기록은 [컨테이너 배포](container-deployment.md#본-작품-api-배포-2026-11-16)를 참고합니다.
- [배포 환경 자동 점검](live-check.md): 새 경로의 401·CORS, 설정·입력 검사, 제외 시 자동 저장, 자동 제외, TMDB 저장·404, 삭제, 탈퇴 후 본 작품 삭제를 확인합니다.
