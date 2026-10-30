# 장르 목록 API

`GET /api/genres`는 RecoMate 장르 마스터 v1의 10개 코드와 표시명을 반환합니다. 응답의 `genres` 배열과 항목의 `code`, `name` 필드는 담당자 승인으로 확정했습니다. 2026-10-29 개발용 AWS 배포와 실제 호출 검증을 완료했습니다. FE 화면에서 이 API를 사용하는 연결 작업은 별도입니다.

## 요청

```http
GET /api/genres
Authorization: Bearer <Cognito ID Token>
```

요청 본문과 필수 쿼리 파라미터는 없습니다. 개발용 URL은 `https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev/api/genres`, 로컬 기본 주소는 `http://localhost:8080`입니다. 실행 방법과 AWS 접근 설정은 [개발 환경](development.md)을 따릅니다. 기존 프론트와 동일한 Cognito ID Token을 사용합니다.

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

프론트는 `genres.js`의 고정 목록 대신 인증된 요청으로 받은 `genres`를 사용하도록 별도 연결해야 합니다. AWS 배포·실제 호출 검증은 아래 기록을 따르며 FE 화면 연결은 별도 작업입니다. 사용자 API와 공유 RecommendationSession 연결은 최신 main에서 반영했으며 세션 생성·추천 저장의 ACTIVE 조건 트랜잭션도 유지합니다.

장르 배포 템플릿은 사용자·세션·장르 메서드 전체를 포함하는 ApiDeploymentGenreCatalogV1 스냅샷으로 기존 dev Stage를 갱신했습니다. 기존 #6 이미지는 아래 새 이미지로 갱신했고 사용자·세션 테이블과 데이터 권한은 유지했습니다.

## 검증 결과

2026-10-28에 임시 DynamoDB Local을 포함한 전체 테스트 92개(실패·건너뜀 0개)와 실행 JAR 빌드를 통과했습니다. 장르 목록의 정확한 코드·표시명·순서, 프로필 없는 사용자 조회, 개인 선호 장르와의 독립성, JWT 정상·오류 요청, 탈퇴 상태 차단, CORS 사전 요청과 사용자 API의 전체 장르 저장을 검증합니다. 기존 사용자 API·저장소·탈퇴 복구 테스트도 함께 실행했습니다.

2026-10-28에는 OpenAPI 응답 스키마·예시와 API 테스트의 기대 응답을 대조했고, 수정한 CloudFormation 템플릿의 cfn-lint 오류·경고 0개와 기존 선택 Guard 규칙 6개 통과를 확인했습니다. 컨테이너 검사 스크립트에는 새 경로를 추가하고 Bash 문법을 검사했습니다. 당시에는 로컬 Docker 재빌드와 실제 AWS 배포·FE 연결 검증을 진행하지 않은 상태였습니다.

2026-10-29 최신 main(`c2604ae`)의 사용자·세션 연동과 CI 구성을 장르 코드에 반영하고 충돌을 정리했습니다. 실제 작업 트리에서 DynamoDB Local을 포함해 테스트 119개(실패·오류·건너뜀 0개)와 bootJar를 통과했습니다. OpenAPI 참조 37개, 장르 예시 10개, 배포 템플릿에 정의한 메서드 11개를 확인했고, 사용자·세션·데이터 권한 리소스가 main과 동일하게 유지되는 것도 대조했습니다.

