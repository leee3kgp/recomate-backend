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

`GET /api/genres`는 인증된 사용자에게 내부 장르 10개를 `{ "genres": [{ "code": "ACTION", "name": "액션" }, ...] }` 형식으로 반환합니다. 기존 FE 순서를 유지하며 개인 선호 장르를 읽거나 변경하지 않습니다. 사용자 API의 장르 코드 검증도 공통 `Genre` 정의를 사용합니다.

장르 목록을 위한 별도 DB·TMDB 호출은 없지만, 공통 탈퇴 상태 검사에는 사용자 테이블 접근이 필요합니다. 앱 프로필이 없어도 목록을 조회할 수 있으며 프로필을 생성하지 않습니다. 오류·전체 응답·FE 연결 안내는 [장르 목록 API](genre-api.md)와 [OpenAPI](openapi.yaml)를 참고합니다. 현재 로컬 구현이며 AWS 배포와 FE 연결은 아직 진행하지 않았습니다.

## 사용자 API와 저장소

사용자 API 5개는 `/api/users/me`, `/api/users/me/genres`에 있습니다. 입출력·오류·저장 구조는 [OpenAPI 명세](openapi.yaml)를 따릅니다. 닉네임은 DynamoDB가 기준이며, 프론트 화면은 프로필 API의 nickname을 표시합니다. 가입 시에만 Cognito에서 이메일과 닉네임을 가져옵니다.

프로필 응답·닉네임 처리·탈퇴 상태 전이는 담당자 승인으로 확정한 구현 기준입니다. 팀에는 이 계약을 공유하고, 실제 RecommendationSession 연결은 BE2 코드·테이블 설정을 확인한 뒤 진행합니다.

프로필 조회와 닉네임 수정의 성공 응답은 `userId`, `email`, `nickname` 세 필드이며 민감한 응답을 저장하지 않도록 `Cache-Control: no-store`를 포함합니다. 수정 가능한 필드는 닉네임 하나이며 요청으로 이메일·사용자 ID를 변경할 수 없습니다. 닉네임은 중복 가능한 표시 이름이고, 사용자 식별과 데이터 접근에는 검증된 JWT의 `sub`만 사용합니다. 앱 닉네임 변경은 DynamoDB에만 반영하고 Cognito 속성을 동기화하지 않습니다.

최초 Cognito 닉네임이 없거나 앱 규칙에 맞지 않으면 사용자가 다시 입력합니다. 최초 프로필 조회와 프로필 생성이 필요한 장르 저장은 `409 NICKNAME_REQUIRED`를 반환하고, 유효한 닉네임 PATCH로 프로필을 생성·복구한 뒤 재시도합니다. 앞뒤 공백을 제거한 1~20 Unicode code points를 허용하며 공백뿐인 값과 제어 문자를 거절합니다. 임의 기본값을 부여하지 않습니다. 정상 DynamoDB 프로필이 이미 있으면 Cognito 닉네임을 다시 검사하지 않습니다. 프론트는 이 오류 코드에만 닉네임 재입력 안내를 연결하며 일반 `500 INTERNAL_ERROR`와 구분해야 합니다.

`NICKNAME_REQUIRED` 응답 변경은 로컬 구현 상태이며 개발용 AWS에는 아직 배포하지 않았습니다. 프론트 화면 연결도 별도 반영이 필요합니다.

`USERS_TABLE`과 `SESSIONS_TABLE`로 실제 테이블명을 지정합니다. 기본값은 각각 `recomate-users-dev`, `recomate-sessions-dev`이며, 애플리케이션이 테이블을 자동 생성하지 않습니다.

| 저장소 | 필수 구성 |
| --- | --- |
| 사용자 전용 테이블 | PK `userId`(String), status/email/nickname/genres 속성. `expiresAt`(Number, epoch seconds) TTL 활성화. 권장: on-demand, 삭제 방지, PITR, 기본 암호화 |
| #3 추천 세션 테이블 | PK `userId`(String), SK `sessionId`(String). recommendations가 세션 항목 안에 저장됨 |

초기 프로필 생성은 조건부 PutItem, 닉네임·장르 변경은 ACTIVE 조건의 속성별 UpdateItem을 사용합니다. 읽기는 사용자 sub를 키로 한 강한 일관성 GetItem입니다. GSI·Streams·전체 테이블 Scan은 사용하지 않습니다.

탈퇴는 DELETING으로 변경하면서 프로필·장르를 제거하고, 자기 userId의 세션 전체(저장된 추천 결과 포함), Cognito 사용자를 순서대로 삭제합니다. 모두 성공한 뒤 userId/status/expiresAt만 48시간 남겨 기존 토큰으로 재생성을 막습니다. TTL 삭제는 지연될 수 있으며, 항목이 남아 있는 동안 접근을 계속 차단합니다. 일부 실패 시 500을 반환하고 DELETING 상태를 유지합니다. 실패 상태에는 TTL을 설정하지 않습니다.

이는 프로필·계정 삭제의 확정된 상태 전이이며, 세션 어댑터는 현재 검증용 구조를 대상으로 합니다. 실제 추천 세션·추천 결과의 물리적 저장 위치와 삭제 대상 연결은 BE2 구현 확인 전까지 완료로 취급하지 않습니다. 탈퇴 상태 기록에 실패하면 외부 데이터 삭제를 시작하지 않고, 이미 DELETED인 사용자에게 같은 DELETE를 반복하면 외부 삭제를 다시 수행하지 않고 204를 반환합니다. 최초 요청도 모든 삭제 단계가 성공한 경우에만 204를 반환합니다.

