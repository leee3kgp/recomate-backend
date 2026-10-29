# 개발 환경

팀원이 같은 환경에서 백엔드를 빌드·실행하기 위한 안내입니다. Docker 구성은 백엔드만 실행합니다.

## Docker로 실행

준비할 도구는 Git과 Docker입니다. Compose 2.39 이상과 Buildx가 필요하며, Windows에서는 Docker 엔진을 Linux 컨테이너 모드로 실행합니다. 호스트의 Java·Gradle 별도 설치는 필요하지 않습니다.

프로젝트 폴더에서 실행합니다.

```text
docker compose up --build
```

최초 실행에는 이미지와 의존성을 받기 위한 인터넷 연결이 필요합니다. Docker 빌드 안에서 Gradle 테스트와 JAR 빌드를 수행한 뒤 서버를 시작합니다. 소스 수정 후에도 같은 명령으로 다시 빌드합니다.

| 작업 | 명령 |
| --- | --- |
| 백그라운드 실행 | `docker compose up --build -d` |
| 상태 확인 | `docker compose ps` |
| 로그 확인 | `docker compose logs -f api` |
| 종료 | `docker compose down` |
| 빌드·테스트만 수행 | `docker compose build` |

기본 주소는 `http://localhost:8080`입니다. Compose는 호스트의 로컬 인터페이스에만 포트를 공개합니다. 포트 변경은 `RECOMATE_PORT`로 지정할 수 있습니다.

프론트엔드는 별도로 실행해야 합니다. 브라우저의 API 주소에는 `localhost`의 공개 포트 또는 배포된 API 주소를 사용하며, Compose 서비스명 `api`를 사용하지 않습니다.

## Docker 없이 개발

IDE 디버깅이나 빠른 테스트에는 저장소의 Gradle Wrapper를 사용합니다. Wrapper를 시작할 수 있는 호환 Java가 필요하며, 처음 설치한다면 JDK 21을 권장합니다. 필요한 JDK 21이 없으면 toolchain resolver가 자동으로 준비합니다.

| 작업 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 서버 실행 | `.\gradlew.bat bootRun` | `./gradlew bootRun` |
| 실행 JAR 빌드 | `.\gradlew.bat bootJar` | `./gradlew bootJar` |
| Java 탐지 확인 | `.\gradlew.bat -q javaToolchains` | `./gradlew -q javaToolchains` |

빌드 결과는 `build/libs/recomate.jar`입니다. IDE에서도 Gradle Wrapper와 프로젝트 Java 21 toolchain을 사용하고, 개인 JDK 설치 경로를 저장소에 넣지 않습니다.

## AWS 접근

- 일반 이미지 빌드와 현재 기본 테스트에는 AWS CLI나 로그인이 필요하지 않습니다.
- 실제 AWS 데이터를 호출하는 기능에는 해당 개발자의 자격 증명과 접근 권한이 필요합니다.
- 네이티브 `bootRun`은 리전 `ap-southeast-2`, 기본 프로필명 `recomate`를 적용합니다. 기존 `AWS_PROFILE` 값이 있으면 그 값을 사용합니다.
- Docker 이미지에는 개인 AWS 프로필을 넣지 않습니다. 로컬 컨테이너에서 실제 AWS를 호출하려면 별도의 자격 증명 설정이 필요합니다.
- Lambda에서는 개인 프로필 대신 실행 역할을 사용합니다.

## 인증·브라우저 설정

API는 Cognito ID Token의 Bearer 인증을 사용합니다. 브라우저에는 JWT 서명·발급처·대상 앱·만료 검증을 통과한 요청만 허용합니다. 테스트는 자체 RSA 키와 로컬 JWK 서버를 사용하므로 AWS 로그인이 필요하지 않습니다.

공개 Cognito 식별자는 기존 프론트 설정과 일치하는 기본값을 사용합니다. 다른 환경은 `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`로 변경합니다. issuer는 리전과 User Pool ID로 구성하며, 테스트 등에서 필요하면 `COGNITO_ISSUER`로 지정합니다. 개인 AWS 접근 자격 증명과는 별개입니다.

