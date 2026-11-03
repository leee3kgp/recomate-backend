# "이미 봤어요" 제외 API (#18)

추천 결과에서 이미 본 콘텐츠를 빼고 다음 후보로 채웁니다. 추천 생성 시 점수순 후보를 세션에 함께 저장하므로 제외할 때 TMDB를 다시 호출하지 않습니다. "이미 봤어요" 버튼 UI는 FE2, FE 추천·제외 흐름 연동 테스트는 BE2 작업 범위입니다. 전체 스키마는 [OpenAPI](openapi.yaml)의 `excludeRecommendation`을 따릅니다.

## 결정 사항

| 항목 | 결정 | 이유 |
| --- | --- | --- |
| 경로 | `POST /api/sessions/{sessionId}/exclusions` | 명세 시트의 `.../recommendations/refresh`는 "전체 다시 뽑기"라 작품 1개 제외와 의미가 다릅니다. refresh가 필요해지면 같은 저장 후보로 구현할 수 있습니다. |
| 응답 | 추천 생성 API와 같은 `{sessionId, recommendations, analysis}` | 결과 화면의 렌더링 코드를 그대로 사용합니다. |
| 교체 방식 | 제외한 작품만 빼고 나머지를 한 칸씩 올린 뒤 다음 후보를 마지막 순위에 추가 | 후보는 점수 내림차순이라 다시 정렬할 필요가 없습니다. |
| 후보 소진 | 남은 작품만 반환(2개 이하, 0개 가능) | 이미 본 작품을 계속 보여주지 않습니다. |
| 일치율 | `participantMatch`만 새 작품 기준으로 다시 계산, `genreScores`는 유지 | genreScores는 참여자 선택 기준이라 바뀌지 않습니다. |
| 재생성 | 작품을 1개 이상 저장한 세션은 모두 제외해도 `POST .../recommendations`가 409 | 유료 외부 호출 반복과 결과 덮어쓰기를 막습니다. TMDB 후보가 아예 없던 빈 결과는 기존처럼 다시 생성할 수 있습니다. |

## 요청

```http
POST /api/sessions/{sessionId}/exclusions
Authorization: Bearer <Cognito ID Token>
Content-Type: application/json

{ "tmdbId": 603, "mediaType": "movie" }
```

- `tmdbId`와 `mediaType`을 함께 사용합니다. TMDB는 영화와 TV의 ID가 겹칠 수 있어 `tmdbId`만으로는 작품을 구분할 수 없습니다.
- 대상은 **현재 추천 결과에 있는 작품**이어야 합니다. 결과 화면의 `recommendations[i].tmdbId`, `recommendations[i].mediaType` 값을 그대로 보냅니다.

## 응답

```json
{
  "sessionId": "01234567-89ab-4cde-8fab-0123456789ab",
  "recommendations": [
    { "rank": 1, "tmdbId": 27205, "mediaType": "movie", "title": "인셉션", "score": 81.2, "genres": ["ACTION", "SF"], "matchedParticipants": [1, 2] },
    { "rank": 2, "tmdbId": 1399, "mediaType": "tv", "title": "왕좌의 게임", "score": 77.9, "genres": ["DRAMA", "FANTASY"], "matchedParticipants": [2] },
    { "rank": 3, "tmdbId": 157336, "mediaType": "movie", "title": "인터스텔라", "score": 76.4, "genres": ["SF", "DRAMA"], "matchedParticipants": [1, 2] }
  ],
  "analysis": { "genreScores": [ { "genre": "SF", "count": 2 } ], "participantMatch": [ { "index": 1, "matchRate": 0.83 }, { "index": 2, "matchRate": 0.67 } ] }
}
```

후보와 제외 목록은 내부 데이터라 응답과 세션 상세 조회에 포함하지 않습니다. `GET /api/sessions/{sessionId}`는 제외가 반영된 결과를 반환합니다.

| 상태 | 코드 | 상황 | FE 처리 |
| --- | --- | --- | --- |
| 200 | - | 제외 완료 또는 이미 제외한 작품의 재요청 | 응답의 `recommendations`, `analysis`로 화면 갱신 |
| 400 | `VALIDATION_ERROR` | 잘못된 sessionId·본문, 현재 결과에 없는 작품 | 화면을 다시 조회 |
| 401 | `UNAUTHORIZED` | 토큰 없음·만료·탈퇴 진행 | 기존 인증 처리와 동일 |
| 404 | `NOT_FOUND` | 없는 세션 또는 다른 사용자의 세션 | 기존 세션 404 처리와 동일 |
| 409 | `CONCURRENT_UPDATE` | 같은 세션에 동시 제외가 반복 충돌 | 잠시 후 재시도 |

