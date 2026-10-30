# 세션 조회·삭제 API와 추천 저장 (#12)

기존 `RecommendationSession` 테이블에서 로그인 사용자의 목록과 상세를 읽습니다. 조회는 프로필·세션을 생성하거나 변경하지 않고 Cognito 프로필·TMDB·추천 알고리즘을 호출하지 않습니다. FE 화면 인증 연결과 추천 생성 API 구현은 별도 작업입니다.

## 공통 규칙

- `Authorization: Bearer <Cognito ID Token>`을 사용하고 소유자는 검증된 JWT의 `sub`로 결정합니다. 클라이언트의 userId를 받지 않습니다.
- 기존 DELETING/DELETED 접근 차단과 공통 오류 형식을 유지합니다. 앱 프로필이 없는 사용자도 조회할 수 있으며 최초 닉네임 초기화를 요구하지 않습니다.
- 성공 응답은 `Cache-Control: no-store`입니다. 허용된 Origin의 OPTIONS 사전 요청은 인증 없이 처리합니다.
- 기존 API 목록에 없는 페이지 처리·목록 필드·누락 필드 처리는 아래 구현 기준으로 구체화했습니다. 팀 리뷰·FE 연동 시 이 계약을 공유합니다.

## 내 세션 목록

```http
GET /api/sessions?limit=20&nextToken=<이전 응답의 토큰>
```

| 파라미터 | 기준 |
| --- | --- |
| limit | 선택, 기본 20. 1~100의 십진 정수. 공백·부호·소수·빈 값·앞자리 0은 거절 |
| nextToken | 선택. 이전 응답 값을 그대로 사용. 빈 값·잘못된 형식·다른 사용자의 토큰은 400 |

중복 파라미터와 정의하지 않은 파라미터는 400입니다. GET 요청 본문은 사용하지 않습니다.

```json
{
  "sessions": [
    { "sessionId": "01234567-89ab-4cde-8fab-0123456789ab", "participantCount": 2, "createdAt": "2026-10-30T00:00:00Z" }
  ],
  "nextToken": null
}
```

목록은 크기가 큰 참여자·추천 결과를 포함하지 않는 요약입니다. 세션이 없으면 `sessions: []`, 다음 조회 키가 없으면 `nextToken: null`을 반환합니다.

기존 GSI `userId-createdAt-index`를 `userId = JWT sub`, `ScanIndexForward=false`로 Query합니다. 정렬 기준은 **저장된 createdAt 문자열의 내림차순**이며 같은 값의 세션 간 순서는 보장하지 않습니다. 새 세션은 UTC ISO 8601의 밀리초 3자리를 고정해 문자열 순서와 시간 순서를 일치시킵니다. 기존 세션의 키는 유지하며 서로 다른 소수초 자릿수를 가진 과거 키가 같은 초 안에 섞이면 그 구간의 문자열 순서가 정확한 시간 순서와 다를 수 있습니다. 과거 데이터 마이그레이션은 수행하지 않습니다.

한 번에 지정한 limit 이하를 반환합니다. DynamoDB의 1 MB 조회 경계로 더 적게 반환할 수도 있습니다. `nextToken`이 있으면 이어서 조회하고, 다음 페이지가 비어 있을 수도 있습니다. 페이지 사이에 생성·삭제가 발생할 수 있으므로 전체 목록의 시점 고정 스냅샷은 아닙니다.

GSI는 최종 일관성을 사용해 생성·삭제 직후 반영이 늦을 수 있습니다. 생성 응답의 sessionId로 즉시 확인할 때는 아래 상세 조회를 사용합니다. 목록은 Scan하지 않으며 Query에서 요약 필드만 선택하지만 이 선택이 GSI 읽기 용량을 줄인다는 의미는 아닙니다.

페이지 토큰은 버전·소유자·마지막 sessionId/createdAt을 담은 URL-safe Base64 탐색 값입니다. 암호화·서명된 자격 증명이 아니며 인증에 사용하지 않습니다. 서버는 형식과 소유자를 검증하고 실제 Query의 소유자 키를 항상 JWT sub에서 다시 구성하므로 토큰으로 다른 사용자의 목록을 읽을 수 없습니다. 토큰을 직접 수정하지 않고 서버 응답을 그대로 사용해야 합니다.