CORS의 기본 허용 주소는 `http://localhost:5173`, `http://127.0.0.1:5173`입니다. 프론트 실행 포트나 배포 주소가 다르면 `CORS_ALLOWED_ORIGINS`에 정확한 origin을 쉼표로 구분해 지정합니다. 로컬 Spring 설정은 `src/main/resources/application-local.properties`에 저장하고 `local` 프로필로 실행할 수 있습니다. 이 파일은 Git과 Docker 빌드에서 제외됩니다. 컨테이너에서는 실행 시 환경 변수로 주입합니다.

## 장르 목록 API

전체 장르 목록은 `GET /api/genres`, 개인 선호 장르는 `/api/users/me/genres`를 사용합니다. 목록은 공통 `Genre`의 고정 코드·표시명 10개를 반환하며 별도 장르 테이블이나 TMDB 호출은 없습니다. 기존 JWT 검증과 사용자 탈퇴 상태 검사를 적용하고 앱 프로필은 생성하지 않습니다. 상세 응답과 FE 연결 기준은 [장르 목록 API](genre-api.md)를 참고합니다.

장르 브랜치에 최신 main의 사용자·세션 연결과 CI 구성을 반영한 작업 트리에서 2026-10-29 테스트 119개(실패·오류·건너뜀 0개)와 JAR 빌드를 통과했습니다. 장르 API는 로컬 구현 상태이며 AWS 배포·실제 FE 연결은 아직 진행하지 않았습니다.

## 사용자 API와 저장소

사용자 API 5개는 `/api/users/me`, `/api/users/me/genres`에 있습니다. 입출력·오류·저장 구조는 [OpenAPI 명세](openapi.yaml)를 따릅니다. 닉네임은 DynamoDB가 기준이며, 프론트 화면은 프로필 API의 nickname을 표시합니다. 가입 시에만 Cognito에서 이메일과 닉네임을 가져옵니다.

프로필 응답·닉네임 처리·탈퇴 상태 전이는 담당자 승인으로 확정한 구현 기준입니다. PR #9의 sessionId 기본 키와 세션 생성 코드를 통합했으며, 저장·탈퇴 연결 계약과 실제 AWS 검증 결과는 [세션 연결 문서](session-integration.md)에 정리했습니다.

프로필 조회와 닉네임 수정의 성공 응답은 `userId`, `email`, `nickname` 세 필드이며 민감한 응답을 저장하지 않도록 `Cache-Control: no-store`를 포함합니다. 수정 가능한 필드는 닉네임 하나이며 요청으로 이메일·사용자 ID를 변경할 수 없습니다. 닉네임은 중복 가능한 표시 이름이고, 사용자 식별과 데이터 접근에는 검증된 JWT의 `sub`만 사용합니다. 앱 닉네임 변경은 DynamoDB에만 반영하고 Cognito 속성을 동기화하지 않습니다.

최초 Cognito 닉네임이 없거나 앱 규칙에 맞지 않으면 사용자가 다시 입력합니다. 최초 프로필 조회와 프로필 생성이 필요한 장르 저장은 `409 NICKNAME_REQUIRED`를 반환하고, 유효한 닉네임 PATCH로 프로필을 생성·복구한 뒤 재시도합니다. 앞뒤 공백을 제거한 1~20 Unicode code points를 허용하며 공백뿐인 값과 제어 문자를 거절합니다. 임의 기본값을 부여하지 않습니다. 정상 DynamoDB 프로필이 이미 있으면 Cognito 닉네임을 다시 검사하지 않습니다. 프론트는 이 오류 코드에만 닉네임 재입력 안내를 연결하며 일반 `500 INTERNAL_ERROR`와 구분해야 합니다.

`NICKNAME_REQUIRED` 응답과 정상 PATCH 후 복구는 2026-10-28 개발용 AWS에 배포해 실제 호출로 확인했습니다. 프론트의 닉네임 재입력 화면 연결은 별도 반영이 필요합니다.