버튼을 연속으로 눌러도 같은 작품은 한 번만 제외되고 200을 반환합니다. 다만 화면에서는 요청 중 버튼을 비활성화하는 것을 권장합니다.

### FE 결과 저장본 처리

현재 `js/result.js`는 결과를 `sessionStorage`의 `recomate:result:{userId}:{sessionId}`에 저장하고, 빈 결과는 저장하지 않습니다. 제외 후에는 아래처럼 처리해야 새로고침 시 이미 제외한 작품이 다시 보이지 않습니다.

- 200 응답의 `{recommendations, analysis}`로 저장본을 **교체**합니다.
- 응답의 `recommendations`가 빈 배열이면 저장본을 **삭제**(`sessionStorage.removeItem`)합니다. 기존 `saveCache`는 빈 결과를 건너뛰므로 그대로 호출하면 이전 결과가 남습니다.
- 저장본이 없으면 기존 흐름대로 추천 생성 요청이 409 `RECOMMENDATION_EXISTS`를 받고 세션 상세 조회로 최신 결과를 표시합니다.

## 저장 구조

추천은 기존처럼 세션 아이템 안에 저장하며, 아래 내부 속성을 추가했습니다. 테이블·키·GSI는 바꾸지 않았습니다.

| 속성 | 형식 | 설명 |
| --- | --- | --- |
| `candidates` | List | TOP 3 다음 순위 후보 최대 20개, 점수 내림차순, `rank` 없음 |
| `excluded` | List of String | 제외한 작품 `"mediaType:tmdbId"` |
| `version` | Number | 작품을 1개 이상 저장한 생성 시 1, 제외할 때마다 +1 |

- 후보 20개는 작품당 약 300바이트로 아이템 최대 크기 400KB에 크게 못 미칩니다.
- 세션 GSI `userId-createdAt-index`는 `ProjectionType: ALL`이라 후보도 GSI에 복제됩니다. 아이템이 약 6KB로 커져 생성·제외 쓰기와 목록 조회의 소비 용량이 몇 단위 늘지만, 온디맨드 요금 기준 이 프로젝트 규모에서는 무시할 수준입니다. 테이블 설계(#3)는 변경하지 않았습니다.
- 새 세션 생성 시에는 이 속성을 쓰지 않아 기존 아이템 형태가 그대로입니다. #18 이전에 만든 세션은 후보가 없어 작품만 빠집니다.

## 동시성과 탈퇴

- 제외 쓰기는 **읽은 시점의 version과 같을 때만** 저장합니다(낙관적 잠금). 다른 작품을 동시에 제외해 충돌하면 서버가 최신 상태를 다시 읽어 한 번 재시도하고, 또 충돌하면 409를 반환합니다.
- 기존 세션 쓰기와 같이 사용자 ACTIVE 확인을 같은 DynamoDB 트랜잭션에 넣어, 탈퇴 진행 중에는 저장하지 않고 401을 반환합니다.
- 추천 생성은 `version`이 없을 때만 저장하므로, 모든 작품을 제외해도 다시 생성되지 않습니다. 빈 생성 결과에는 `version`을 쓰지 않아 기존처럼 재시도할 수 있습니다.

## 배포 구성

- `infra/user-api.yaml`에 `/exclusions` 리소스와 POST(Cognito Authorizer)·OPTIONS(인증 없음) 메서드를 추가하고, 새 배포 스냅샷 `ApiDeploymentSessionExclusionsV1`으로 교체했습니다.
- Lambda 호출 권한은 기존 `/api/sessions/*` 범위에 포함되고, 실행 역할은 기존 세션 테이블 `UpdateItem` 권한을 사용하므로 IAM 변경이 없습니다.
- 컨테이너 검사 스크립트에 새 경로의 401·CORS 사전 요청 검사를 추가했습니다.

## 검증

- Gradle 테스트 203개, 실패·오류·건너뜀 0개(DynamoDB Local 실제 조건식 테스트 20개 포함).
- CloudFormation 3개 템플릿 cfn-lint 1.58.0 오류·경고 0개, 컨테이너 검사 스크립트 Bash 문법 검사 통과.
- OpenAPI 3.1 스키마 검증 통과, 로컬 참조 78개 확인.
- 2026-11-03 AWS dev 배포(이미지 `src-a46b4b3fc58f8d58`, 배포 ID `m0s661`) 후 실제 API 검증 23개 항목과 전체 12개 경로의 401·CORS 사전 요청 검사를 통과했습니다. 상세 기록은 [컨테이너 배포](container-deployment.md)를 따릅니다.
- FE 화면 연동("이미 봤어요" 버튼)은 FE2 작업으로 아직 진행하지 않았습니다.