## 내 세션 상세

```http
GET /api/sessions/{sessionId}
```

sessionId는 표준 하이픈 UUID 형식이며 대문자는 소문자로 정규화합니다. 쿼리 파라미터는 받지 않습니다. 기본 테이블을 강한 일관성 GetItem으로 읽고 본인 소유인지 확인합니다.

```json
{
  "sessionId": "01234567-89ab-4cde-8fab-0123456789ab",
  "userId": "Cognito-sub",
  "participantCount": 2,
  "participants": [ { "index": 1, "genres": ["ACTION"] }, { "index": 2, "genres": ["SF"] } ],
  "recommendations": [],
  "createdAt": "2026-10-30T00:00:00Z",
  "analysis": null
}
```

추천 결과가 없거나 과거 데이터에서 recommendations가 누락/null이면 빈 배열입니다. participants가 누락/null인 과거 데이터도 빈 배열로 읽습니다. sessionId·userId·participantCount·createdAt은 기존 세션의 필수 저장 필드입니다.

저장된 추천에는 rank·tmdbId·mediaType과 함께 title·posterPath·score·releaseYear·voteAverage·genres·matchedParticipants를 읽을 수 있습니다. 저장되지 않은 선택 추천 필드는 응답에서 생략하며 임의로 TMDB 정보나 0점을 만들어 채우지 않습니다. `analysis`는 저장된 genreScores/participantMatch를 반환하고 없으면 null입니다. 조회가 분석을 계산하거나 추천 생성을 실행하지 않습니다. 기존 생성 API의 응답 구조는 유지합니다.

## 세션 삭제

```http
DELETE /api/sessions/{sessionId}
```

성공하면 본문 없이 204를 반환합니다. 소유자 조건을 건 DeleteItem 한 번으로 처리해 세션에 포함된 추천 결과와 analysis도 함께 지워집니다. 없는 세션과 타인 세션은 같은 404이므로 반복 삭제도 404입니다. 삭제는 데이터를 늘리지 않으므로 ACTIVE 트랜잭션 없이 공통 탈퇴 상태 검사를 사용합니다.

## 추천 생성과 저장

```http
POST /api/sessions/{sessionId}/recommendations
```

요청 본문은 없습니다. `RecommendationService`가 본인 세션을 확인하고, 이미 추천이 있으면 외부 호출 전에 409를 반환합니다. 이후 `RecommendationGenerator`로 결과를 만들어 추천과 analysis를 세션 항목에 함께 저장합니다. 저장은 기존 ACTIVE·소유권·중복 조건 트랜잭션을 사용하므로 탈퇴 중에는 저장되지 않고 탈퇴 시 세션과 함께 삭제됩니다.

저장 시 genres는 장르 마스터 코드만, matchedParticipants와 analysis의 index는 1~6만 허용합니다. analysis의 count는 0 이상, matchRate는 0~1입니다. matchedParticipants는 세션 참여자에서 계산하므로 실제 인원수를 넘지 않습니다. 기존 `saveRecommendations` 3개 인자 메서드는 analysis 없이 저장하도록 유지합니다.

`TmdbRecommendationGenerator`가 #13의 TMDB 조회와 점수 계산을 연결합니다. 영화와 TV 후보를 조회해 상위 3개를 고르고, `genreScores`는 장르별 선택 참여자 수, `matchRate`는 추천 결과 중 해당 참여자와 장르가 일치하는 비율로 계산합니다. TMDB 호출 실패는 `502 UPSTREAM_ERROR`이며 저장하지 않습니다. 후보가 없으면 빈 결과를 반환하고 다시 생성할 수 있습니다.

Lambda에는 `TMDB_ACCESS_TOKEN`이 필요합니다. 값은 저장소에 넣지 않고 배포 시 `TmdbAccessToken` NoEcho 파라미터로 전달합니다.

## 오류

| 상태 | code | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 잘못된 UUID, limit, nextToken, 중복·알 수 없는 쿼리 파라미터 |
| 401 | UNAUTHORIZED | 토큰 누락·오류·만료, 다른 앱·Access Token, DELETING/DELETED 사용자 |
| 404 | NOT_FOUND | 세션 없음 또는 본인 소유가 아님. 두 경우 같은 응답 |
| 409 | RECOMMENDATION_EXISTS | 추천 결과가 이미 저장된 세션에 추천 생성 |
| 500 | INTERNAL_ERROR | 사용자 접근 검사·세션 저장소 오류, 유효하지 않은 필수 저장 데이터 |
| 502 | UPSTREAM_ERROR | TMDB 호출 실패 |