`USERS_TABLE`과 `SESSIONS_TABLE`로 실제 테이블명을 지정합니다. 기본값은 각각 `recomate-users-dev`, `RecommendationSession`이며 세션에는 BE2의 `SESSION_TABLE_NAME`도 호환합니다. 애플리케이션이 테이블을 자동 생성하지 않습니다.

| 저장소 | 필수 구성 |
| --- | --- |
| 사용자 전용 테이블 | PK `userId`(String), status/email/nickname/genres 속성. `expiresAt`(Number, epoch seconds) TTL 활성화. 권장: on-demand, 삭제 방지, PITR, 기본 암호화 |
| #3 공유 추천 세션 테이블 | PK `sessionId`(String). GSI `userId-createdAt-index`: PK userId, SK createdAt. participants/recommendations는 세션 안에 저장 |

초기 프로필 생성은 조건부 PutItem, 닉네임·장르 변경은 ACTIVE 조건의 속성별 UpdateItem을 사용합니다. 사용자 읽기는 sub를 키로 한 강한 일관성 GetItem입니다. 세션 생성·추천 결과 저장은 사용자 ACTIVE 조건과 동일한 트랜잭션으로 수행합니다. 탈퇴 시에는 GSI 반영 지연으로 삭제가 누락되지 않도록 기본 세션 테이블을 강한 일관성 Scan으로 읽고 본인 항목만 삭제합니다. 전체 테이블 크기에 비례하는 비용·시간 제약은 세션 연결 문서를 확인합니다.

탈퇴는 DELETING으로 변경하면서 프로필·장르를 제거하고, 자기 userId의 세션 전체(저장된 추천 결과 포함), Cognito 사용자를 순서대로 삭제합니다. 모두 성공한 뒤 userId/status/expiresAt만 48시간 남겨 기존 토큰으로 재생성을 막습니다. TTL 삭제는 지연될 수 있으며, 항목이 남아 있는 동안 접근을 계속 차단합니다. 일부 실패 시 500을 반환하고 DELETING 상태를 유지합니다. 실패 상태에는 TTL을 설정하지 않습니다.

이는 프로필·계정 삭제의 확정된 상태 전이입니다. 세션 어댑터는 PR #9의 sessionId 구조와 세션 안의 추천 결과를 대상으로 합니다. 실제 추천 생성 담당 코드도 공통 RecommendationRepository를 사용해야 탈퇴 중 저장 차단을 유지할 수 있습니다. 탈퇴 상태 기록에 실패하면 외부 데이터 삭제를 시작하지 않고, 이미 DELETED인 사용자에게 같은 DELETE를 반복하면 외부 삭제를 다시 수행하지 않고 204를 반환합니다. 최초 요청도 모든 삭제 단계가 성공한 경우에만 204를 반환합니다.

동일한 DELETE 요청은 남은 작업을 재시도합니다. 토큰이 만료된 뒤에는 운영자가 권한 있는 실행 환경에서 다음 명령으로 **이미 요청된 탈퇴만** 복구할 수 있습니다. sub는 기존 DELETING 항목의 userId를 사용합니다. 아직 ACTIVE이거나 존재하지 않는 사용자에게는 이 명령이 탈퇴를 시작하지 않습니다. 자동 재시도 스케줄러는 포함하지 않습니다.

```text
java -jar build/libs/recomate.jar --spring.main.web-application-type=none --recomate.deletion.resume-user-id=<Cognito-sub>
```

이 명령에도 실제 테이블 환경 변수와 AWS 접근 권한이 필요합니다. 네이티브 실행 시 사용할 개발자 프로필을 실행 환경에 지정하고, 배포 환경에서는 실행 역할을 사용합니다.

백엔드 실행 역할에 필요한 작업은 사용자 테이블의 `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `ConditionCheckItem`, 공유 세션 테이블의 `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `Scan`, `BatchWriteItem`, 해당 User Pool의 `cognito-idp:AdminGetUser`, `AdminDeleteUser`입니다. 코드 분석 초안을 기반으로 배포 대상 리소스에 한정합니다.