동일한 DELETE 요청은 남은 작업을 재시도합니다. 토큰이 만료된 뒤에는 운영자가 권한 있는 실행 환경에서 다음 명령으로 **이미 요청된 탈퇴만** 복구할 수 있습니다. sub는 기존 DELETING 항목의 userId를 사용합니다. 아직 ACTIVE이거나 존재하지 않는 사용자에게는 이 명령이 탈퇴를 시작하지 않습니다. 자동 재시도 스케줄러는 포함하지 않습니다.

```text
java -jar build/libs/recomate.jar --spring.main.web-application-type=none --recomate.deletion.resume-user-id=<Cognito-sub>
```

이 명령에도 실제 테이블 환경 변수와 AWS 접근 권한이 필요합니다. 네이티브 실행 시 사용할 개발자 프로필을 실행 환경에 지정하고, 배포 환경에서는 실행 역할을 사용합니다.

백엔드 실행 역할에 필요한 작업은 사용자 테이블의 `dynamodb:GetItem`, `PutItem`, `UpdateItem`, 세션 테이블의 `dynamodb:Query`, `BatchWriteItem`, 해당 User Pool의 `cognito-idp:AdminGetUser`, `AdminDeleteUser`입니다. 배포 시 각 리소스에 한정하여 부여합니다.

**BE2 연결 조건:** 새 세션 저장 전 사용자 프로필이 존재하도록 하고, 사용자 status=ACTIVE 확인과 세션 쓰기를 같은 DynamoDB 트랜잭션으로 처리해야 탈퇴와 동시 실행될 때 삭제 후 기록이 다시 생기지 않습니다. 이 트랜잭션의 사용자 테이블 ConditionCheck 권한도 해당 저장 기능 배포 시 추가합니다. 현재 API 접근 필터도 모든 `/api/**` 경로에서 탈퇴 상태를 검사합니다. 세션 생성 코드는 아직 이 저장소에 없습니다.

## 테스트 범위

기본 `test`는 AWS 없이 JWT·CORS·사용자 API·외부 서비스 오류를 검사합니다. 실제 DynamoDB 프로토콜 테스트는 로컬 DynamoDB 실행 후 `DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:18000`을 테스트 실행 환경에 지정하면 포함됩니다. 이 테스트는 loopback 주소만 허용하고 임의 이름의 로컬 테이블을 생성·정리하며, AWS 자격 증명을 사용하지 않습니다. CI에도 별도 로컬 DB 테스트 작업을 추가했습니다.

2026-10-27 기준 로컬 테스트 83개(실패·건너뜀 0개)를 통과했습니다. 프로필 응답 필드·캐시 방지, 수정 필드 제한, 닉네임 재입력 복구, 탈퇴 상태 기록 실패 시 삭제 중단과 완료 요청 반복 처리도 포함합니다. 개발용 AWS에 배포된 이전 이미지는 CodeBuild의 실제 Docker 빌드·읽기 전용 컨테이너 검사와 실제 API 통합 검증 53개를 통과했습니다. 실제 SRP 로그인·사용자별 데이터 분리·프로필과 장르 저장·추천 기록과 Cognito 삭제·탈퇴 후 기존 토큰 차단·잘못된 기존 닉네임 복구를 확인했고 임시 Cognito 사용자 3명은 정리했습니다. 정상 탈퇴의 최소 TTL 상태만 사용자 테이블에 48시간 유지합니다. 새 `409 NICKNAME_REQUIRED` 응답과 재입력 후 복구 흐름은 위 로컬 테스트에 포함되어 있으며 실제 AWS 검증은 아직 진행하지 않았습니다.

컨테이너 CI는 Compose v2.39.4를 명시하고 CodeBuild와 같은 401·CORS 검증 스크립트를 사용합니다. Compose 설정 검사와 actionlint 1.7.12 문법 검사는 통과했으며, 수정된 GitHub Actions 전체 실행은 아직 확인하지 않았습니다. 이 결과가 팀원의 코드 리뷰나 실제 BE2 기능과의 통합 검증을 대체하지 않습니다.

2026-10-28 장르 목록 API 추가 후에는 임시 DynamoDB Local을 포함한 전체 테스트 92개(실패·건너뜀 0개)와 JAR 빌드를 통과했습니다. 코드·표시명·순서와 개인 선호 장르 검증의 일치, JWT·탈퇴 상태·CORS 처리를 확인했습니다. 장르 API의 명세와 배포 템플릿은 로컬 검증 상태이며, AWS 배포·실제 컨테이너 재빌드·FE 연결은 아직 진행하지 않았습니다.

개발용 API 기준 주소는 `https://rdjkqhp2el.execute-api.ap-southeast-2.amazonaws.com/dev`이며, 예를 들어 프로필 경로는 이 주소 뒤에 `/api/users/me`를 붙입니다. 실제 배포 테이블은 `recomate-user-api-dev-users`, `recomate-user-api-dev-validation-sessions`입니다. 로컬에서 같은 테이블을 쓰려면 이 이름을 USERS_TABLE·SESSIONS_TABLE에 지정하고 별도의 개발자 인증을 연결합니다. 첫 호출이 약 26.3초로 관측되어 Lambda 메모리를 1536 MiB에서 3008 MiB로 조정했고, 업데이트 직후 첫 사전 요청은 7.4초, 연속 사전 요청은 0.65초였습니다. 이는 개별 측정이며 부하·동시 콜드 스타트 성능은 별도 검증 대상입니다.

## 관련 문서

- [컨테이너 구성과 AWS 배포](container-deployment.md)
- [장르 목록 API](genre-api.md)
- [프론트·API 명세 호환성 점검 기록](frontend-container-review.md)
- [Docker Desktop 설치 안내](https://docs.docker.com/desktop/setup/install/windows-install/)
