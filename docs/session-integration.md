# 사용자 API와 BE2 세션 연결

PR #9(`e3387e00fe24617c5129649404c826ec379a6d45`, main 병합 커밋 `c2cd6d12c6f04cef394f03505f140cee3f0f550a`)의 모델·DTO·세션 생성 코드를 기준으로 #6을 연결합니다. 세션 기능을 사용자 API에 연결한 뒤, 2026-10-29에 해당 main 이력까지 feature/user-api에 통합했습니다. TMDB 호출·추천 점수 계산·추천 생성 HTTP API는 이번 범위에 포함하지 않습니다.

## main 통합 기준

사용자 API의 인증·탈퇴 보장과 검증된 배포 구성을 유지하면서 PR #9의 세션 모델·테이블·생성 계약을 사용합니다. 같은 경로의 세션 저장소·서비스·요청 DTO·테스트·설정 충돌은 현재 보완된 구현으로 해결했습니다.

- main의 `config.SecurityConfig`와 `DynamoDbConfig`를 중복 등록하지 않습니다. 인증은 `security.SecurityConfig`, DB 클라이언트는 `user.infrastructure.AwsClientConfig`를 함께 사용합니다.
- SDK는 단일 BOM 2.57.3과 signin 지원을 유지합니다. main의 dependencyManagement BOM 2.29.52를 함께 가져오면 SDK가 낮아져 현재 AWS 로그인 프로필을 읽지 못하는 문제가 재현됐습니다.
- 오류는 `ApiExceptionHandler`의 공통 envelope를 사용하고 장르는 `com.recomate.genre.Genre`로 통일합니다. 별도의 GlobalExceptionHandler·session.domain.Genre는 추가하지 않습니다.
- 사용자 식별·최소 프로필·닉네임 재입력·탈퇴 상태 전이는 기존 확정 정책을 유지합니다. 세션 생성의 ACTIVE 조건 트랜잭션도 유지합니다.
- 기존 Docker·Compose·CodeBuild·CloudFormation 설정과 공유 테이블을 사용합니다. main 통합을 위해 데이터를 삭제하거나 테이블을 다시 만들지 않습니다.

팀장님의 세션 생성 요청과 201 응답·Location·UUID·인원/참여자 검증·정렬·중복 장르 제거는 유지됩니다. 공통 오류 형식과 409 NICKNAME_REQUIRED, 추천 담당자의 RecommendationRepository 사용 규칙은 이 문서와 OpenAPI를 기준으로 공유합니다.

2026-10-29 실제 통합 작업 브랜치에서 DynamoDB Local을 실행하고 Gradle test·bootJar를 캐시 재사용 없이 검증했습니다. 테스트 110개, 실패·오류·건너뜀 0개이며 JAR 빌드가 성공했습니다. dependencyInsight의 DynamoDB·enhanced 실제 버전은 모두 2.57.3입니다. CloudFormation 3개 템플릿의 cfn-lint, 선택한 Guard 규칙, Compose 설정과 GitHub Actions 문법 검사도 통과했습니다.

통합 후 생성한 소스 ZIP 해시는 기존 검증 이미지의 `f603fb4e2abe4ed643c8ceeaf46fd8d570e1c0ae75566a64c13711143eb46544`와 같습니다. 실제 Lambda는 동일한 이미지 digest로 Active/Successful이며 공유 세션 테이블 연결도 유지됐습니다. 이번 Git 통합을 위해 AWS 리소스를 다시 배포하지 않았습니다. GitHub Actions 실행 결과는 브랜치를 푸시한 뒤 별도로 확인합니다.

## 공통 저장 계약