오류는 `{ "error": { "code": "NOT_FOUND", "message": "세션을 찾을 수 없습니다." } }` 공통 형식입니다. 내부 저장소 예외나 타인 세션 존재 여부를 노출하지 않습니다.

## 배포와 검증

`infra/user-api.yaml`에 목록 GET, 상세 리소스 GET·OPTIONS, 상세 호출 권한과 새 배포 스냅샷을 정의합니다. 기존 목록 경로 OPTIONS와 호출 권한은 재사용합니다. 실행 역할에는 기존 테이블 GetItem과 함께 **해당 GSI ARN만 허용하는 Query**를 추가합니다. 테이블·키·Cognito·기존 쓰기/탈퇴 권한은 유지합니다.

기존 [개발 환경](development.md)과 [컨테이너 배포](container-deployment.md) 절차를 따릅니다. 정상·빈 목록·페이지·소유권·과거 데이터·저장된 최신 필드·JWT·탈퇴 차단·CORS·저장소 실패를 로컬에서 확인한 뒤, 실제 배포와 임시 사용자 호출 결과를 별도로 기록합니다.

공식 기준: [DynamoDB Query 페이지 처리](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.Pagination.html), [GSI 읽기와 일관성](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html), [정렬 키 Query](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.KeyConditionExpressions.html).

## 삭제·추천 경로 검증·개발 배포 결과 (2026-10-30)

- #14를 반영한 작업 트리에서 DynamoDB Local을 포함한 Java 21 전체 테스트 177개, 실패·오류·건너뜀 0개와 bootJar 통과. 실제 DynamoDB Local에서 소유자 조건 삭제, 추천·analysis 저장과 탈퇴 중 저장 차단을 검증했습니다.
- OpenAPI 로컬 참조 71개, CloudFormation 3개 템플릿 cfn-lint, Bash 문법 검사 통과. 배포 메서드는 17개입니다.
- 소스 SHA: `964fa901ac5ec4fca7436cbf9afc71402bfc6b8bcb188dba288ef16c9d8374ec`. CodeBuild `recomate-build-dev:2516803b-e2c4-41ee-a80a-fec9debf37f9` SUCCEEDED. 이미지 `src-964fa901ac5ec4fc`, digest `sha256:19aafdda8b82be3b26d66d5bc2f6dfc9190957797b5a982fd6de59e23cc09053`. ECR 기본 스캔 COMPLETE·발견 항목 0개.
- 변경 계획 `session-commands-44ca240a9674960f`와 `recommendation-link-964fa901ac5ec4fc`를 사용자 승인 후 적용했습니다. TMDB 토큰은 사용자가 `TmdbAccessToken` 파라미터로 직접 설정했습니다. 스택 UPDATE_COMPLETE, Lambda Active/Successful, dev 배포 ID `zbut4a`, 추천 리소스 `6cnzrl`입니다. 테이블 교체·Cognito 리소스 변경은 없습니다.
- 실제 SRP 로그인과 기존 사용자·장르·세션 회귀, 삭제·추천 경로를 포함한 **146개 항목**을 통과했습니다. 실제 TMDB 호출로 TOP 3 생성과 저장, 상세 조회 일치, 재생성 409, analysis 세션 저장을 확인했습니다. 소유자 삭제 204와 반복 삭제 404, 타인 세션 동일 404와 데이터 보존, 밀리초 createdAt, 토큰 오류 401, CORS, 탈퇴 후 차단도 확인했습니다. 임시 사용자 3명과 세션을 정리했습니다. 비밀번호·JWT는 메모리에서만 사용했습니다.

TMDB 장애로 인한 실제 502, FE 브라우저 화면, 부하 검증은 이번 실제 호출에 포함하지 않았습니다. 502 처리는 로컬 테스트로 확인했습니다.

## 조회 API 검증·개발 배포 결과 (2026-10-30)

이 절은 조회 API 배포 당시 기록입니다. 삭제·추천 경로와 밀리초 형식은 이후 변경입니다.