CloudFormation 3개 템플릿의 cfn-lint, 선택한 Guard 규칙, Compose 설정, 워크플로 문법과 Bash 문법 검사도 통과했습니다. 새 빌드 소스 해시는 `d955fea7152f7939ef307f5b8cdebb8a84551e0d8564a373a0a41bbc6887dd0b`입니다. 이 로컬 검증 이후 아래 AWS 배포·실제 호출도 확인했습니다. 코드 반영·리뷰 상태는 [장르 API 이슈 #8](https://github.com/leee3kgp/recomate-backend/issues/8)에서 확인합니다.

## 실제 AWS 배포·호출 결과 (2026-10-29)

- CodeBuild: `recomate-build-dev:39fd4fbf-42ff-4714-a49e-799b45012dfe`, SUCCEEDED. linux/amd64 Docker 빌드와 읽기 전용 컨테이너의 사용자·세션·장르 인증/CORS 검사가 통과했습니다.
- 이미지: `src-d955fea7152f7939`, digest `sha256:f691338b35827ab3579f8317749a8a39342f9e60a5427e38f4d7e7994725911d`. ECR basic scan은 COMPLETE, 발견 항목 0개입니다.
- 변경 계획: `genre-catalog-d955fea7152f7939`. 서비스 사전 검증 오류 0개를 확인하고 사용자 승인 후 적용했습니다. 스택은 UPDATE_COMPLETE, Lambda는 Active/Successful이며 실제 이미지 digest가 위 결과와 같습니다.
- API Gateway 장르 리소스: `afkgd7`. GET은 기존 Cognito Authorizer `2oetm8`, OPTIONS는 NONE이며 모두 기존 Lambda의 AWS_PROXY 통합을 사용합니다. dev Stage의 배포 ID는 `uky3vl`입니다.

실제 검증 항목 77개가 통과했습니다. 새 장르 경로에서는 실제 SRP 로그인 후 정확한 코드·표시명·순서와 중복 없는 10개 응답, 프로필 없는 사용자·유효하지 않은 초기 닉네임 사용자 조회, 개인 선호 장르와의 독립성을 확인했습니다. 토큰 누락·형식 오류·서명 변조·Access Token·탈퇴 사용자는 공통 형식의 401로 거절됐고 CORS 사전 요청은 200이었습니다.

사용자 프로필·닉네임·선호 장르, 세션 생성·추천 결과 저장, 본인 데이터 삭제·다른 사용자 데이터 보존, 반복 탈퇴, 실제 DynamoDB의 탈퇴 후 쓰기 차단도 함께 확인했습니다. 추천 결과는 테스트용 값이며 TMDB·추천 알고리즘·추천 생성 HTTP API를 검증한 것은 아닙니다. 임시 사용자 3명과 세션은 정리했고 최소 탈퇴 TTL 상태만 남겼습니다. 비밀번호·토큰은 프로세스 메모리에서만 사용했습니다.

만료 토큰과 잘못된 issuer·audience·token_use, 접근 검사 실패의 500은 로컬 서명 JWT·오류 주입 테스트에 포함됩니다. 실제 만료된 Cognito 토큰의 공통 Authorizer 검증은 #5 기록을 따르며 이번 장르 실제 호출 77개에서 만료를 새로 기다려 시험한 것은 아닙니다. FE 화면 연결은 별도 확인 대상입니다.

## 진행 상태 재확인 (2026-10-30)

현재 소스는 위 이미지의 빌드 소스와 일치합니다. main을 반영한 장르 브랜치 커밋 `4206389`의 [GitHub Actions 실행](https://github.com/leee3kgp/recomate-backend/actions/runs/40835162023)에서도 `dynamodb-integration`과 `build-and-start`가 모두 성공했습니다.

AWS 로그인 세션을 갱신한 뒤 스택 UPDATE_COMPLETE, Lambda Active/Successful과 위 이미지 digest, dev 배포 ID `uky3vl`, 장르 GET의 기존 Cognito Authorizer 및 AWS_PROXY 통합을 다시 확인했습니다. 실제 SRP 로그인부터 장르 10개 응답·인증 실패·CORS·사용자·세션·탈퇴까지 전체 77개 항목을 다시 실행해 모두 통과했습니다. 이번 임시 사용자 3명과 테스트 세션도 정리했으며 최소 탈퇴 TTL 상태만 남겼습니다. 만료 토큰·500 오류의 검증 범위는 위 기록과 같습니다.