- 실제 공유 테이블: `RecommendationSession`. `SESSIONS_TABLE`로 변경할 수 있고 기존 BE2의 `SESSION_TABLE_NAME`도 호환합니다.
- 기본 키: `sessionId`(String) 하나. 신규 세션 ID는 BE2 상세 명세대로 UUID입니다.
- GSI: `userId-createdAt-index`, PK `userId`, SK `createdAt`, projection ALL. 일반 사용자별 목록 조회용이며 탈퇴 완료 판정에는 사용하지 않습니다.
- 참여자와 추천 결과는 세션 항목의 `participants`, `recommendations` 안에 저장합니다. 별도 추천 저장소는 현재 없습니다.
- `createdAt`은 UTC ISO 8601, 추천 `mediaType`은 `movie` 또는 `tv`입니다.
- 장르 코드는 `com.recomate.genre.Genre`를 함께 사용합니다. 장르 목록 HTTP API는 별도 장르 브랜치에서 관리합니다.

## 세션 생성과 인증 통합

`POST /api/sessions`는 PR #9의 요청·성공 응답과 UUID·중복 장르 제거 규칙을 유지합니다. 사용자 식별에는 검증된 Cognito ID Token의 sub만 사용합니다. 인원수 2~6, 참여자 수 일치, 연속된 index, 유효한 장르 입력을 검사하고 index 순서로 저장합니다. null 참여자도 400으로 거절합니다.

프로필이 없으면 기존 사용자 API 로직으로 먼저 초기화합니다. 최초 닉네임이 유효하지 않으면 기존 `409 NICKNAME_REQUIRED`로 재입력을 요청하고 세션을 저장하지 않습니다. 기존 프로필의 닉네임 규칙과 탈퇴 상태 전이는 그대로 유지합니다.

BE2의 별도 SecurityConfig·DynamoDbConfig·GlobalExceptionHandler를 중복 등록하지 않습니다. 기존 JWT 검증·DB 클라이언트·CORS·오류 처리를 재사용하고 CORS에 POST를 추가했습니다. 세션 오류도 `{ "error": { "code": "...", "message": "..." } }` 공통 형식입니다. 필드 검증·잘못된 JSON은 `VALIDATION_ERROR`, 인원 불일치·index·장르 규칙 오류는 `INVALID_REQUEST`입니다. 이 공통 envelope는 기존 사용자 API와 일치하며 BE2가 작성한 최상위 code/message 문서와 FE 계약을 함께 갱신해야 합니다.

## 탈퇴 중 저장 차단

`DynamoSessionRepository.save`는 다음 두 작업을 동일한 DynamoDB 트랜잭션으로 실행합니다.

1. 사용자 프로필이 존재하고 status가 ACTIVE인지 ConditionCheck.
2. sessionId가 아직 없는 경우에만 세션 Put.

사용자 상태를 먼저 읽고 나중에 단순 Put하는 방식은 사용하지 않습니다. 탈퇴가 DELETING을 기록하면 이후 저장은 원자적으로 거절됩니다.

추천 생성 담당 코드는 `RecommendationRepository.saveRecommendations(userId, sessionId, results)`를 사용합니다. 이 저장소는 같은 ACTIVE 조건과 함께 본인 세션이 존재하고 기존 추천이 비어 있는 경우에만 recommendations 속성을 변경합니다. 없는 세션을 다시 생성하지 않고 타인 세션은 404, 기존 추천은 409, 탈퇴 상태는 401로 처리합니다. 최대 3개·연속 순위·양수 TMDB ID·movie/tv 유형·콘텐츠 중복을 검사합니다. 실제 추천 API의 TMDB·알고리즘 호출은 담당 코드에서 수행하며 이 저장 메서드에 연결해야 합니다.

## 삭제의 정확성과 비용

사용자를 DELETING으로 바꾸고 프로필·선호 장르를 제거한 뒤, 공유 세션 테이블을 강한 일관성 Scan으로 읽어 해당 userId의 항목만 삭제합니다. 삭제 키는 sessionId 하나이며 페이지를 끝까지 확인하고 최대 25개씩 BatchWriteItem으로 삭제합니다. 비어 있는 필터 결과에도 다음 페이지가 있으면 계속합니다. 미처리 항목은 재시도하고 실패하면 DELETING을 유지합니다.