- Java 21 Gradle 전체 테스트 149개, 실패·오류·건너뜀 0개와 bootJar 통과. 실제 DynamoDB Local에서 Query 페이지·정렬·본인 분리·삭제된 페이지 경계·기존/최신 저장 필드 읽기를 검증했습니다. 임시 로컬 DB 프로세스는 종료했습니다.
- 서명 JWT의 만료·issuer·audience·token_use·서명 오류를 두 조회 경로에서 검사했고, 접근 검사·조회 오류의 500은 로컬 오류 주입으로 확인했습니다.
- OpenAPI 로컬 참조 57개와 스키마 문법, CloudFormation 3개 템플릿의 cfn-lint, 선택 Guard 규칙, Compose·워크플로·Bash 검사 통과. 배포 메서드 14개의 스냅샷 의존성을 확인했고 기존 테이블·실행 역할·생성·장르 경로 정의는 유지했습니다. 기존 데이터 정책에는 해당 GSI의 Query만 추가했습니다.
- IAM Policy Autopilot 0.3.0으로 호출 분석 초안을 다시 생성하고 기존처럼 실제 호출·리소스로 범위를 좁혔습니다. Query는 `RecommendationSession/index/userId-createdAt-index` ARN만 허용합니다.
- 소스 SHA: `411753bd3e0c758b553f8f84e113e7f07624869fa03854299947c93b50517321`. CodeBuild `recomate-build-dev:89d8cbd2-90f7-45f6-83b8-3af786223a92` SUCCEEDED. linux/amd64 Docker 빌드와 읽기 전용 컨테이너 인증/CORS 검사 통과.
- 이미지: `src-411753bd3e0c758b`, digest `sha256:edada919ec19abd83cc9fcb5b563df70a6d7ea8708d4e32af6c203cacda4aa64`. ECR 기본 스캔 COMPLETE·발견 항목 0개.
- 변경 계획 `session-read-411753bd3e0c758b`는 CREATE_COMPLETE/AVAILABLE 및 서비스 검증 오류 없음 확인 후 사용자 승인으로 적용했습니다. 스택 UPDATE_COMPLETE, Lambda Active/Successful과 실제 이미지 digest를 확인했습니다. 테이블 교체·Cognito 리소스 변경은 없습니다.
- API Gateway의 목록 리소스 `k2bnt3`, 상세 리소스 `6n06gd`, dev 배포 ID `tt875t`. 두 GET은 기존 Cognito Authorizer `2oetm8`과 AWS_PROXY, OPTIONS는 인증 없이 기존 Spring CORS를 사용합니다.

개발용 기준 주소는 `https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev`입니다. 여기에 `/api/sessions` 또는 `/api/sessions/{sessionId}`를 붙입니다.

실제 Cognito SRP 로그인과 두 조회 API·기존 사용자·장르·세션 생성·탈퇴 회귀 검증 **117개 항목**을 통과했습니다. 프로필 없는 조회·빈 목록·최신순 요약·실제 페이지 이동·다른 사용자의 토큰 재사용 거절·동일한 404·저장된 최신 필드와 analysis·과거 누락 배열·조회 중 데이터 불변·탈퇴 후 차단을 확인했습니다. 임시 사용자 3명과 세션은 정리하고 최소 탈퇴 TTL 상태만 유지했습니다. 비밀번호·JWT는 메모리에서만 사용했습니다.

실제 검증의 추천 결과·analysis는 임시 사용자 세션에 저장한 테스트용 값입니다. TMDB·알고리즘·추천 생성 API나 FE 브라우저 화면 연결을 검증한 결과는 아닙니다. 실제 Cognito 토큰 만료를 새로 기다리는 테스트는 이번 117개에 포함하지 않았고, 만료 및 잘못된 JWT 조건은 위 로컬 테스트와 기존 #5의 공통 Authorizer 검증 기록을 따릅니다. 부하·동시 콜드 스타트 성능 검증도 포함하지 않습니다.

감사 기록은 기존 CloudTrail의 90일 이벤트 이력을 사용하며 별도 장기 보관 Trail은 구성하지 않았습니다. 문서에 없는 응답 정책은 이슈 #12에 공유했고 팀 최종 확인은 PR 리뷰에서 진행합니다.
