# 장르 목록 API

`GET /api/genres`는 RecoMate 장르 마스터 v1의 10개 코드와 표시명을 반환합니다. 응답의 `genres` 배열과 항목의 `code`, `name` 필드는 담당자 승인으로 확정했습니다. 현재 로컬 구현이며 AWS 배포와 실제 FE 연결은 아직 진행하지 않았습니다.

## 요청

```http
GET /api/genres
Authorization: Bearer <Cognito ID Token>
```

요청 본문과 필수 쿼리 파라미터는 없습니다. 로컬 기본 주소는 `http://localhost:8080`이며 실행 방법과 AWS 접근 설정은 [개발 환경](development.md)을 따릅니다. 기존 프론트와 동일한 Cognito ID Token을 사용합니다.

## 성공 응답

상태 코드 `200`, JSON UTF-8입니다. 다음 순서를 항상 유지하고, 개인 선호 장르와 관계없이 전체 10개를 반환합니다.

```json
{
  "genres": [
    { "code": "ACTION", "name": "액션" },
    { "code": "COMEDY", "name": "코미디" },
    { "code": "ROMANCE", "name": "로맨스" },
    { "code": "DRAMA", "name": "드라마" },
    { "code": "SF", "name": "SF" },
    { "code": "FANTASY", "name": "판타지" },
    { "code": "HORROR", "name": "공포" },
    { "code": "CRIME", "name": "범죄" },
    { "code": "MYSTERY", "name": "미스터리" },
    { "code": "ANIMATION", "name": "애니메이션" }
  ]
}
```

화면에는 `name`을 표시하고, 선호 장르 저장·추천 세션 생성 요청에는 `code`를 보냅니다. TMDB 장르 ID는 응답에 포함하지 않으며 기존 내부 코드와 TMDB 매핑 규칙을 변경하지 않습니다.

## 인증과 오류

| 조건 | 상태 코드 | 오류 코드 |
| --- | --- | --- |
| 토큰 누락·형식 오류·만료·위조·다른 앱 또는 Access Token 사용 | 401 | UNAUTHORIZED |
| 사용자 상태가 DELETING 또는 DELETED | 401 | UNAUTHORIZED |
| 공통 사용자 접근 검사 중 저장소 오류 | 500 | INTERNAL_ERROR |

오류 JSON은 기존 `{ "error": { "code": "...", "message": "..." } }` 형식을 따릅니다. 상세 스키마는 [OpenAPI](openapi.yaml)를 참고합니다.

목록은 공통 `Genre` 정의에서 가져오므로 별도 장르 테이블·TMDB 호출은 없습니다. 다만 기존 보안 필터가 탈퇴 상태를 확인하기 위해 사용자 테이블을 읽으므로, 로컬에서 실제 API를 호출하려면 해당 테이블에 대한 AWS 접근 설정이 필요합니다. 앱 프로필이 없는 사용자는 목록을 조회할 수 있고, 조회 중 프로필 생성이나 Cognito 초기 닉네임 확인은 하지 않습니다.

허용된 FE Origin의 `OPTIONS /api/genres` 사전 요청은 토큰 없이 처리합니다. 실제 GET은 인증이 필요합니다. 허용 Origin 설정은 기존 `CORS_ALLOWED_ORIGINS`를 사용합니다.

## 개인 선호 장르와의 차이

| API | 반환 또는 저장하는 값 |
| --- | --- |
| GET /api/genres | 전체 장르의 code/name 목록 |
| GET /api/users/me/genres | 본인이 저장한 코드 배열: `{ "genres": ["ACTION", "SF"] }` |
| PUT /api/users/me/genres | 본인의 코드 배열 전체 교체; 빈 배열은 기존대로 전체 해제 |

목록 조회와 개인 선호 장르 검증은 같은 `Genre` 정의를 사용합니다. 참여자마다 최소 한 장르를 요구하는 추천 세션의 입력 개수 규칙은 별도이며 이 작업에서 변경하지 않습니다.

## 배포·연동

`infra/user-api.yaml`에 기존 사용자 API와 같은 Lambda로 연결되는 장르 GET·OPTIONS, Cognito Authorizer, 경로를 제한한 호출 권한을 추가했습니다. 새 경로를 Stage에 반영하도록 API Deployment 스냅샷도 갱신합니다. 실제 배포 순서는 [컨테이너 배포](container-deployment.md)를 따릅니다.

프론트는 `genres.js`의 고정 목록 대신 인증된 요청으로 받은 `genres`를 사용하도록 별도 연결해야 합니다. 백엔드 구현 완료가 FE 연결·AWS 배포 완료를 의미하지 않습니다. 실제 BE2 RecommendationSession 연결 대기 상태도 유지합니다.

## 검증 결과

2026-10-28에 임시 DynamoDB Local을 포함한 전체 테스트 92개(실패·건너뜀 0개)와 실행 JAR 빌드를 통과했습니다. 장르 목록의 정확한 코드·표시명·순서, 프로필 없는 사용자 조회, 개인 선호 장르와의 독립성, JWT 정상·오류 요청, 탈퇴 상태 차단, CORS 사전 요청과 사용자 API의 전체 장르 저장을 검증합니다. 기존 사용자 API·저장소·탈퇴 복구 테스트도 함께 실행했습니다.

OpenAPI 응답 스키마·예시와 API 테스트의 기대 응답을 대조했고, 수정한 CloudFormation 템플릿의 cfn-lint 오류·경고 0개와 기존 선택 Guard 규칙 6개 통과를 확인했습니다. 컨테이너 검사 스크립트에는 새 경로를 추가하고 Bash 문법을 검사했습니다. 현재 PC에 Docker가 없어 실제 컨테이너 재빌드는 하지 않았으며, 실제 AWS 배포·FE 연결 검증도 남아 있습니다.