GSI는 최근 쓰기가 늦게 보일 수 있어 GSI의 빈 결과로 삭제 완료를 판정하지 않습니다. 모든 세션·추천 쓰기가 위 ACTIVE 트랜잭션을 사용할 때, DELETING 이후 해당 사용자의 데이터는 새로 추가·변경되지 않으므로 기본 테이블에서 삭제 대상을 확인할 수 있습니다. 세션 삭제, Cognito 삭제가 모두 성공한 경우에만 DELETED와 기존 48시간 TTL을 기록합니다.

이 Scan은 탈퇴 요청에만 사용하지만 전체 테이블 크기에 비례해 읽기 비용과 시간이 듭니다. 큰 테이블에서는 HTTP/Lambda 시간 제한에 걸릴 수 있고 실패를 성공으로 처리하지 않습니다. 기존 비 HTTP 탈퇴 재개 명령으로 남은 삭제를 끝낼 수 있습니다. 규모가 커지면 사용자별 강한 일관성 소유권 목록 또는 비동기 삭제 작업으로 확장해야 합니다. 외부 코드가 일반 Put/Update로 ACTIVE 트랜잭션을 우회하면 이 보장은 유지되지 않습니다.

## 배포 구성

`infra/sessions.yaml`은 공유 테이블을 별도 스택으로 생성합니다. 기본 이름은 BE2 설정과 같은 RecommendationSession이며, on-demand·PITR·삭제 방지·Retain을 사용합니다. 기존 테이블이 있으면 같은 이름의 테이블을 새로 생성하지 말고 해당 테이블 설정을 확인해 연결합니다.

`infra/user-api.yaml`의 `ExistingSessionsTableName`에 공유 테이블 이름을 지정합니다. 기존 `recomate-user-api-dev-validation-sessions`는 `PreserveLegacyValidationTable=true`로 보존하며 계속 기존 스택에서 관리합니다. 라이브 코드는 그 검증용 테이블에 더 이상 접근하지 않습니다. 사용자 테이블·Cognito·기존 JWT 테스트 Stage를 재생성하지 않습니다.

Java SDK 분석으로 생성한 IAM Policy Autopilot 초안에서 실제 Put/Update 트랜잭션에 필요한 작업을 사용자·세션 테이블 ARN별로 좁혔습니다. 사용하지 않는 트랜잭션 Delete, 복제 및 고객 KMS 키 권한은 제외합니다. 세션 POST·OPTIONS와 새 API Deployment 스냅샷을 기존 dev Stage에 연결합니다.

## 로컬 검증

임시 DynamoDB Local에서 PR #9와 같은 기본 키·GSI를 생성해 실제 세션 생성, 참여자·빈 추천 저장, 추천 결과 저장, 본인 소유권, 중복 결과 거절, 탈퇴 중 쓰기 차단, 동시 생성·탈퇴, 세션·추천 삭제를 검사합니다. 기존 사용자 API·닉네임 재입력·삭제 재개 테스트도 함께 실행합니다. 2026-10-28 Java 21 Gradle 검증은 테스트 110개, 실패·오류·건너뜀 0개이며 bootJar도 성공했습니다. 로컬 테스트 성공과 실제 AWS 배포 성공은 별도로 기록합니다.

## AWS 변경 계획 검증

CodeBuild `recomate-build-dev:70379989-06c3-4836-a9a9-f4c3b31d5dc8`이 성공했습니다. 소스 해시는 `f603fb4e2abe4ed643c8ceeaf46fd8d570e1c0ae75566a64c13711143eb46544`, 이미지 태그는 `src-f603fb4e2abe4ed6`, digest는 `sha256:6393e97c1e98590ae957dbd741fc679e633291caccbf92b096b58d948f04d6f1`입니다. CodeBuild는 실제 Docker 빌드와 읽기 전용 컨테이너의 미인증·CORS 검사를 수행했습니다. ECR basic scan도 COMPLETE이며 발견 항목은 0개였습니다. 이는 해당 스캐너의 검사 시점 결과이며 전체 애플리케이션의 취약점 부재를 의미하지는 않습니다.