**BE2 연결:** POST /api/sessions는 기존 프로필 초기화와 공통 인증을 사용합니다. 세션·추천 쓰기는 사용자 ACTIVE 조건 트랜잭션으로 저장하고, 공통 필터도 모든 `/api/**` 경로에서 탈퇴 상태를 검사합니다. TMDB·추천 알고리즘·추천 생성 HTTP API는 포함하지 않으며 추천 담당자는 공통 저장소에 연결합니다.

## 테스트 범위

기본 `test`는 AWS 없이 JWT·CORS·사용자 API·외부 서비스 오류를 검사합니다. 실제 DynamoDB 프로토콜 테스트는 로컬 DynamoDB 실행 후 `DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:18000`을 테스트 실행 환경에 지정하면 포함됩니다. 이 테스트는 loopback 주소만 허용하고 임의 이름의 로컬 테이블을 생성·정리하며, AWS 자격 증명을 사용하지 않습니다. CI에도 별도 로컬 DB 테스트 작업을 추가했습니다.

2026-10-28 기준 DynamoDB Local을 포함한 테스트 110개(실패·오류·건너뜀 0개)와 bootJar를 통과했습니다. 프로필 응답 필드·캐시 방지, 수정 필드 제한, 닉네임 재입력 복구, 탈퇴 상태 기록 실패 시 삭제 중단, 삭제 재개·반복 처리와 실제 BE2 구조의 동시 세션 생성·탈퇴를 검사합니다. CodeBuild의 실제 Docker 빌드·읽기 전용 컨테이너 검사도 통과했습니다.

개발용 AWS 검증 항목 66개를 통과했습니다. 실제 SRP 로그인·사용자별 데이터 분리·프로필과 장르 저장·POST 세션 생성·공유 테이블의 추천 결과 저장·탈퇴 시 본인 데이터 및 Cognito 삭제·기존 토큰 차단·반복 탈퇴·잘못된 초기 닉네임의 409 응답 및 PATCH 복구를 확인했습니다. 실제 DynamoDB 트랜잭션도 탈퇴 후 세션 재생성을 거절했습니다. 정상 검증의 임시 Cognito 사용자 3명과 세션은 정리됐고, 검증 스크립트의 캐시 헤더 검사 수정 전 첫 시도에서 만든 임시 사용자 3명도 정리했습니다. 최소 TTL 탈퇴 상태만 사용자 테이블에 48시간 유지합니다. 비밀번호·토큰은 프로세스 메모리에서만 사용했습니다. 추천 결과는 테스트용 값을 저장한 것이며 TMDB·추천 알고리즘·추천 생성 HTTP API를 검증한 것은 아닙니다.

컨테이너 CI는 Compose v2.39.4를 명시하고 CodeBuild와 같은 401·CORS 검증 스크립트를 사용합니다. Compose 설정 검사와 actionlint 1.7.12 문법 검사는 기존 검증에서 통과했으며, main 통합 변경의 GitHub Actions 전체 실행은 푸시 후 확인합니다. 실제 BE2 구조의 저장·탈퇴 통합은 위 검증에 포함되며 팀 코드 리뷰와 FE 브라우저 화면 연결은 별도입니다.

개발용 API 기준 주소는 `https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev`이며, 예를 들어 프로필 경로는 이 주소 뒤에 `/api/users/me`를 붙입니다. 현재 사용 테이블은 `recomate-user-api-dev-users`, `RecommendationSession`입니다. 로컬에서 같은 테이블을 쓰려면 이 이름을 USERS_TABLE·SESSIONS_TABLE에 지정하고 별도의 개발자 인증을 연결합니다. 이전 `recomate-user-api-dev-validation-sessions`는 보존하지만 라이브 API는 사용하지 않습니다. Lambda 메모리는 기존 3008 MiB를 유지했습니다. 이번 배포 후 최초 세션 사전 요청은 약 12.7초, 재검증의 사전 요청은 약 0.3초였습니다. 이는 개별 측정이며 부하·동시 콜드 스타트 성능은 별도 검증 대상입니다.

## 관련 문서

- [컨테이너 구성과 AWS 배포](container-deployment.md)
- [장르 목록 API](genre-api.md)
- [프론트·API 명세 호환성 점검 기록](frontend-container-review.md)
- [Docker Desktop 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)