`recomate-sessions-dev`의 `user6-shared-sessions` 변경 계획은 공유 테이블 1개를 추가합니다. `recomate-user-api-dev`의 `user6-session-integration` 변경 계획은 Lambda 코드·공유 테이블 환경 변수·데이터 권한을 갱신하고 세션 경로와 새 API 배포 스냅샷을 추가합니다. 기존 UsersTable은 변경 대상이 아니며 ValidationSessionsTable은 메타데이터만 바뀌고 교체되지 않습니다. 이전 API Deployment 스냅샷은 새 스냅샷으로 교체됩니다.

두 템플릿의 로컬 cfn-lint 오류·경고는 0개, 선택한 Guard 규칙 위반은 0개이며 CloudFormation 변경 계획의 서비스 사전 검증 오류도 0개입니다. 변경 계획 작성은 실제 적용과 구분합니다. 지속 CloudTrail trail이 없는 기존 프로젝트의 장기 감사 기록 제한은 그대로 남아 있습니다.

## 실제 AWS 적용·검증 결과 (2026-10-28)

사용자 승인 후 위 두 변경 계획을 적용했습니다. `recomate-sessions-dev`는 CREATE_COMPLETE, `recomate-user-api-dev`는 UPDATE_COMPLETE이며 Lambda의 실제 이미지 digest가 위 빌드 결과와 일치합니다. SESSIONS_TABLE은 RecommendationSession입니다. 공유 테이블·GSI는 ACTIVE, PITR·삭제 방지는 활성화됐습니다. 기존 사용자 테이블과 검증용 테이블의 물리 이름·키는 유지됐으며 두 테이블 모두 기존 스택에서 관리합니다.

개발 API는 `https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev`입니다. POST /api/sessions는 기존 Cognito Authorizer `2oetm8`을 적용하고 OPTIONS는 인증 없이 Lambda로 전달합니다. 사용자 API와 세션 API의 CORS는 `http://localhost:5173`에서 확인했습니다.

실제 검증 항목 66개가 통과했습니다.

- 임시 사용자 3명의 실제 SRP 로그인과 ID Token 인증, 토큰 누락·오류의 401, 사용자·세션 사전 요청.
- 최소 프로필 응답, 닉네임 변경·Cognito 초기값과 분리, 선호 장르 조회·전체 교체·비우기, 잘못된 입력 400.
- POST 세션 3건의 201·Location·UUID·소유자·참여자 정렬·중복 장르 정리·빈 초기 추천과 실제 공유 테이블 저장.
- 테스트 추천 결과가 포함된 본인 세션 삭제, 다른 사용자의 프로필·세션 보존, Cognito 삭제, 최소 48시간 TTL 상태.
- 탈퇴 후 기존 토큰의 사용자·세션 접근 차단, 반복 DELETE의 204, 실제 DynamoDB ACTIVE 조건 트랜잭션의 세션 재생성 거절.
- 유효하지 않은 초기 닉네임에 대한 프로필 조회·장르 저장·세션 생성의 409 NICKNAME_REQUIRED, 유효한 PATCH 후 복구.

추천 결과는 임시 사용자 세션에 테스트용 값을 저장했습니다. 실제 추천 생성 HTTP API는 아직 없으며 그 API나 TMDB·점수 계산을 검증했다는 의미가 아닙니다. Java RecommendationRepository의 소유권·중복·탈퇴 동시성 동작은 위 로컬 테스트로 확인했습니다.

성공 검증의 임시 사용자 3명과 세션을 정리했습니다. 캐시 헤더 검사 조건을 바로잡기 전 첫 시도의 임시 사용자 3명도 정리했습니다. 토큰·비밀번호는 저장하거나 출력하지 않았고 최소 탈퇴 TTL 상태만 남습니다. 재검증용 스크립트·비밀값 없는 결과 JSON은 Git에서 제외한 `.gradle/aws-smoke/user6-live.cjs`, `.gradle/deployment/user6-live-report.json`에 있습니다. 이 AWS 검증은 Git 통합 전인 2026-10-28에 수행한 결과이며 이후 main 통합은 위 기준으로 진행했습니다. Git 푸시와 PR 생성·병합은 별도 절차입니다.
